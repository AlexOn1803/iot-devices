// Перевірка протоколу Tuya з прошивки проти векторів tinytuya (tools/gen_vectors.py).
// Збірка: g++ -DIOT_HOST_TEST -I firmware/esp/src tools/tuya_test/test.cpp firmware/esp/src/tuya*.cpp -lcrypto
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include "tuya.h"
using namespace tuya;

static std::map<std::string, std::string> V;
static int fails = 0;
static Bytes unhex(const std::string& s) { Bytes b; for (size_t i = 0; i + 1 < s.size(); i += 2) b.push_back(std::stoi(s.substr(i, 2), nullptr, 16)); return b; }
static std::string hex(const Bytes& b) { std::string s; char t[3]; for (auto c : b) { snprintf(t, 3, "%02x", c); s += t; } return s; }
static void check(const char* name, bool ok) { printf("%s %s\n", ok ? "OK  " : "FAIL", name); if (!ok) fails++; }
static void loadVectors(const char* path) {  // простий розбір плаского JSON {"k": "v"|null}
  std::ifstream f(path); std::stringstream ss; ss << f.rdbuf(); std::string j = ss.str();
  size_t p = 0;
  while ((p = j.find('"', p)) != std::string::npos) {
    size_t e = j.find('"', p + 1); std::string k = j.substr(p + 1, e - p - 1);
    size_t c = j.find_first_not_of(" :", e + 1);
    if (j[c] == '"') { size_t e2 = j.find('"', c + 1); V[k] = j.substr(c + 1, e2 - c - 1); p = e2 + 1; }
    else { p = c + 1; }
  }
}

int main(int argc, char** argv) {
  loadVectors(argc > 1 ? argv[1] : "vectors.json");
  const char* KEY = "0123456789abcdef";
  std::string ctrl = "{\"devId\":\"bf0000000000000000abcd\",\"dps\":{\"1\":true}}";
  Bytes iv = unhex(V["iv"]);
  {
    Session s(V33, KEY); s.seq = 1;
    check("encode 3.3 CONTROL", hex(s.encode(CONTROL, ctrl)) == V["enc3.3"]);
    s.seq = 5; check("encode 3.3 DP_QUERY", hex(s.encode(DP_QUERY, "{}")) == V["query3.3"]);
  }
  {
    Session s(V34, KEY); s.seq = 1;
    check("encode 3.4 CONTROL_NEW", hex(s.encode(CONTROL_NEW, ctrl)) == V["enc3.4"]);
    s.seq = 5; check("encode 3.4 DP_QUERY_NEW", hex(s.encode(DP_QUERY_NEW, "{}")) == V["query3.4"]);
  }
  {
    Session s(V35, KEY); s.seq = 1;
    check("encode 3.5 CONTROL_NEW", hex(s.encode(CONTROL_NEW, (const uint8_t*)ctrl.data(), ctrl.size(), iv.data())) == V["enc3.5"]);
  }
  struct { Ver v; const char* k; const char* expect; } dev[] = {
    {V33, "dev3.3", "{\"dps\":{\"1\":true,\"19\":1234,\"20\":2301,\"18\":567}}"},
    {V34, "dev3.4", "{\"protocol\":4,\"t\":1,\"data\":{\"dps\":{\"1\":false,\"19\":55}}}"},
    {V35, "dev3.5", "{\"protocol\":4,\"data\":{\"dps\":{\"20\":2290}}}"},
  };
  for (auto& d : dev) {
    Session s(d.v, KEY); Bytes f = unhex(V[d.k]); int cmd = 0; std::string t;
    bool ok = s.decode(f.data(), f.size(), &cmd, &t);
    check((std::string("decode ") + d.k).c_str(), ok && cmd == STATUS && t == d.expect);
    if (!ok || t != d.expect) printf("   got: %s\n", t.c_str());
    check((std::string("frameLength ") + d.k).c_str(), frameLength(f.data(), f.size()) == (int)f.size());
  }
  {
    Session s(V33, KEY); Bytes f = unhex(V["dev3.3"]); int cmd; std::string t;
    s.decode(f.data(), f.size(), &cmd, &t);
    check("findBoolDp", findBoolDp(t, 1) == 1 && findBoolDp(t, 19) == -1 && findBoolDp(t, 7) == -1);
  }
  // узгодження ключа
  const uint8_t* LN = (const uint8_t*)"LOCALNONCE123456";
  for (Ver v : {V34, V35}) {
    std::string tag = v == V34 ? "3.4" : "3.5";
    Session s(v, KEY); s.seq = 1;
    Bytes start = s.negStart(LN);
    if (v == V34) check("neg 3.4 start", hex(start) == V["neg3.4_start"]);
    Bytes resp = unhex(V["neg" + tag + "_resp"]), out;
    bool ok = s.negStep3(resp.data(), resp.size(), &out);
    check(("neg " + tag + " step3").c_str(), ok);
    if (v == V34) check("neg 3.4 step3 frame", hex(out) == V["neg3.4_step3"]);
    Session chk(v, KEY); int cmd; std::string t;
    chk.decode(out.data(), out.size(), &cmd, &t);
    check(("neg " + tag + " step3 payload").c_str(), cmd == SESS_KEY_NEG_FINISH && hex(Bytes(t.begin(), t.end())) == V["neg" + tag + "_step3payload"]);
    s.negFinalize();
    check(("neg " + tag + " session key").c_str(), hex(Bytes(s.key, s.key + 16)) == V["neg" + tag + "_session"]);
    Session bad(v, "fedcba9876543210"); bad.negStart(LN); Bytes o2;
    check(("neg " + tag + " wrong key rejected").c_str(), !bad.negStep3(resp.data(), resp.size(), &o2));
  }
  for (const char* k : {"udp", "udp35"}) {
    Bytes f = unhex(V[k]); std::string j;
    bool ok = decodeUdp(f.data(), f.size(), &j);
    check((std::string("udp ") + k).c_str(), ok && j.find("\"gwId\"") != std::string::npos);
    printf("   %s\n", j.c_str());
  }
  printf(fails ? "\n%d FAILED\n" : "\nALL OK\n", fails);
  return fails ? 1 : 0;
}
