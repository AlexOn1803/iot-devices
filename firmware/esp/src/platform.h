#pragma once
// Відмінності між ESP8266 і сімейством ESP32 (класична, S2, S3, C3) в одному місці.

#include <Arduino.h>
#include <ArduinoJson.h>

#if defined(ESP8266)
  #include <ESP8266WiFi.h>
  #include <ESP8266WebServer.h>
  #include <ESP8266mDNS.h>
  #include <LittleFS.h>
  #include <Updater.h>
  typedef ESP8266WebServer WebServerT;
  #define CHIP_NAME "ESP8266"
  #define BOOT_PIN 0
  #define ADC_MAX 1023
  #define WIFI_OPEN_AUTH ENC_TYPE_NONE
  #define PWM_CHANNELS 8
#else
  #include <WiFi.h>
  #include <WebServer.h>
  #include <ESPmDNS.h>
  #include <Preferences.h>
  #include <Update.h>
  #include "soc/soc_caps.h"
  #include "driver/gpio.h"
  typedef WebServer WebServerT;
  #define CHIP_NAME ESP.getChipModel()
  #if CONFIG_IDF_TARGET_ESP32C3
    #define BOOT_PIN 9
  #else
    #define BOOT_PIN 0
  #endif
  #define ADC_MAX 4095
  #define WIFI_OPEN_AUTH WIFI_AUTH_OPEN
  #ifdef SOC_LEDC_CHANNEL_NUM
    #define PWM_CHANNELS SOC_LEDC_CHANNEL_NUM
  #else
    #define PWM_CHANNELS 8
  #endif
#endif

/** Піни, які можна використовувати під компоненти (без флешу та USB). */
inline bool validPin(int p) {
#if defined(ESP8266)
  return (p >= 0 && p <= 5) || (p >= 12 && p <= 16) || p == 17;  // 17 — A0
#elif CONFIG_IDF_TARGET_ESP32C3
  return (p >= 0 && p <= 10) || p == 20 || p == 21;
#elif CONFIG_IDF_TARGET_ESP32S2
  return GPIO_IS_VALID_GPIO(p) && !(p >= 26 && p <= 32) && p != 19 && p != 20;
#elif CONFIG_IDF_TARGET_ESP32S3
  return GPIO_IS_VALID_GPIO(p) && !(p >= 26 && p <= 32) && p != 19 && p != 20;
#else  // класична ESP32
  return GPIO_IS_VALID_GPIO(p) && !(p >= 6 && p <= 11);
#endif
}

inline int pinForAnalog(int p) {
#if defined(ESP8266)
  (void)p; return A0;
#else
  return p;
#endif
}

inline void setHostName(const String& h) {
#if defined(ESP8266)
  WiFi.hostname(h);
#else
  WiFi.setHostname(h.c_str());
#endif
}

inline bool beginUpdate() {
#if defined(ESP8266)
  uint32_t space = (ESP.getFreeSketchSpace() - 0x1000) & 0xFFFFF000;
  return Update.begin(space);
#else
  return Update.begin(UPDATE_SIZE_UNKNOWN);
#endif
}

// ---------- PWM ----------
inline int pwmAttach(int pin, int& nextCh) {
#if defined(ESP8266)
  analogWriteRange(255);
  analogWriteFreq(1000);
  pinMode(pin, OUTPUT);
  return nextCh++;
#else
  if (nextCh >= PWM_CHANNELS) return -1;
  int ch = nextCh++;
  ledcSetup(ch, 5000, 8);
  ledcAttachPin(pin, ch);
  return ch;
#endif
}

inline void pwmWrite(int pin, int ch, int duty) {
#if defined(ESP8266)
  (void)ch; analogWrite(pin, duty);
#else
  (void)pin; ledcWrite(ch, duty);
#endif
}

inline void pwmDetach(int pin) {
#if defined(ESP8266)
  analogWrite(pin, 0);
#else
  ledcDetachPin(pin);
#endif
}

// ---------- сховище налаштувань ----------
#if defined(ESP8266)
/** Простий аналог Preferences поверх файлу JSON у LittleFS. */
class KV {
  JsonDocument doc;
  bool ok = false;
  void flush() {
    File f = LittleFS.open("/prefs.json", "w");
    if (f) { serializeJson(doc, f); f.close(); }
  }
 public:
  void begin() {
    ok = LittleFS.begin();
    if (!ok) { LittleFS.format(); ok = LittleFS.begin(); }
    File f = LittleFS.open("/prefs.json", "r");
    if (f) { if (deserializeJson(doc, f)) doc.clear(); f.close(); }
  }
  String getString(const char* k, const String& def = "") { return doc[k].is<const char*>() ? String((const char*)doc[k]) : def; }
  void putString(const char* k, const String& v) { doc[k] = v; flush(); }
  uint32_t getUInt(const char* k, uint32_t def = 0) { return doc[k].is<uint32_t>() ? (uint32_t)doc[k] : def; }
  void putUInt(const char* k, uint32_t v) { doc[k] = v; flush(); }
  void clear() { doc.clear(); flush(); }
};
#else
class KV {
  Preferences p;
 public:
  void begin() { p.begin("iot", false); }
  String getString(const char* k, const String& def = "") { return p.getString(k, def); }
  void putString(const char* k, const String& v) { p.putString(k, v); }
  uint32_t getUInt(const char* k, uint32_t def = 0) { return p.getUInt(k, def); }
  void putUInt(const char* k, uint32_t v) { p.putUInt(k, v); }
  void clear() { p.clear(); }
};
#endif
