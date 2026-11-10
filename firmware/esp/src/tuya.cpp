#if !defined(ESP8266)   // на ESP8266 бракує місця й апаратного AES — лише ESP32-сімейство

#include "tuya.h"

#include <string.h>

namespace tuya {

static const uint32_t PREFIX_55AA = 0x000055AA, SUFFIX_55AA = 0x0000AA55;
static const uint32_t PREFIX_6699 = 0x00006699, SUFFIX_6699 = 0x00009966;

static void put32(Bytes& b, uint32_t v) {
  b.push_back(v >> 24); b.push_back(v >> 16); b.push_back(v >> 8); b.push_back(v);
}
static uint32_t get32(const uint8_t* p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }

uint32_t crc32(const uint8_t* d, size_t n) {
  uint32_t c = 0xFFFFFFFF;
  for (size_t i = 0; i < n; i++) {
    c ^= d[i];
    for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320 & (0 - (c & 1)));
  }
  return ~c;
}

Ver parseVer(const char* s) {
  if (s && strcmp(s, "3.4") == 0) return V34;
  if (s && strcmp(s, "3.5") == 0) return V35;
  return V33;
}

static Bytes pad(const uint8_t* d, size_t n) {
  size_t p = 16 - (n % 16);
  Bytes out(d, d + n);
  out.insert(out.end(), p, (uint8_t)p);
  return out;
}

static void unpad(Bytes& b) {
  if (b.empty()) return;
  uint8_t n = b.back();
  if (n < 1 || n > 16 || n > b.size()) return;
  for (size_t i = b.size() - n; i < b.size(); i++) if (b[i] != n) return;
  b.resize(b.size() - n);
}

static Bytes ecb(const uint8_t key[16], const Bytes& in, bool enc) {
  Bytes out(in.size());
  aesEcb(key, in.data(), out.data(), in.size(), enc);
  return out;
}

static bool noHeader(int cmd) {
  return cmd == DP_QUERY || cmd == DP_QUERY_NEW || cmd == UPDATEDPS || cmd == HEART_BEAT ||
         cmd == SESS_KEY_NEG_START || cmd == SESS_KEY_NEG_RESP || cmd == SESS_KEY_NEG_FINISH || cmd == 0x40;
}

static void verHeader(Ver v, Bytes& b) {
  const char* s = v == V33 ? "3.3" : v == V34 ? "3.4" : "3.5";
  b.insert(b.end(), s, s + 3);
  b.insert(b.end(), 12, 0);
}

static Bytes frame55(uint32_t seq, int cmd, const Bytes& body, const uint8_t* hmacKey) {
  Bytes f;
  put32(f, PREFIX_55AA); put32(f, seq); put32(f, cmd);
  put32(f, body.size() + (hmacKey ? 36 : 8));
  f.insert(f.end(), body.begin(), body.end());
  if (hmacKey) {
    uint8_t h[32];
    hmac256(hmacKey, 16, f.data(), f.size(), h);
    f.insert(f.end(), h, h + 32);
  } else {
    put32(f, crc32(f.data(), f.size()));
  }
  put32(f, SUFFIX_55AA);
  return f;
}

static Bytes frame6699(uint32_t seq, int cmd, const Bytes& plain, const uint8_t key[16], const uint8_t iv[12]) {
  Bytes f;
  put32(f, PREFIX_6699); f.push_back(0); f.push_back(0);
  put32(f, seq); put32(f, cmd); put32(f, 12 + plain.size() + 16);
  Bytes aad(f.begin() + 4, f.end());
  f.insert(f.end(), iv, iv + 12);
  Bytes ct(plain.size());
  uint8_t tag[16];
  gcm(key, iv, aad.data(), aad.size(), plain.data(), plain.size(), ct.data(), tag, true);
  f.insert(f.end(), ct.begin(), ct.end());
  f.insert(f.end(), tag, tag + 16);
  put32(f, SUFFIX_6699);
  return f;
}

int frameLength(const uint8_t* h, size_t avail) {
  if (avail < 4) return -1;
  uint32_t p = get32(h);
  if (p == PREFIX_55AA) return avail < 16 ? -1 : (int)(16 + get32(h + 12));
  if (p == PREFIX_6699) return avail < 18 ? -1 : (int)(18 + get32(h + 14) + 4);
  return -2;
}

Session::Session(Ver v, const char* localKey) : ver(v) {
  memset(real, 0, 16);
  size_t n = strlen(localKey);
  memcpy(real, localKey, n > 16 ? 16 : n);
  memcpy(key, real, 16);
  memset(localNonce, 0, 16);
  memset(remoteNonce, 0, 16);
}

