"""Імітація розетки Tuya (3.3/3.4/3.5) на базі tinytuya — для перевірки клієнта з прошивки.
python3 fake_plug.py <port> <version> <local_key>   (одне з'єднання за раз, як справжня розетка)"""
import socket, struct, sys, hmac, hashlib, json, os
from tinytuya.core import header as H, command_types as CT
from tinytuya.core.message_helper import pack_message, unpack_message, TuyaMessage
from tinytuya.core.crypto_helper import AESCipher

port, ver, KEY = int(sys.argv[1]), sys.argv[2], sys.argv[3].encode()
state = {"1": False, "19": 1234}
NOHDR = {CT.DP_QUERY, CT.DP_QUERY_NEW, CT.UPDATEDPS, CT.HEART_BEAT, 3, 4, 5}

def recv_frame(c, key):
    buf = b""
    while len(buf) < 18:
        d = c.recv(4096)
        if not d: return None
        buf += d
    if buf[:4] == b"\0\0\x55\xaa": total = 16 + struct.unpack(">I", buf[12:16])[0]
    else: total = 18 + struct.unpack(">I", buf[14:18])[0] + 4
    while len(buf) < total: buf += c.recv(4096)
    hk = key if ver != "3.3" else None
    m = unpack_message(buf[:total], hmac_key=hk, no_retcode=True)
    p = m.payload
    if ver == "3.3":
        if p[:3] == b"3.3": p = p[15:]
        p = AESCipher(key).decrypt(p, False, decode_text=False)
    elif ver == "3.4":
        p = AESCipher(key).decrypt(p, False, decode_text=False)
        if p[:3] == b"3.4": p = p[15:]
    else:
        if p[:3] == b"3.5": p = p[15:]
    return m.seqno, m.cmd, p

def send(c, key, seq, cmd, plain):
    if ver == "3.5":
        m = TuyaMessage(seq, cmd, 0, plain, 0, True, H.PREFIX_6699_VALUE, os.urandom(12))
        c.sendall(pack_message(m, hmac_key=key)); return
    if ver == "3.4":
        hdr = b"" if cmd in NOHDR else b"3.4" + b"\0" * 12
        body = struct.pack(">I", 0) + AESCipher(key).encrypt(hdr + plain, False)
        c.sendall(pack_message(TuyaMessage(seq, cmd, 0, body, 0, True, H.PREFIX_55AA_VALUE, False), hmac_key=key)); return
    enc = AESCipher(key).encrypt(plain, False)
    body = struct.pack(">I", 0) + ((b"3.3" + b"\0" * 12) if cmd == CT.STATUS else b"") + enc
    c.sendall(pack_message(TuyaMessage(seq, cmd, 0, body, 0, True, H.PREFIX_55AA_VALUE, False)))

def status_json():
    return json.dumps({"dps": state} if ver == "3.3" else {"protocol": 4, "t": 1, "data": {"dps": state}}).encode()

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port)); srv.listen(1)
print("ready", flush=True)
while True:
    c, _ = srv.accept(); c.settimeout(5)
    key = KEY; ln = rn = None
    try:
        while True:
            f = recv_frame(c, key)
            if f is None: break
            seq, cmd, p = f
            if cmd == 3:
                ln = p; rn = os.urandom(16)
                send(c, key, seq, 4, rn + hmac.new(KEY, ln, hashlib.sha256).digest())
            elif cmd == 5:
                assert p[:32] == hmac.new(KEY, rn, hashlib.sha256).digest(), "bad step3"
                x = bytes(a ^ b for a, b in zip(ln, rn))
                if ver == "3.4": key = AESCipher(KEY).encrypt(x, False, pad=False)
                else: key = AESCipher(KEY).encrypt(x, use_base64=False, pad=False, iv=ln[:12])[12:28]
            elif cmd in (CT.CONTROL, CT.CONTROL_NEW):
                j = json.loads(p)
                dps = j["dps"] if ver == "3.3" else j["data"]["dps"]
                state.update(dps); print("set", dps, flush=True)
                send(c, key, seq, cmd, b"")
                send(c, key, 0, CT.STATUS, status_json())
            elif cmd in (CT.DP_QUERY, CT.DP_QUERY_NEW):
                send(c, key, seq, cmd, json.dumps({"dps": state}).encode())
    except Exception as e:
        print("err", repr(e), flush=True)
    c.close()
