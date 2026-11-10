#if !defined(ESP8266)

#include "tuya.h"

#include <string.h>

#if defined(IOT_HOST_TEST)
// ---------- комп'ютер (тести): OpenSSL ----------
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>

namespace tuya {

void aesEcb(const uint8_t key[16], const uint8_t* in, uint8_t* out, size_t len, bool encrypt) {
  EVP_CIPHER_CTX* c = EVP_CIPHER_CTX_new();
  EVP_CipherInit_ex(c, EVP_aes_128_ecb(), nullptr, key, nullptr, encrypt ? 1 : 0);
  EVP_CIPHER_CTX_set_padding(c, 0);
  int n = 0;
  EVP_CipherUpdate(c, out, &n, in, (int)len);
  EVP_CIPHER_CTX_free(c);
}

bool gcm(const uint8_t key[16], const uint8_t iv[12], const uint8_t* aad, size_t aadLen,
         const uint8_t* in, size_t len, uint8_t* out, uint8_t tag[16], bool encrypt) {
  EVP_CIPHER_CTX* c = EVP_CIPHER_CTX_new();
  int n = 0;
  EVP_CipherInit_ex(c, EVP_aes_128_gcm(), nullptr, nullptr, nullptr, encrypt ? 1 : 0);
  EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_IVLEN, 12, nullptr);
  EVP_CipherInit_ex(c, nullptr, nullptr, key, iv, encrypt ? 1 : 0);
  if (aadLen) EVP_CipherUpdate(c, nullptr, &n, aad, (int)aadLen);
  EVP_CipherUpdate(c, out, &n, in, (int)len);
  bool ok = true;
  if (encrypt) {
    EVP_CipherFinal_ex(c, out + n, &n);
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_GET_TAG, 16, tag);
  } else {
    EVP_CIPHER_CTX_ctrl(c, EVP_CTRL_GCM_SET_TAG, 16, tag);
    ok = EVP_CipherFinal_ex(c, out + n, &n) > 0;
  }
  EVP_CIPHER_CTX_free(c);
  return ok;
}

void hmac256(const uint8_t* key, size_t keyLen, const uint8_t* data, size_t len, uint8_t out[32]) {
  unsigned int n = 32;
  HMAC(EVP_sha256(), key, (int)keyLen, data, len, out, &n);
}

void md5(const uint8_t* data, size_t len, uint8_t out[16]) {
  unsigned int n = 16;
  EVP_Digest(data, len, out, &n, EVP_md5(), nullptr);
}

void randomBytes(uint8_t* out, size_t n) { RAND_bytes(out, (int)n); }

}  // namespace tuya

#else
// ---------- ESP32: mbedtls (апаратний AES) ----------
#include <esp_random.h>
#include <mbedtls/aes.h>
#include <mbedtls/gcm.h>
#include <mbedtls/md.h>
#include <mbedtls/md5.h>

namespace tuya {

void aesEcb(const uint8_t key[16], const uint8_t* in, uint8_t* out, size_t len, bool encrypt) {
  mbedtls_aes_context c;
  mbedtls_aes_init(&c);
  if (encrypt) mbedtls_aes_setkey_enc(&c, key, 128); else mbedtls_aes_setkey_dec(&c, key, 128);
  for (size_t i = 0; i + 16 <= len; i += 16)
    mbedtls_aes_crypt_ecb(&c, encrypt ? MBEDTLS_AES_ENCRYPT : MBEDTLS_AES_DECRYPT, in + i, out + i);
  mbedtls_aes_free(&c);
}

bool gcm(const uint8_t key[16], const uint8_t iv[12], const uint8_t* aad, size_t aadLen,
         const uint8_t* in, size_t len, uint8_t* out, uint8_t tag[16], bool encrypt) {
  mbedtls_gcm_context c;
  mbedtls_gcm_init(&c);
  mbedtls_gcm_setkey(&c, MBEDTLS_CIPHER_ID_AES, key, 128);
  int r;
  if (encrypt) r = mbedtls_gcm_crypt_and_tag(&c, MBEDTLS_GCM_ENCRYPT, len, iv, 12, aad, aadLen, in, out, 16, tag);
  else r = mbedtls_gcm_auth_decrypt(&c, len, iv, 12, aad, aadLen, tag, 16, in, out);
  mbedtls_gcm_free(&c);
  return r == 0;
}

void hmac256(const uint8_t* key, size_t keyLen, const uint8_t* data, size_t len, uint8_t out[32]) {
  mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), key, keyLen, data, len, out);
}

void md5(const uint8_t* data, size_t len, uint8_t out[16]) { mbedtls_md5(data, len, out); }

void randomBytes(uint8_t* out, size_t n) { esp_fill_random(out, n); }

}  // namespace tuya
#endif

#endif