Bytes Session::encode(int cmd, const uint8_t* payload, size_t len, const uint8_t* fixedIv) {
  bool header = !noHeader(cmd);
  uint32_t s = seq++;
  Bytes p;
  if (ver == V33) {
    Bytes enc = ecb(key, pad(payload, len), true);
    if (header) { verHeader(ver, p); }
    p.insert(p.end(), enc.begin(), enc.end());
    return frame55(s, cmd, p, nullptr);
  }
  if (header) verHeader(ver, p);
  p.insert(p.end(), payload, payload + len);
  if (ver == V34) return frame55(s, cmd, ecb(key, pad(p.data(), p.size()), true), key);
  uint8_t iv[12];
  if (fixedIv) memcpy(iv, fixedIv, 12); else randomBytes(iv, 12);
  return frame6699(s, cmd, p, key, iv);
}

static bool looksLikeRetcode(const Bytes& b) {
  return b.size() >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] != '{';
}

static void stripVersion(Bytes& b) {
  if (b.size() >= 15 && b[0] == '3' && b[1] == '.') b.erase(b.begin(), b.begin() + 15);
}

bool Session::decode(const uint8_t* f, size_t len, int* cmd, std::string* text) {
  int total = frameLength(f, len);
  if (total < 0 || (size_t)total > len) return false;
  Bytes body;
  if (get32(f) == PREFIX_55AA) {
    *cmd = get32(f + 8);
    size_t endLen = ver == V34 ? 36 : 8;
    size_t l = get32(f + 12);
    if (l < endLen) return false;
    body.assign(f + 16, f + 16 + l - endLen);
    if (looksLikeRetcode(body)) body.erase(body.begin(), body.begin() + 4);
    if (ver == V33) {
      stripVersion(body);
      if (!body.empty() && body[0] != '{') {
        if (body.size() % 16) return false;
        body = ecb(key, body, false);
        unpad(body);
      }
    } else {
      if (body.size() % 16) return false;
      body = ecb(key, body, false);
      unpad(body);
      stripVersion(body);
    }
  } else {
    *cmd = get32(f + 10);
    size_t l = get32(f + 14);
    if (l < 28) return false;
    const uint8_t* iv = f + 18;
    size_t ctLen = l - 12 - 16;
    body.resize(ctLen);
    uint8_t tag[16];
    memcpy(tag, f + 18 + 12 + ctLen, 16);
    if (!gcm(key, iv, f + 4, 14, f + 30, ctLen, body.data(), tag, false)) return false;
    if (looksLikeRetcode(body)) body.erase(body.begin(), body.begin() + 4);
    stripVersion(body);
  }
  text->assign(body.begin(), body.end());
  return true;
}

Bytes Session::negStart(const uint8_t* fixedNonce) {
  if (fixedNonce) memcpy(localNonce, fixedNonce, 16); else randomBytes(localNonce, 16);
  memcpy(key, real, 16);
  return encode(SESS_KEY_NEG_START, localNonce, 16);
}

bool Session::negStep3(const uint8_t* f, size_t len, Bytes* out) {
  int cmd;
  std::string t;
  if (!decode(f, len, &cmd, &t) || cmd != SESS_KEY_NEG_RESP || t.size() < 48) return false;
  const uint8_t* p = (const uint8_t*)t.data();
  uint8_t expect[32];
  hmac256(real, 16, localNonce, 16, expect);
  if (memcmp(expect, p + 16, 32) != 0) return false;   // невірний local key
  memcpy(remoteNonce, p, 16);
  uint8_t h[32];
  hmac256(real, 16, remoteNonce, 16, h);
  *out = encode(SESS_KEY_NEG_FINISH, h, 32);
  return true;
}

void Session::negFinalize() {
  uint8_t x[16];
  for (int i = 0; i < 16; i++) x[i] = localNonce[i] ^ remoteNonce[i];
  if (ver == V34) {
    aesEcb(real, x, key, 16, true);
  } else if (ver == V35) {
    uint8_t tag[16];
    gcm(real, localNonce, nullptr, 0, x, 16, key, tag, true);
  }
}

static std::string num(uint32_t v) { char b[16]; snprintf(b, sizeof b, "%u", (unsigned)v); return b; }

std::string controlJson(Ver v, const std::string& id, int dp, bool on, uint32_t t) {
  std::string dps = "{\"" + num(dp) + "\":" + (on ? "true" : "false") + "}";
  if (v == V33) return "{\"devId\":\"" + id + "\",\"uid\":\"" + id + "\",\"t\":\"" + num(t) + "\",\"dps\":" + dps + "}";
  return "{\"protocol\":5,\"t\":" + num(t) + ",\"data\":{\"dps\":" + dps + "}}";
}

std::string queryJson(Ver v, const std::string& id, uint32_t t) {
  if (v == V33) return "{\"gwId\":\"" + id + "\",\"devId\":\"" + id + "\",\"uid\":\"" + id + "\",\"t\":\"" + num(t) + "\"}";
  return "{}";
}

int controlCmd(Ver v) { return v == V33 ? CONTROL : CONTROL_NEW; }
int queryCmd(Ver v) { return v == V33 ? DP_QUERY : DP_QUERY_NEW; }

