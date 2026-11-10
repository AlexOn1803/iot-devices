#pragma once
// Локальний протокол Tuya (3.3 / 3.4 / 3.5) — щоб плата сама керувала розеткою Tuya по Wi-Fi.
// Без залежностей від Arduino: той самий код перевіряється на комп'ютері (tools/tuya_test).

#include <stddef.h>
#include <stdint.h>
#include <string>
#include <vector>

namespace tuya {

typedef std::vector<uint8_t> Bytes;

// ---- криптографія (реалізація — tuya_crypto.cpp: mbedtls на ESP32, OpenSSL на комп'ютері) ----
void aesEcb(const uint8_t key[16], const uint8_t* in, uint8_t* out, size_t len, bool encrypt);
/** AES-GCM. При розшифруванні перевіряє тег; false — дані підроблені або невірний ключ. */
bool gcm(const uint8_t key[16], const uint8_t iv[12], const uint8_t* aad, size_t aadLen,
         const uint8_t* in, size_t len, uint8_t* out, uint8_t tag[16], bool encrypt);
void hmac256(const uint8_t* key, size_t keyLen, const uint8_t* data, size_t len, uint8_t out[32]);
void md5(const uint8_t* data, size_t len, uint8_t out[16]);
void randomBytes(uint8_t* out, size_t n);

uint32_t crc32(const uint8_t* d, size_t n);

enum Ver { V33 = 33, V34 = 34, V35 = 35 };
Ver parseVer(const char* s);

enum Cmd {
  SESS_KEY_NEG_START = 0x03, SESS_KEY_NEG_RESP = 0x04, SESS_KEY_NEG_FINISH = 0x05,
  CONTROL = 0x07, STATUS = 0x08, HEART_BEAT = 0x09, DP_QUERY = 0x0a,
  CONTROL_NEW = 0x0d, DP_QUERY_NEW = 0x10, UPDATEDPS = 0x12,
};

/** Повна довжина кадру за першими байтами або -1, якщо байтів поки замало / префікс невідомий (-2). */
int frameLength(const uint8_t* head, size_t avail);

/** Стан шифрування одного з'єднання. */
struct Session {
  Ver ver;
  uint8_t real[16];
  uint8_t key[16];
  uint32_t seq = 1;
  uint8_t localNonce[16];
  uint8_t remoteNonce[16];

  Session(Ver v, const char* localKey);

  /** Кодує команду в кадр. [fixedIv] — лише для тестів 3.5. */
  Bytes encode(int cmd, const uint8_t* payload, size_t len, const uint8_t* fixedIv = nullptr);
  Bytes encode(int cmd, const std::string& s) { return encode(cmd, (const uint8_t*)s.data(), s.size()); }

  /** Розбирає вхідний кадр: команда і розшифрований текст (без «3.x»-заголовка). */
  bool decode(const uint8_t* frame, size_t len, int* cmd, std::string* text);

  // узгодження сесійного ключа (3.4 / 3.5): start → (відповідь пристрою) → step3 → finalize
  Bytes negStart(const uint8_t* fixedNonce = nullptr);
  bool negStep3(const uint8_t* frame, size_t len, Bytes* out);
  void negFinalize();
};

/** JSON команд для перемикання DP [dp] у стан [on]. */
std::string controlJson(Ver v, const std::string& devId, int dp, bool on, uint32_t t);
std::string queryJson(Ver v, const std::string& devId, uint32_t t);
int controlCmd(Ver v);
int queryCmd(Ver v);

/** Шукає в JSON-відповіді значення "<dp>": true/false. 1 — увімк., 0 — вимк., -1 — немає. */
int findBoolDp(const std::string& json, int dp);

/** Розшифровує UDP-оголошення пристрою (порти 6666/6667/7000) у JSON або повертає false. */
bool decodeUdp(const uint8_t* d, size_t n, std::string* json);

/** Канал до пристрою (TCP 6668). На платі — WiFiClient, у тестах — сокет. */
struct Transport {
  virtual ~Transport() {}
  virtual bool write(const uint8_t* d, size_t n) = 0;
  /** Читає до [n] байтів, чекаючи не довше [timeoutMs]. 0 — нічого не прийшло, -1 — з'єднання закрите. */
  virtual int read(uint8_t* d, size_t n, uint32_t timeoutMs) = 0;
};

/**
 * Одна коротка сесія: (узгодження ключа для 3.4/3.5) → команда → відповідь.
 * [setTo]: -1 — лише запит стану, 0/1 — вимкнути/увімкнути.
 * Повертає стан DP після операції (1/0) або -1 при помилці (текст у [err]).
 * Якщо пристрій підтвердив команду, але не повідомив стан, повертає [setTo].
 */
int exchange(Transport& t, Ver v, const char* localKey, const std::string& devId, int dp, int setTo,
             uint32_t unixTime, std::string* err, uint32_t timeoutMs = 2500);

}  // namespace tuya
