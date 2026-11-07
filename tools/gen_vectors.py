import struct, hmac, hashlib, json
import tinytuya
from tinytuya.core import header as H, command_types as CT
from tinytuya.core.message_helper import pack_message, unpack_message, TuyaMessage, MessagePayload
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.udp_helper import decrypt_udp, udpkey

KEY = b"0123456789abcdef"
DEV = "bf0000000000000000abcd"
out = {}
def hx(b): return b.hex()

ctrl = b'{"devId":"bf0000000000000000abcd","dps":{"1":true}}'
for ver in (3.3, 3.4):
    d = tinytuya.OutletDevice(DEV, "127.0.0.1", KEY.decode(), version=ver)
    d.seqno = 1
    cmd = CT.CONTROL if ver == 3.3 else CT.CONTROL_NEW
    out[f"enc{ver}"] = hx(d._encode_message(MessagePayload(cmd, ctrl)))
    d.seqno = 5
    out[f"query{ver}"] = hx(d._encode_message(MessagePayload(CT.DP_QUERY if ver==3.3 else CT.DP_QUERY_NEW, b'{}')))

# 3.5 with fixed iv
iv = bytes(range(12))
payload = b"3.5" + b"\0"*12 + ctrl
m = TuyaMessage(1, CT.CONTROL_NEW, None, payload, 0, True, H.PREFIX_6699_VALUE, iv)
out["enc3.5"] = hx(pack_message(m, hmac_key=KEY))
out["iv"] = hx(iv)

# device -> client STATUS frames
status = b'{"dps":{"1":true,"19":1234,"20":2301,"18":567}}'
c = AESCipher(KEY)
p33 = struct.pack(">I", 0) + b"3.3" + b"\0"*12 + c.encrypt(status, False)
out["dev3.3"] = hx(pack_message(TuyaMessage(7, CT.STATUS, 0, p33, 0, True, H.PREFIX_55AA_VALUE, False)))
p34 = struct.pack(">I", 0) + c.encrypt(b"3.4" + b"\0"*12 + b'{"protocol":4,"t":1,"data":{"dps":{"1":false,"19":55}}}', False)
out["dev3.4"] = hx(pack_message(TuyaMessage(8, CT.STATUS, 0, p34, 0, True, H.PREFIX_55AA_VALUE, False), hmac_key=KEY))
m = TuyaMessage(9, CT.STATUS, 0, b"3.5"+b"\0"*12+b'{"protocol":4,"data":{"dps":{"20":2290}}}', 0, True, H.PREFIX_6699_VALUE, iv)
out["dev3.5"] = hx(pack_message(m, hmac_key=KEY))

# verify tinytuya itself decodes them
for ver, key in (("3.3", None), ("3.4", KEY)):
    d = tinytuya.OutletDevice(DEV, "127.0.0.1", KEY.decode(), version=float(ver))
    msg = unpack_message(bytes.fromhex(out["dev"+ver]), hmac_key=key)
    print(ver, "crc_good", msg.crc_good, d._decode_payload(msg.payload))
msg = unpack_message(bytes.fromhex(out["dev3.5"]), hmac_key=KEY)
print("3.5", msg.crc_good, msg.payload)

# session negotiation 3.4 & 3.5
ln = b"LOCALNONCE123456"; rn = b"REMOTENONCE65432"
for ver in (3.4, 3.5):
    d = tinytuya.OutletDevice(DEV, "127.0.0.1", KEY.decode(), version=ver)
    d.seqno = 1
    d.local_nonce = ln; d.remote_nonce = b''; d.local_key = d.real_local_key
    start = d._encode_message(MessagePayload(CT.SESS_KEY_NEG_START, ln))
    resp_plain = rn + hmac.new(KEY, ln, hashlib.sha256).digest()
    if ver == 3.4:
        rp = struct.pack(">I",0) + c.encrypt(resp_plain, False)
        resp = pack_message(TuyaMessage(1, CT.SESS_KEY_NEG_RESP, 0, rp, 0, True, H.PREFIX_55AA_VALUE, False), hmac_key=KEY)
        rmsg = unpack_message(resp, hmac_key=KEY)
    else:
        resp = pack_message(TuyaMessage(1, CT.SESS_KEY_NEG_RESP, 0, resp_plain, 0, True, H.PREFIX_6699_VALUE, iv), hmac_key=KEY)
        rmsg = unpack_message(resp, hmac_key=KEY)
    step3 = d._negotiate_session_key_generate_step_3(rmsg)
    if ver == 3.4:
        f3 = d._encode_message(step3)
    else:
        f3 = None  # random iv; compare payload only
    d._negotiate_session_key_generate_finalize()
    out[f"neg{ver}_start"] = hx(start) if ver == 3.4 else None
    out[f"neg{ver}_resp"] = hx(resp)
    out[f"neg{ver}_step3"] = hx(f3) if f3 else None
    out[f"neg{ver}_session"] = hx(d.local_key)
    out[f"neg{ver}_step3payload"] = hx(step3.payload)

# UDP broadcast (6667 style)
bjson = b'{"ip":"192.168.1.77","gwId":"bf0000000000000000abcd","active":2,"ability":0,"mode":0,"encrypt":true,"productKey":"keyabc","version":"3.3"}'
udp = pack_message(TuyaMessage(0, 0x13, 0, struct.pack(">I",0)+AESCipher(udpkey).encrypt(bjson, False), 0, True, H.PREFIX_55AA_VALUE, False))
out["udp"] = hx(udp)
print("udp decode", decrypt_udp(udp))
m = TuyaMessage(0, 0x13, None, b'{"ip":"192.168.1.78","gwId":"bf1","version":"3.5"}', 0, True, H.PREFIX_6699_VALUE, iv)
out["udp35"] = hx(pack_message(m, hmac_key=udpkey))
print("udp35 decode", decrypt_udp(bytes.fromhex(out["udp35"])))

# cloud sign
cid, sec, tok, t = "cid123", "secretXYZ", "tok456", "1700000000000"
body = '{"commands":[{"code":"switch_1","value":true}]}'
path = "/v1.0/iot-03/devices/bf1/commands"
sts = "POST\n" + hashlib.sha256(body.encode()).hexdigest() + "\n\n" + path
out["sign"] = hmac.new(sec.encode(), (cid+tok+t+sts).encode(), hashlib.sha256).hexdigest().upper()
sts2 = "GET\n" + hashlib.sha256(b"").hexdigest() + "\n\n/v1.0/token?grant_type=1"
out["signtoken"] = hmac.new(sec.encode(), (cid+t+sts2).encode(), hashlib.sha256).hexdigest().upper()
json.dump(out, open("vectors.json","w"), indent=1)
for k,v in out.items(): print(k, (v or "")[:60])