int findBoolDp(const std::string& j, int dp) {
  std::string k = "\"" + num(dp) + "\":";
  size_t p = j.find(k);
  if (p == std::string::npos) return -1;
  p += k.size();
  while (p < j.size() && j[p] == ' ') p++;
  if (j.compare(p, 4, "true") == 0) return 1;
  if (j.compare(p, 5, "false") == 0) return 0;
  return -1;
}

bool decodeUdp(const uint8_t* d, size_t n, std::string* json) {
  static uint8_t udpKey[16];
  static bool ready = false;
  if (!ready) { md5((const uint8_t*)"yGAdlopoPVldABfn", 16, udpKey); ready = true; }
  if (n < 20) return false;
  int total = frameLength(d, n);
  if (total < 0 || (size_t)total > n) return false;
  if (get32(d) == PREFIX_6699) {
    Session s(V35, "");
    memcpy(s.key, udpKey, 16);
    int cmd;
    return s.decode(d, n, &cmd, json);
  }
  // 55AA: тіло між заголовком і CRC; відкритий JSON (6666) або AES-ECB ключем UDP (6667)
  size_t l = get32(d + 12);
  if (l < 8) return false;
  Bytes body(d + 16, d + 16 + l - 8);
  if (looksLikeRetcode(body)) body.erase(body.begin(), body.begin() + 4);
  if (!body.empty() && body[0] != '{') {
    if (body.size() % 16) return false;
    body = ecb(udpKey, body, false);
    unpad(body);
  }
  json->assign(body.begin(), body.end());
  return !json->empty() && (*json)[0] == '{';
}

// ---------- клієнт ----------

/** Читає один кадр (з урахуванням довжини з заголовка). */
static bool readFrame(Transport& t, Bytes& buf, Bytes& frame, uint32_t timeoutMs) {
  for (;;) {
    int need = buf.empty() ? -1 : frameLength(buf.data(), buf.size());
    if (need == -2) {  // сміття — шукаємо наступний префікс
      buf.erase(buf.begin());
      continue;
    }
    if (need > 0 && need > 4096) return false;
    if (need > 0 && buf.size() >= (size_t)need) {
      frame.assign(buf.begin(), buf.begin() + need);
      buf.erase(buf.begin(), buf.begin() + need);
      return true;
    }
    uint8_t tmp[512];
    int n = t.read(tmp, sizeof tmp, timeoutMs);
    if (n <= 0) return false;
    buf.insert(buf.end(), tmp, tmp + n);
  }
}

int exchange(Transport& t, Ver v, const char* localKey, const std::string& devId, int dp, int setTo,
             uint32_t unixTime, std::string* err, uint32_t timeoutMs) {
  Session s(v, localKey);
  Bytes buf, f;
  int cmd;
  std::string text;
  if (v != V33) {
    Bytes st = s.negStart();
    if (!t.write(st.data(), st.size())) { *err = "no connection"; return -1; }
    bool got = false;
    while (readFrame(t, buf, f, timeoutMs)) {
      Bytes out;
      if (s.negStep3(f.data(), f.size(), &out)) {
        if (!t.write(out.data(), out.size())) { *err = "write"; return -1; }
        got = true;
        break;
      }
      int c2; std::string t2;
      if (s.decode(f.data(), f.size(), &c2, &t2) && c2 == SESS_KEY_NEG_RESP) { *err = "wrong local key"; return -1; }
    }
    if (!got) { *err = "key negotiation failed (wrong key or version?)"; return -1; }
    s.negFinalize();
  }
  bool ctrlAcked = false;
  for (int attempt = 0; attempt < 2; attempt++) {
    bool query = setTo < 0 || attempt == 1;
    Bytes req = query ? s.encode(queryCmd(v), queryJson(v, devId, unixTime))
                      : s.encode(controlCmd(v), controlJson(v, devId, dp, setTo == 1, unixTime));
    if (!t.write(req.data(), req.size())) { *err = "write"; return -1; }
    bool any = false;
    while (readFrame(t, buf, f, any ? 800 : timeoutMs)) {
      if (!s.decode(f.data(), f.size(), &cmd, &text)) { *err = "decrypt failed (wrong key?)"; return -1; }
      if (cmd == HEART_BEAT) continue;
      any = true;
      int st = findBoolDp(text, dp);
      if (st >= 0) return st;
      if (!query && (text.find("unvalid") != std::string::npos || text.find("rror") != std::string::npos)) {
        *err = text; return -1;
      }
    }
    if (!query) { ctrlAcked = any; continue; }  // стан не прийшов — окремо запитаємо
    if (ctrlAcked || (setTo >= 0 && any)) return setTo;
    *err = any ? "no dp " + num(dp) + " in reply" : "no reply";
    return -1;
  }
  *err = "no reply";
  return -1;
}

}  // namespace tuya

#endif
