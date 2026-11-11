// IoT Devices — універсальна прошивка для ESP8266 / ESP32 / S2 / S3 / C3.
// Що підключено до яких пінів, задається JSON-конфігом з додатку (POST /api/config),
// тож перепрошивати плату під кожен пристрій не треба.

#include "platform.h"
#if !defined(ESP8266)
#include <esp_system.h>
#endif
#include <DNSServer.h>
#include <Wire.h>
#include <Adafruit_NeoPixel.h>
#include <DHT.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Adafruit_AHTX0.h>
#include <Adafruit_BME280.h>
#include <Adafruit_BMP280.h>
#include <vector>

#include "web.h"

#if !defined(ESP8266)
#include <WiFiUdp.h>
#include "tuya.h"
#define HAS_TUYA 1
// компоненти читає ще й фонова задача Tuya — перебудова списку і її доступ ідуть під цим замком
SemaphoreHandle_t compsLock = nullptr;
#define COMPS_LOCK() do { if (compsLock) xSemaphoreTake(compsLock, portMAX_DELAY); } while (0)
#define COMPS_UNLOCK() do { if (compsLock) xSemaphoreGive(compsLock); } while (0)
#else
#define HAS_TUYA 0
#define COMPS_LOCK() do {} while (0)
#define COMPS_UNLOCK() do {} while (0)
#endif

#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif
#ifndef FW_BOARD
#define FW_BOARD "unknown"
#endif

static const char* fwVersion() { return strlen(FW_VERSION) ? FW_VERSION : "dev"; }

static const uint32_t STA_TIMEOUT = 30000;

WebServerT server(80);
DNSServer dns;
KV prefs;

String deviceId;      // esp-xxxxxx
String deviceName;
String apiKey;        // встановлюється додатком при першому підключенні (claim)
String wifiSsid, wifiPass;
bool apMode = false;
uint32_t staStartedAt = 0;
uint32_t lastStaOk = 0;
bool restartPending = false;
uint32_t restartAt = 0;
String mqttStatus = "off";

// ---------------- світлодіод стану ----------------
// мигає 1 с / 1 с — підключається до Wi-Fi; світить постійно — роздає свою мережу; не світить — у мережі
int ledPin = -1;
bool ledInv = false, ledRgb = false, ledEn = true, ledActive = false, ledLevel = false;

void ledDefaults() {
  String b = FW_BOARD;
  ledRgb = false;
  if (b == "esp32c3") { ledPin = 8; ledInv = true; }          // C3 SuperMini, активний LOW
  else if (b == "esp32") { ledPin = 2; ledInv = false; }
  else if (b == "esp32s2") { ledPin = 15; ledInv = false; }   // Lolin S2 mini
  else if (b == "esp32s3") { ledPin = 48; ledInv = false; ledRgb = true; }  // RGB на DevKitC
  else if (b == "esp8266" || b == "esp01") { ledPin = 2; ledInv = true; }
  else ledPin = -1;
}
void ledLoad();
void ledWrite(bool on);
bool pinUsed(int p);
String mqttHost;

// ---------------- компоненти ----------------

/** Період розкладу термостата: з [min] хвилини доби у дні [days] (біт 0 — Пн … біт 6 — Нд). */
struct Period {
  uint8_t days = 0x7F;
  uint16_t min = 0;
  float temp = 20;
  bool off = false;   // у цей період не гріти (і не охолоджувати)
};

enum CompType { C_RELAY, C_BUTTON, C_PWM, C_WS2812, C_DHT, C_DS18B20, C_AHT, C_ANALOG, C_INPUT, C_THERMO, C_BME, C_SHT21, C_TUYA, C_UNKNOWN };

struct Comp {
  CompType type = C_UNKNOWN;
  String typeName;
  String name;
  int pin = -1, pin2 = -1;
  bool inv = false;
  // реле / світло
  bool on = false;
  String powerOn = "off";       // off | on | memory
  uint32_t countdownEnd = 0;    // millis(), 0 — немає
  int bri = 255;
  uint8_t r = 255, g = 255, b = 255;
  String fx = "none";           // none | rainbow | breathe
  int count = 0;
  int ledcCh = -1;
  Adafruit_NeoPixel* strip = nullptr;
  // кнопка
  int target = 0;
  bool lastLevel = true;
  uint32_t lastChange = 0;
  // датчики
  int model = 22;
  DHT* dht = nullptr;
  OneWire* ow = nullptr;
  DallasTemperature* ds = nullptr;
  Adafruit_AHTX0* aht = nullptr;
  Adafruit_BME280* bme = nullptr;
  Adafruit_BMP280* bmp = nullptr;
  float temp = NAN, hum = NAN, pres = NAN;
  int raw = 0, minV = 0, maxV = 4095;
  bool active = false;
  bool pull = true;
  uint32_t lastRead = 0;
  // термостат: датчик [sensor] керує реле [target]
  int sensor = -1;
  bool heat = true;          // true — обігрів, false — охолодження
  float onAt = 18, offAt = 19;
  bool enabled = true;
  uint32_t lastSwitch = 0;
  uint32_t sensorBadSince = 0;
  String thermoState = "idle"; // idle | on | off | nosensor | offline | schedoff
  std::vector<Period> sched;   // розклад; порожній — завжди onAt/offAt
  float effOn = 18, effOff = 19;   // пороги, що діють зараз (з розкладу чи ручні)
  int curPeriod = -1, curKey = -1, nextKey = -1;   // ключ = хвилина тижня від Пн 00:00
  int holdKey = -1;            // змінено вручну — діє до кінця періоду з цим ключем
  float holdOn = 0, holdOff = 0;
  bool schedOff = false;
  // розетка Tuya в тій самій мережі: плата керує нею напряму локальним протоколом
  String tId, tKey;
  char tIp[16] = "";
  int tVer = 33, tDp = 1;
  volatile int8_t tWant = -1;   // що треба надіслати: -1 нічого, 0/1
  bool tOnline = false, tKnown = false;
  char tErr[64] = "";
  uint32_t tLastQuery = 0, tNextTry = 0;
};

std::vector<Comp> comps;
int nextLedc = 0;

CompType parseType(const String& t) {
  if (t == "relay") return C_RELAY;
  if (t == "button") return C_BUTTON;
  if (t == "pwm") return C_PWM;
  if (t == "ws2812") return C_WS2812;
  if (t == "dht") return C_DHT;
  if (t == "ds18b20") return C_DS18B20;
  if (t == "aht") return C_AHT;
  if (t == "analog") return C_ANALOG;
  if (t == "input") return C_INPUT;
  if (t == "thermo") return C_THERMO;
  if (t == "bme") return C_BME;
  if (t == "sht21") return C_SHT21;
  if (t == "tuya") return C_TUYA;
  return C_UNKNOWN;
}


void saveRelayStates() {
  uint32_t mask = 0;
  for (size_t i = 0; i < comps.size() && i < 32; i++)
    if (comps[i].type == C_RELAY && comps[i].on) mask |= (1UL << i);
  prefs.putUInt("rs", mask);
}

void applyRelay(Comp& c) {
  if (validPin(c.pin)) digitalWrite(c.pin, (c.on != c.inv) ? HIGH : LOW);
}

void applyPwm(Comp& c) {
  if (c.ledcCh < 0) return;
  int duty = c.on ? c.bri : 0;
  if (c.inv) duty = 255 - duty;
  pwmWrite(c.pin, c.ledcCh, duty);
}

uint32_t wheel(uint8_t pos) {
  pos = 255 - pos;
  if (pos < 85) return Adafruit_NeoPixel::Color(255 - pos * 3, 0, pos * 3);
  if (pos < 170) { pos -= 85; return Adafruit_NeoPixel::Color(0, pos * 3, 255 - pos * 3); }
  pos -= 170;
  return Adafruit_NeoPixel::Color(pos * 3, 255 - pos * 3, 0);
}

void applyStrip(Comp& c, uint32_t now = 0) {
  if (!c.strip) return;
  if (!c.on) {
    c.strip->clear();
  } else if (c.fx == "rainbow") {
    uint8_t base = (now / 20) & 0xFF;
    for (int i = 0; i < c.count; i++) c.strip->setPixelColor(i, wheel(base + i * 256 / max(1, c.count)));
  } else {
    float k = 1.0f;
    if (c.fx == "breathe") k = 0.15f + 0.85f * (0.5f + 0.5f * sinf(now / 600.0f));
    uint32_t col = Adafruit_NeoPixel::Color(c.r * k, c.g * k, c.b * k);
    for (int i = 0; i < c.count; i++) c.strip->setPixelColor(i, col);
  }
  c.strip->setBrightness(c.bri);
  c.strip->show();
}

uint32_t compsGen = 0;   // змінюється при кожній перебудові — фонова задача так бачить застарілі результати

void freeComps() {
  for (auto& c : comps) {
    if (c.strip) { c.strip->clear(); c.strip->show(); delete c.strip; }
    delete c.dht; delete c.ds; delete c.ow; delete c.aht; delete c.bme; delete c.bmp;
    if (c.type == C_PWM && c.ledcCh >= 0 && validPin(c.pin)) pwmDetach(c.pin);
  }
  comps.clear();
  nextLedc = 0;
  compsGen++;
}

/** Створює компоненти з JSON-конфігу (поле "comps"). */
void buildCompsLocked(JsonArrayConst arr);
void buildComps(JsonArrayConst arr) {
  COMPS_LOCK();
  buildCompsLocked(arr);
  COMPS_UNLOCK();
}

void buildCompsLocked(JsonArrayConst arr) {
  freeComps();
  uint32_t relayMask = prefs.getUInt("rs", 0);
  bool wireStarted = false;
  // усі I2C-датчики сидять на одній шині; піни беруться з першого такого компонента
  auto startWire = [&](int sda, int scl) {
    if (!wireStarted && validPin(sda) && validPin(scl)) { Wire.begin(sda, scl); wireStarted = true; }
    return wireStarted;
  };
  for (JsonObjectConst o : arr) {
    Comp c;
    c.typeName = o["t"] | "";
    c.type = parseType(c.typeName);
    c.name = o["name"] | c.typeName;
    c.pin = o["pin"] | -1;
    c.pin2 = o["scl"] | -1;
    if (c.type == C_AHT || c.type == C_BME || c.type == C_SHT21) { c.pin = o["sda"] | -1; }
    c.inv = o["inv"] | false;
    size_t idx = comps.size();
    switch (c.type) {
      case C_RELAY:
        c.powerOn = o["po"] | "off";
        if (validPin(c.pin)) pinMode(c.pin, OUTPUT);
        c.on = c.powerOn == "on" || (c.powerOn == "memory" && idx < 32 && (relayMask & (1UL << idx)));
        applyRelay(c);
        break;
      case C_BUTTON:
        c.target = o["target"] | 0;
        c.pull = o["pull"] | true;
        if (validPin(c.pin)) pinMode(c.pin, c.pull ? INPUT_PULLUP : INPUT);
        c.lastLevel = validPin(c.pin) ? digitalRead(c.pin) : HIGH;
        break;
      case C_PWM:
        if (validPin(c.pin)) c.ledcCh = pwmAttach(c.pin, nextLedc);
        applyPwm(c);
        break;
      case C_WS2812:
        c.count = constrain((int)(o["count"] | 8), 1, 600);
        c.bri = 128;
        if (validPin(c.pin)) {
          c.strip = new Adafruit_NeoPixel(c.count, c.pin, NEO_GRB + NEO_KHZ800);
          c.strip->begin();
        }
        applyStrip(c);
        break;
      case C_DHT:
        c.model = o["model"] | 22;
        if (validPin(c.pin)) { c.dht = new DHT(c.pin, c.model == 11 ? DHT11 : DHT22); c.dht->begin(); }
        break;
      case C_DS18B20:
        if (validPin(c.pin)) {
          c.ow = new OneWire(c.pin);
          c.ds = new DallasTemperature(c.ow);
          c.ds->begin();
          c.ds->setWaitForConversion(false);
          c.ds->requestTemperatures();
        }
        break;
      case C_AHT:
        if (startWire(c.pin, c.pin2)) {
          c.aht = new Adafruit_AHTX0();
          if (!c.aht->begin(&Wire)) { delete c.aht; c.aht = nullptr; }
        }
        break;
      case C_SHT21:
        // SHT21 / HTU21D / Si7021 — однакові команди, адреса 0x40; без бібліотеки, щоб не роздувати прошивку
        if (startWire(c.pin, c.pin2)) {
          Wire.beginTransmission(0x40);
          c.active = Wire.endTransmission() == 0;
          c.ledcCh = 0;  // фаза вимірювання: 0 — чекаємо, 1 — міряємо температуру, 2 — вологість
        }
        break;
      case C_BME:
        // BME280 (з вологістю) і BMP280 (без) продають під однією назвою — пробуємо обидва
        if (startWire(c.pin, c.pin2)) {
          for (uint8_t addr : {0x76, 0x77}) {
            c.bme = new Adafruit_BME280();
            if (c.bme->begin(addr, &Wire)) break;
            delete c.bme; c.bme = nullptr;
            c.bmp = new Adafruit_BMP280(&Wire);
            if (c.bmp->begin(addr)) break;
            delete c.bmp; c.bmp = nullptr;
          }
        }
        break;
      case C_ANALOG:
        c.minV = o["min"] | 0;
        c.maxV = o["max"] | ADC_MAX;
        if (validPin(c.pin)) pinMode(c.pin, INPUT);
        break;
      case C_INPUT:
        c.pull = o["pull"] | true;
        if (validPin(c.pin)) pinMode(c.pin, c.pull ? INPUT_PULLUP : INPUT);
        break;
      case C_THERMO:
        c.sensor = o["sensor"] | -1;
        c.target = o["target"] | -1;
        c.heat = String((const char*)(o["mode"] | "heat")) != "cool";
        c.onAt = o["onAt"] | 18.0f;
        c.offAt = o["offAt"] | 19.0f;
        c.enabled = o["enabled"] | true;
        c.effOn = c.onAt; c.effOff = c.offAt;
        for (JsonObjectConst p : o["sched"].as<JsonArrayConst>()) {
          if (c.sched.size() >= 16) break;
          Period q;
          q.days = (p["d"] | 0x7F) & 0x7F;
          q.min = (p["t"] | 0) % 1440;
          q.temp = p["temp"] | 20.0f;
          q.off = p["x"] | false;
          if (q.days) c.sched.push_back(q);
        }
        break;
      case C_TUYA:
        c.tId = (const char*)(o["id"] | "");
        c.tKey = (const char*)(o["key"] | "");
        strlcpy(c.tIp, o["ip"] | "", sizeof(c.tIp));
        c.tVer = (int)(atof(o["ver"] | "3.3") * 10 + 0.5);
        c.tDp = o["dp"] | 1;
#if !HAS_TUYA
        strlcpy(c.tErr, "ESP8266 cannot control Tuya plugs", sizeof(c.tErr));
#endif
        break;
      default:
        break;
    }
    comps.push_back(c);
  }
}

String configJson;  // збережений конфіг як є
String tzPosix;     // часовий пояс у форматі POSIX (його передає додаток), напр. EET-2EEST,M3.5.0/3,M10.5.0/4

void applyTz() {
  setenv("TZ", tzPosix.isEmpty() ? "UTC0" : tzPosix.c_str(), 1);
  tzset();
}

/** Час з інтернету (NTP) і місцевий пояс — для розкладу. */
void startTime() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  applyTz();
}

bool timeValid() { return time(nullptr) > 1700000000; }

/** Поточна хвилина тижня (Пн 00:00 = 0) за місцевим часом або -1, якщо час ще невідомий. */
int weekMinute() {
  if (!timeValid()) return -1;
  time_t t = time(nullptr);
  struct tm lt;
  localtime_r(&t, &lt);
  return ((lt.tm_wday + 6) % 7) * 1440 + lt.tm_hour * 60 + lt.tm_min;
}

void loadConfig() {
  configJson = prefs.getString("cfg", "{\"comps\":[]}");
  JsonDocument doc;
  if (deserializeJson(doc, configJson)) { doc.clear(); doc["comps"].to<JsonArray>(); }
  deviceName = prefs.getString("name", deviceName);
  tzPosix = prefs.getString("tz", "");
  applyTz();
  buildComps(doc["comps"].as<JsonArrayConst>());
}

/** CRC-8 (поліном 0x31), яким SHT21/HTU21 захищає кожне вимірювання. */
static uint8_t shtCrc(const uint8_t* d, int n) {
  uint8_t crc = 0;
  for (int i = 0; i < n; i++) {
    crc ^= d[i];
    for (int b = 0; b < 8; b++) crc = (crc & 0x80) ? (crc << 1) ^ 0x31 : crc << 1;
  }
  return crc;
}

/** Читає 16-бітне значення після команди; -1 — помилка. */
static int shtRead() {
  uint8_t d[3];
  if (Wire.requestFrom((uint8_t)0x40, (uint8_t)3) != 3) return -1;
  for (int i = 0; i < 3; i++) d[i] = Wire.read();
  if (shtCrc(d, 2) != d[2]) return -1;
  return ((d[0] << 8) | d[1]) & 0xFFFC;
}

static void shtCommand(uint8_t cmd) {
  Wire.beginTransmission(0x40); Wire.write(cmd); Wire.endTransmission();
}

/**
 * Вимірювання без блокування циклу: команда «температура» (0xF3), через 90 мс читання,
 * команда «вологість» (0xF5), через 40 мс читання. Повтор раз на 2 с.
 */
void shtStep(Comp& c, uint32_t now) {
  int& phase = c.ledcCh;
  if (phase == 0) {
    if (now - c.lastRead < 2000) return;
    c.lastRead = now;
    shtCommand(0xF3);
    c.lastChange = now; phase = 1;
  } else if (phase == 1 && now - c.lastChange >= 90) {
    int raw = shtRead();
    if (raw < 0) { c.temp = NAN; c.hum = NAN; phase = 0; return; }
    c.temp = -46.85f + 175.72f * raw / 65536.0f;
    shtCommand(0xF5);
    c.lastChange = now; phase = 2;
  } else if (phase == 2 && now - c.lastChange >= 40) {
    int raw = shtRead();
    c.hum = raw < 0 ? NAN : constrain(-6.0f + 125.0f * raw / 65536.0f, 0.0f, 100.0f);
    phase = 0;
  }
}

void readSensors(uint32_t now) {
  for (auto& c : comps) {
    switch (c.type) {
      case C_DHT:
        if (c.dht && now - c.lastRead > 2500) {
          c.lastRead = now;
          float t = c.dht->readTemperature(), h = c.dht->readHumidity();
          c.temp = t; c.hum = h;
        }
        break;
      case C_DS18B20:
        if (c.ds && now - c.lastRead > 1000) {
          c.lastRead = now;
          float t = c.ds->getTempCByIndex(0);
          c.temp = (t == DEVICE_DISCONNECTED_C) ? NAN : t;
          c.ds->requestTemperatures();
        }
        break;
      case C_AHT:
        if (c.aht && now - c.lastRead > 2000) {
          c.lastRead = now;
          sensors_event_t h, t;
          if (c.aht->getEvent(&h, &t)) { c.temp = t.temperature; c.hum = h.relative_humidity; }
          else { c.temp = NAN; c.hum = NAN; }
        }
        break;
      case C_SHT21:
        shtStep(c, now);
        break;
      case C_BME:
        if (now - c.lastRead > 2000) {
          c.lastRead = now;
          if (c.bme) { c.temp = c.bme->readTemperature(); c.hum = c.bme->readHumidity(); c.pres = c.bme->readPressure() / 100.0f; }
          else if (c.bmp) { c.temp = c.bmp->readTemperature(); c.pres = c.bmp->readPressure() / 100.0f; }
          else { c.temp = NAN; c.hum = NAN; c.pres = NAN; }
        }
        break;
      case C_ANALOG:
        if (validPin(c.pin) && now - c.lastRead > 500) {
          c.lastRead = now;
          int v = 0;
          for (int k = 0; k < 8; k++) v += analogRead(pinForAnalog(c.pin));
          c.raw = v / 8;
        }
        break;
      case C_INPUT:
        if (validPin(c.pin)) {
          bool a = digitalRead(c.pin) == HIGH;
          if (c.pull) a = !a;   // з підтяжкою вгору активний рівень — LOW
          if (c.inv) a = !a;
          c.active = a;
        }
        break;
      default:
        break;
    }
  }
}

/** Розетці Tuya — лише замовлення; надішле фонова задача, стан оновиться після відповіді. */
void setTuya(Comp& c, bool on) {
  int8_t w = on ? 1 : 0;
  if (c.tWant == w) return;          // вже чекає надсилання — не збиваємо паузу між повторами
  c.tWant = w;
  c.tNextTry = 0;
}

void toggleTuya(Comp& c) {
  int8_t w = c.tWant;
  setTuya(c, w >= 0 ? !w : !c.on);
}

void setRelay(Comp& c, bool on) {
  if (c.type == C_TUYA) { setTuya(c, on); return; }
  c.on = on;
  c.countdownEnd = 0;
  applyRelay(c);
  if (c.powerOn == "memory") saveRelayStates();
}

/**
 * Термостат на самій платі: працює без телефона і без Wi-Fi.
 * Гістерезис між onAt і offAt, перемикання не частіше ніж раз на 30 с,
 * при втраті датчика на 60 с обігрів вимикається.
 */
/** Який період розкладу діє зараз: індекс, його ключ і ключ наступної зміни. */
static int activePeriod(const Comp& t, int nowKey, int* key, int* next) {
  int best = -1, bestDist = 1 << 30, nextDist = 1 << 30;
  *key = -1; *next = -1;
  for (size_t i = 0; i < t.sched.size(); i++) {
    for (int d = 0; d < 7; d++) {
      if (!(t.sched[i].days & (1 << d))) continue;
      int k = d * 1440 + t.sched[i].min;
      int back = (nowKey - k + 10080) % 10080;
      if (back < bestDist) { bestDist = back; best = i; *key = k; }
      int fwd = (k - nowKey + 10080) % 10080;
      if (fwd > 0 && fwd < nextDist) { nextDist = fwd; *next = k; }
    }
  }
  return best;
}

/** Пороги, що діють зараз: ручні (до кінця періоду), з розкладу або основні. */
static void thermoSetpoints(Comp& t) {
  t.effOn = t.onAt; t.effOff = t.offAt;
  t.schedOff = false;
  t.curPeriod = -1;
  int nowKey = t.sched.empty() ? -1 : weekMinute();
  if (nowKey < 0) { t.holdKey = -1; return; }
  int key, next;
  int idx = activePeriod(t, nowKey, &key, &next);
  t.nextKey = next;
  if (idx < 0) return;
  t.curPeriod = idx;
  t.curKey = key;
  if (t.holdKey >= 0 && t.holdKey != key) t.holdKey = -1;   // почався новий період — знову за розкладом
  if (t.holdKey == key) { t.effOn = t.holdOn; t.effOff = t.holdOff; return; }
  const Period& p = t.sched[idx];
  if (p.off) { t.schedOff = true; return; }
  float gap = fabsf(t.offAt - t.onAt);
  if (gap < 0.2f) gap = 0.5f;
  t.effOn = p.temp;
  t.effOff = t.heat ? p.temp + gap : p.temp - gap;
}

void handleThermostats(uint32_t now) {
  for (auto& t : comps) {
    if (t.type != C_THERMO) continue;
    if (t.sensor < 0 || t.sensor >= (int)comps.size() || t.target < 0 || t.target >= (int)comps.size()) continue;
    Comp& sensor = comps[t.sensor];
    Comp& relay = comps[t.target];
    if (relay.type != C_RELAY && relay.type != C_TUYA) continue;
    t.temp = sensor.temp;
    thermoSetpoints(t);
    if (!t.enabled) { t.thermoState = "idle"; t.sensorBadSince = 0; continue; }
    if (isnan(sensor.temp)) {
      if (!t.sensorBadSince) t.sensorBadSince = now ? now : 1;
      t.thermoState = "nosensor";
      if (t.heat && relay.on && now - t.sensorBadSince > 60000) setRelay(relay, false);
      continue;
    }
    t.sensorBadSince = 0;
    int want = -1;
    if (t.schedOff) want = 0;
    else if (t.heat) { if (sensor.temp <= t.effOn) want = 1; else if (sensor.temp >= t.effOff) want = 0; }
    else { if (sensor.temp >= t.effOn) want = 1; else if (sensor.temp <= t.effOff) want = 0; }
    if (want >= 0 && (bool)want != relay.on && (t.lastSwitch == 0 || now - t.lastSwitch > 30000)) {
      t.lastSwitch = now;
      setRelay(relay, want == 1);
    }
    t.thermoState = relay.on ? "on" : t.schedOff ? "schedoff" : "off";
    if (relay.type == C_TUYA && !relay.tOnline && relay.tLastQuery) t.thermoState = "offline";
  }
}

/** Зберігає змінені з додатку параметри термостата у конфіг на флеші. */
void persistThermo(int i) {
  JsonDocument doc;
  if (deserializeJson(doc, configJson)) return;
  JsonArray arr = doc["comps"];
  if (i < 0 || i >= (int)arr.size()) return;
  Comp& c = comps[i];
  JsonObject o = arr[i];
  o["enabled"] = c.enabled; o["onAt"] = c.onAt; o["offAt"] = c.offAt; o["mode"] = c.heat ? "heat" : "cool";
  String s; serializeJson(doc, s);
  configJson = s;
  prefs.putString("cfg", s);
}

void handleButtons(uint32_t now) {
  for (auto& c : comps) {
    if (c.type != C_BUTTON || !validPin(c.pin)) continue;
    bool level = digitalRead(c.pin);
    if (level != c.lastLevel && now - c.lastChange > 40) {
      c.lastChange = now;
      c.lastLevel = level;
      bool pressed = c.pull ? (level == LOW) : (level == HIGH);
      if (c.inv) pressed = !pressed;
      if (pressed && c.target >= 0 && c.target < (int)comps.size()) {
        Comp& t = comps[c.target];
        if (t.type == C_RELAY) setRelay(t, !t.on);
        else if (t.type == C_TUYA) toggleTuya(t);
        else if (t.type == C_PWM) { t.on = !t.on; applyPwm(t); }
        else if (t.type == C_WS2812) { t.on = !t.on; applyStrip(t, now); }
      }
    }
  }
}

// ---------------- JSON стану ----------------

void putNum(JsonObject o, const char* k, float v, int digits = 1) {
  if (isnan(v)) o[k] = nullptr;
  else { float m = powf(10, digits); o[k] = roundf(v * m) / m; }
}

String stateJson(bool diag = true) {
  JsonDocument doc;
  JsonArray arr = doc["comps"].to<JsonArray>();
  uint32_t now = millis();
  for (size_t i = 0; i < comps.size(); i++) {
    Comp& c = comps[i];
    JsonObject o = arr.add<JsonObject>();
    o["i"] = i;
    o["t"] = c.typeName;
    o["name"] = c.name;
    switch (c.type) {
      case C_RELAY:
        o["on"] = c.on;
        o["cd"] = c.countdownEnd ? (int)((c.countdownEnd - now) / 1000) : 0;
        break;
      case C_PWM:
        o["on"] = c.on; o["bri"] = c.bri;
        break;
      case C_WS2812: {
        o["on"] = c.on; o["bri"] = c.bri; o["fx"] = c.fx;
        JsonArray rgb = o["rgb"].to<JsonArray>();
        rgb.add(c.r); rgb.add(c.g); rgb.add(c.b);
        break;
      }
      case C_DHT: case C_AHT: case C_SHT21:
        putNum(o, "temp", c.temp); putNum(o, "hum", c.hum, 0);
        break;
      case C_DS18B20:
        putNum(o, "temp", c.temp);
        break;
      case C_BME:
        putNum(o, "temp", c.temp);
        if (c.bme) putNum(o, "hum", c.hum, 0);
        putNum(o, "pres", c.pres, 1);
        o["model"] = c.bme ? "BME280" : (c.bmp ? "BMP280" : "");
        break;
      case C_ANALOG: {
        o["raw"] = c.raw;
        float span = c.maxV - c.minV;
        float pct = span != 0 ? (c.raw - c.minV) * 100.0f / span : 0;
        if (c.inv) pct = 100 - pct;
        o["pct"] = (int)constrain(pct, 0.0f, 100.0f);
        break;
      }
      case C_INPUT:
        o["active"] = c.active;
        break;
      case C_BUTTON:
        o["target"] = c.target;
        break;
      case C_THERMO:
        o["enabled"] = c.enabled; o["mode"] = c.heat ? "heat" : "cool";
        putNum(o, "onAt", c.effOn); putNum(o, "offAt", c.effOff); putNum(o, "temp", c.temp);
        o["sensor"] = c.sensor; o["target"] = c.target; o["state"] = c.thermoState;
        if (!c.sched.empty()) {
          JsonObject sc = o["sched"].to<JsonObject>();
          sc["n"] = c.sched.size();
          sc["time"] = timeValid();
          sc["period"] = c.curPeriod;
          if (c.curPeriod >= 0) {
            sc["hold"] = c.holdKey >= 0;
            sc["off"] = c.schedOff;
            sc["next"] = c.nextKey;
          }
          putNum(sc, "baseOn", c.onAt); putNum(sc, "baseOff", c.offAt);
        }
        break;
      case C_TUYA:
        if (c.tKnown) o["on"] = c.on; else o["on"] = nullptr;
        o["online"] = c.tOnline;
        o["ip"] = c.tIp;
        if (c.tWant >= 0) o["pending"] = c.tWant == 1;
        if (c.tErr[0]) o["err"] = c.tErr;
        break;
      default:
        break;
    }
  }
  if (diag) {
    doc["uptime"] = now / 1000;
    doc["rssi"] = WiFi.RSSI();
  }
  String s;
  serializeJson(doc, s);
  return s;
}

String infoJson() {
  JsonDocument doc;
  doc["id"] = deviceId;
  doc["name"] = deviceName;
  doc["fw"] = fwVersion();
  doc["mac"] = WiFi.macAddress();
  doc["ip"] = WiFi.localIP().toString();
  doc["rssi"] = WiFi.RSSI();
  doc["ssid"] = wifiSsid;
  doc["claimed"] = apiKey.length() > 0;
  doc["ap"] = apMode;
  doc["chip"] = CHIP_NAME;
  doc["board"] = FW_BOARD;
  doc["mqtt"] = mqttStatus;
  doc["mqttHost"] = mqttHost;
  String s;
  serializeJson(doc, s);
  return s;
}

// ---------------- HTTP ----------------

void sendJson(int code, const String& body) {
  server.sendHeader("Cache-Control", "no-store");
  server.send(code, "application/json", body);
}

void sendError(int code, const char* msg) {
  JsonDocument d; d["error"] = msg;
  String s; serializeJson(d, s);
  sendJson(code, s);
}

bool authorized() {
  if (apiKey.isEmpty()) return true;
  return server.header("X-Key") == apiKey;
}

bool requireAuth() {
  if (authorized()) return true;
  sendError(401, "unauthorized");
  return false;
}

bool parseBody(JsonDocument& doc) {
  if (deserializeJson(doc, server.arg("plain"))) { sendError(400, "bad json"); return false; }
  return true;
}

void scheduleRestart(uint32_t ms = 500) { restartPending = true; restartAt = millis() + ms; }

/** Виконує команди компонентам (з HTTP або MQTT): один об'єкт або масив. */
void applyCommands(JsonVariant doc) {
  JsonArray list;
  JsonDocument wrap;
  if (doc.is<JsonArray>()) list = doc.as<JsonArray>();
  else { list = wrap.to<JsonArray>(); list.add(doc.as<JsonObject>()); }
  uint32_t now = millis();
  for (JsonObject o : list) {
    int i = o["i"] | -1;
    if (i < 0 || i >= (int)comps.size()) continue;
    Comp& c = comps[i];
    bool hasOn = o["on"].is<bool>();
    bool toggle = o["toggle"] | false;
    if (c.type == C_RELAY) {
      if (hasOn) setRelay(c, o["on"]);
      if (toggle) setRelay(c, !c.on);
      if (o["cd"].is<int>()) {
        int sec = o["cd"];
        c.countdownEnd = sec > 0 ? now + (uint32_t)sec * 1000 : 0;
      }
    } else if (c.type == C_PWM || c.type == C_WS2812) {
      if (hasOn) c.on = o["on"];
      if (toggle) c.on = !c.on;
      if (o["bri"].is<int>()) { c.bri = constrain((int)o["bri"], 0, 255); if (!hasOn) c.on = c.bri > 0; }
      if (o["rgb"].is<JsonArray>()) {
        JsonArray rgb = o["rgb"];
        c.r = rgb[0] | 0; c.g = rgb[1] | 0; c.b = rgb[2] | 0;
        if (!hasOn) c.on = true;
      }
      if (o["fx"].is<const char*>()) c.fx = (const char*)o["fx"];
      if (c.type == C_PWM) applyPwm(c); else applyStrip(c, now);
    } else if (c.type == C_TUYA) {
      if (hasOn) setTuya(c, o["on"]);
      if (toggle) toggleTuya(c);
    } else if (c.type == C_THERMO) {
      if (o["enabled"].is<bool>()) c.enabled = o["enabled"];
      if (c.curPeriod >= 0 && (o["onAt"].is<float>() || o["offAt"].is<float>())) {
        // діє розклад: зміна з додатку — лише до початку наступного періоду
        if (c.holdKey != c.curKey) {
          c.holdKey = c.curKey;
          bool wasOff = c.schedOff;
          c.holdOn = wasOff ? c.onAt : c.effOn;
          c.holdOff = wasOff ? c.offAt : c.effOff;
        }
        if (o["onAt"].is<float>()) c.holdOn = o["onAt"];
        if (o["offAt"].is<float>()) c.holdOff = o["offAt"];
        thermoSetpoints(c);
      } else {
        if (o["onAt"].is<float>()) c.onAt = o["onAt"];
        if (o["offAt"].is<float>()) c.offAt = o["offAt"];
        c.effOn = c.onAt; c.effOff = c.offAt;
      }
      if (o["resume"] | false) { c.holdKey = -1; thermoSetpoints(c); }
      if (o["mode"].is<const char*>()) c.heat = String((const char*)o["mode"]) != "cool";
      c.lastSwitch = 0;
      persistThermo(i);
    }
  }
}

void handleSet() {
  if (!requireAuth()) return;
  JsonDocument doc;
  if (!parseBody(doc)) return;
  applyCommands(doc.as<JsonVariant>());
  sendJson(200, stateJson());
}

void handleConfigPost() {
  if (!requireAuth()) return;
  JsonDocument doc;
  if (!parseBody(doc)) return;
  if (doc["led"].is<JsonObject>()) {
    // старий пін відпускаємо (гасимо), новий підхопить ledLoop
    if (ledActive && validPin(ledPin) && !pinUsed(ledPin)) ledWrite(false);
    String l; serializeJson(doc["led"], l);
    prefs.putString("led", l);
    ledLoad();
  }
  if (doc["name"].is<const char*>()) {
    deviceName = (const char*)doc["name"];
    prefs.putString("name", deviceName);
  }
  if (doc["tz"].is<const char*>()) {
    tzPosix = (const char*)doc["tz"];
    prefs.putString("tz", tzPosix);
    applyTz();
  }
  if (doc["comps"].is<JsonArray>()) {
    JsonDocument cfg;
    cfg["comps"] = doc["comps"];
    String s; serializeJson(cfg, s);
    configJson = s;
    prefs.putString("cfg", s);
    prefs.putUInt("rs", 0);
    buildComps(cfg["comps"].as<JsonArrayConst>());
  }
  MDNS.addServiceTxt("iotdev", "tcp", "name", deviceName.c_str());
  sendJson(200, stateJson());
}

void handleWifi() {
  // у режимі точки доступу налаштувати Wi-Fi можна без ключа (потрібен фізичний доступ до плати)
  if (!apMode && !requireAuth()) return;
  JsonDocument doc;
  if (!parseBody(doc)) return;
  String ssid = doc["ssid"] | "";
  if (ssid.isEmpty()) { sendError(400, "ssid required"); return; }
  prefs.putString("ssid", ssid);
  prefs.putString("pass", (const char*)(doc["pass"] | ""));
  sendJson(200, "{\"ok\":true,\"restart\":true}");
  scheduleRestart(1500);
}

void handleClaim() {
  if (!apiKey.isEmpty()) { sendError(409, "already claimed"); return; }
  JsonDocument doc;
  if (!parseBody(doc)) return;
  String key = doc["key"] | "";
  if (key.length() < 8) { sendError(400, "key too short"); return; }
  apiKey = key;
  prefs.putString("key", apiKey);
  if (doc["name"].is<const char*>()) { deviceName = (const char*)doc["name"]; prefs.putString("name", deviceName); }
  sendJson(200, infoJson());
}

void handleScan() {
  int n = WiFi.scanComplete();
  if (n == WIFI_SCAN_FAILED || n == -2) { WiFi.scanNetworks(true); sendJson(202, "{\"scanning\":true}"); return; }
  if (n == WIFI_SCAN_RUNNING) { sendJson(202, "{\"scanning\":true}"); return; }
  JsonDocument doc;
  JsonArray arr = doc["nets"].to<JsonArray>();
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (s.isEmpty()) continue;
    bool dup = false;
    for (JsonObject o : arr) if (s == (const char*)o["ssid"]) { dup = true; break; }
    if (dup) continue;
    JsonObject o = arr.add<JsonObject>();
    o["ssid"] = s; o["rssi"] = WiFi.RSSI(i); o["open"] = WiFi.encryptionType(i) == WIFI_OPEN_AUTH;
  }
  WiFi.scanDelete();
  WiFi.scanNetworks(true);
  String out; serializeJson(doc, out);
  sendJson(200, out);
}

// ---------------- MQTT (доступ через інтернет) ----------------
// Топіки: iotdev/<id>/state (retained стан), /status (online/offline, LWT), /info, /set (команди)

#include <PubSubClient.h>
#include <time.h>
#include "certs.h"
#if defined(ESP8266)
  #include <WiFiClientSecureBearSSL.h>
  BearSSL::WiFiClientSecure mqttTlsClient;
  BearSSL::X509List* mqttCa = nullptr;
#else
  #include <WiFiClientSecure.h>
  WiFiClientSecure mqttTlsClient;
#endif
WiFiClient mqttPlainClient;
PubSubClient mqtt;
String mqttUser, mqttPass;
int mqttPort = 8883;
bool mqttEnabled = false;
uint32_t mqttLastTry = 0, mqttRetryMs = 5000, mqttLastCheck = 0, mqttLastFull = 0;
String mqttLastState;
bool timeStarted = false;

String mqttTopic(const char* t) { return "iotdev/" + deviceId + "/" + t; }

void mqttCallback(char* topic, byte* payload, unsigned int len) {
  JsonDocument d;
  if (deserializeJson(d, payload, len)) return;
  // команди мають бути підписані ключем плати — інакше їх ігноруємо
  if (!apiKey.isEmpty() && String((const char*)(d["key"] | "")) != apiKey) return;
  if (!d["cmds"].isNull()) applyCommands(d["cmds"]);
  mqttLastState = "";  // одразу опублікувати новий стан
}

void mqttApplySettings() {
  if (mqtt.connected()) mqtt.disconnect();   // без клієнта disconnect() звертається до NULL
  mqttHost = prefs.getString("mq_host", "");
  mqttPort = prefs.getUInt("mq_port", 8883);
  mqttUser = prefs.getString("mq_user", "");
  mqttPass = prefs.getString("mq_pass", "");
  mqttEnabled = !mqttHost.isEmpty();
  mqttStatus = mqttEnabled ? "connecting" : "off";
  if (!mqttEnabled) return;
  bool useTls = mqttPort != 1883;
  if (useTls) {
    bool verify = mqttHost.endsWith(".hivemq.cloud");
#if defined(ESP8266)
    mqttTlsClient.setBufferSizes(1024, 1024);
    if (verify) { if (!mqttCa) mqttCa = new BearSSL::X509List(ISRG_ROOT_X1); mqttTlsClient.setTrustAnchors(mqttCa); }
    else mqttTlsClient.setInsecure();
#else
    if (verify) mqttTlsClient.setCACert(ISRG_ROOT_X1); else mqttTlsClient.setInsecure();
#endif
    mqtt.setClient(mqttTlsClient);
  } else {
    mqtt.setClient(mqttPlainClient);
  }
  mqtt.setServer(mqttHost.c_str(), mqttPort);
  mqtt.setCallback(mqttCallback);
  mqtt.setBufferSize(2048);
  mqtt.setKeepAlive(30);
  mqttLastTry = 0;
  mqttRetryMs = 5000;
}

void mqttLoop(uint32_t now) {
  if (!mqttEnabled) return;
  if (WiFi.status() != WL_CONNECTED) { mqttStatus = "no wifi"; return; }
  if (!timeStarted) { startTime(); timeStarted = true; }
  // для перевірки сертифіката потрібен правильний час
  if (mqttPort != 1883 && time(nullptr) < 1700000000) { mqttStatus = "waiting time"; return; }
  if (!mqtt.connected()) {
    if (mqttLastTry && now - mqttLastTry < mqttRetryMs) return;
    mqttLastTry = now;
    String cid = deviceId + "-" + String((uint32_t)random(0xFFFFFF), HEX);
    String st = mqttTopic("status");
    if (!mqtt.connect(cid.c_str(), mqttUser.c_str(), mqttPass.c_str(), st.c_str(), 1, true, "offline")) {
      mqttStatus = "error " + String(mqtt.state());
      mqttRetryMs = mqttRetryMs * 2 > 120000 ? 120000 : mqttRetryMs * 2;
      return;
    }
    mqttRetryMs = 5000;
    mqttStatus = "connected";
    mqtt.publish(st.c_str(), "online", true);
    mqtt.publish(mqttTopic("info").c_str(), infoJson().c_str(), true);
    mqtt.subscribe(mqttTopic("set").c_str(), 1);
    mqttLastState = "";
  }
  mqtt.loop();
  if (now - mqttLastCheck > 1000) {
    mqttLastCheck = now;
    String st = stateJson(false);
    if (st != mqttLastState || now - mqttLastFull > 60000) {
      mqttLastState = st;
      mqttLastFull = now;
      mqtt.publish(mqttTopic("state").c_str(), stateJson(true).c_str(), true);
    }
  }
}

void handleMqttPost() {
  if (!requireAuth()) return;
  JsonDocument doc;
  if (!parseBody(doc)) return;
  prefs.putString("mq_host", (const char*)(doc["host"] | ""));
  prefs.putUInt("mq_port", doc["port"] | 8883);
  prefs.putString("mq_user", (const char*)(doc["user"] | ""));
  prefs.putString("mq_pass", (const char*)(doc["pass"] | ""));
  mqttApplySettings();
  sendJson(200, infoJson());
}

bool otaAuthorized = false;

void setupServer() {
#if defined(ESP8266)
  server.collectHeaders("X-Key");
#else
  const char* headers[] = {"X-Key"};
  server.collectHeaders(headers, 1);
#endif

  server.on("/", HTTP_GET, []() {
    server.send_P(200, "text/html; charset=utf-8", apMode ? PORTAL_HTML : CONTROL_HTML);
  });
  server.on("/api/info", HTTP_GET, []() { sendJson(200, infoJson()); });
  server.on("/api/state", HTTP_GET, []() { if (requireAuth()) sendJson(200, stateJson()); });
  server.on("/api/set", HTTP_POST, handleSet);
  server.on("/api/config", HTTP_GET, []() {
    if (!requireAuth()) return;
    JsonDocument doc;
    deserializeJson(doc, configJson);
    doc["name"] = deviceName;
    JsonObject l = doc["led"].to<JsonObject>();
    l["en"] = ledEn; l["pin"] = ledPin; l["inv"] = ledInv; l["rgb"] = ledRgb;
    String s; serializeJson(doc, s);
    sendJson(200, s);
  });
  server.on("/api/config", HTTP_POST, handleConfigPost);
  server.on("/api/wifi", HTTP_POST, handleWifi);
  server.on("/api/scan", HTTP_GET, handleScan);
  server.on("/api/claim", HTTP_POST, handleClaim);
  server.on("/api/mqtt", HTTP_POST, handleMqttPost);
  server.on("/api/reboot", HTTP_POST, []() {
    if (!requireAuth()) return;
    sendJson(200, "{\"ok\":true}");
    scheduleRestart();
  });
  server.on("/update", HTTP_POST,
    []() {
      if (!otaAuthorized) { sendError(401, "unauthorized"); return; }
      bool ok = !Update.hasError();
      sendJson(ok ? 200 : 500, ok ? "{\"ok\":true}" : "{\"error\":\"update failed\"}");
      if (ok) scheduleRestart(800);
    },
    []() {
      HTTPUpload& up = server.upload();
      if (up.status == UPLOAD_FILE_START) {
        otaAuthorized = authorized();
        if (otaAuthorized) beginUpdate();
      } else if (up.status == UPLOAD_FILE_WRITE) {
        if (otaAuthorized) Update.write(up.buf, up.currentSize);
      } else if (up.status == UPLOAD_FILE_END) {
        if (otaAuthorized) Update.end(true);
      }
    });
  // для каптивного порталу: будь-яка адреса веде на сторінку налаштування
  server.onNotFound([]() {
    if (apMode) {
      server.sendHeader("Location", "http://192.168.4.1/", true);
      server.send(302, "text/plain", "");
    } else {
      sendError(404, "not found");
    }
  });
  server.begin();
}

// ---------------- Wi-Fi ----------------

void startAp() {
  if (apMode) return;
  apMode = true;
  String ssid = "IoT-Setup-" + deviceId.substring(4);
  WiFi.mode(wifiSsid.isEmpty() ? WIFI_AP : WIFI_AP_STA);
  WiFi.softAP(ssid.c_str());
  dns.start(53, "*", WiFi.softAPIP());
  WiFi.scanNetworks(true);
  Serial.printf("AP mode: %s\n", ssid.c_str());
}

void stopAp() {
  if (!apMode) return;
  apMode = false;
  dns.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
}

void startSta() {
  WiFi.mode(WIFI_STA);
  setHostName("iot-" + deviceId.substring(4));
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
  staStartedAt = millis();
}

bool mdnsStarted = false;

void startMdns() {
  if (mdnsStarted) return;
  String host = "iot-" + deviceId.substring(4);
  if (MDNS.begin(host.c_str())) {
    MDNS.addService("iotdev", "tcp", 80);
    MDNS.addServiceTxt("iotdev", "tcp", "id", deviceId.c_str());
    MDNS.addServiceTxt("iotdev", "tcp", "name", deviceName.c_str());
    MDNS.addServiceTxt("iotdev", "tcp", "fw", fwVersion());
    MDNS.addServiceTxt("iotdev", "tcp", "chip", String(CHIP_NAME).c_str());
    mdnsStarted = true;
  }
}

void factoryReset() {
  Serial.println("Factory reset");
  prefs.clear();
  delay(200);
  ESP.restart();
}

// ---------------- налаштування, записані разом із прошивкою ----------------
// Додаток пише окремий сектор: "IOTCFG1\n" + JSON + '\0'. Плата застосовує його на першому старті й стирає.
// ESP32: початок розділу spiffs (0x290000 у default.csv); ESP8266: сектор EEPROM (адресу перевіряє CI).
#if defined(ESP8266)
extern "C" uint32_t _EEPROM_start;
static uint32_t blobAddr() { return (uint32_t)&_EEPROM_start - 0x40200000; }
static bool blobRead(uint32_t* buf, size_t n) { return ESP.flashRead(blobAddr(), buf, n); }
static void blobErase() { ESP.flashEraseSector(blobAddr() / 4096); }
#else
#include "esp_partition.h"
static const esp_partition_t* blobPart() {
  return esp_partition_find_first(ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_DATA_SPIFFS, NULL);
}
static bool blobRead(uint32_t* buf, size_t n) { auto p = blobPart(); return p && esp_partition_read(p, 0, buf, n) == ESP_OK; }
static void blobErase() { auto p = blobPart(); if (p) esp_partition_erase_range(p, 0, 4096); }
#endif

static uint32_t blobBuf[1024];   // 4 КБ, вирівняно під читання флешу

void applyFlashedSettings() {
  if (!blobRead(blobBuf, sizeof(blobBuf))) return;
  char* b = (char*)blobBuf;
  if (memcmp(b, "IOTCFG1\n", 8) != 0) return;
  b[sizeof(blobBuf) - 1] = 0;
  JsonDocument d;
  if (!deserializeJson(d, b + 8)) {
    if (d["ssid"].is<const char*>()) {
      prefs.putString("ssid", (const char*)d["ssid"]);
      prefs.putString("pass", (const char*)(d["pass"] | ""));
    }
    if (d["name"].is<const char*>()) prefs.putString("name", (const char*)d["name"]);
    if (d["tz"].is<const char*>()) prefs.putString("tz", (const char*)d["tz"]);
    if (d["key"].is<const char*>() && strlen((const char*)d["key"]) >= 8) prefs.putString("key", (const char*)d["key"]);
    if (d["comps"].is<JsonArray>()) {
      JsonDocument cfg;
      cfg["comps"] = d["comps"];
      String c; serializeJson(cfg, c);
      prefs.putString("cfg", c);
      prefs.putUInt("rs", 0);
    }
    if (d["led"].is<JsonObject>()) { String l; serializeJson(d["led"], l); prefs.putString("led", l); }
    if (d["mqtt"].is<JsonObject>()) {
      prefs.putString("mq_host", (const char*)(d["mqtt"]["host"] | ""));
      prefs.putUInt("mq_port", d["mqtt"]["port"] | 8883);
      prefs.putString("mq_user", (const char*)(d["mqtt"]["user"] | ""));
      prefs.putString("mq_pass", (const char*)(d["mqtt"]["pass"] | ""));
    }
    Serial.println("IOTDEV-APPLIED settings from flash");
  }
  blobErase();
}

// ---------------- розетки Tuya (фонова задача) ----------------
// Кожна дія — коротке TCP-з'єднання з розеткою (порт 6668): розетка тримає лише одне з'єднання,
// тож довго його не займаємо, щоб і додаток міг підключитись. Стан опитується раз на хвилину.
// Якщо розетка змінила IP, плата знаходить її за ID у широкомовних UDP-пакетах (6666/6667).
#if HAS_TUYA
struct ClientTransport : tuya::Transport {
  WiFiClient& c;
  explicit ClientTransport(WiFiClient& cl) : c(cl) {}
  bool write(const uint8_t* d, size_t n) override { return c.write(d, n) == n; }
  int read(uint8_t* d, size_t n, uint32_t ms) override {
    uint32_t start = millis();
    while (!c.available()) {
      if (!c.connected()) return -1;
      if (millis() - start >= ms) return 0;
      vTaskDelay(pdMS_TO_TICKS(5));
    }
    int a = c.available();
    return c.read(d, (size_t)a < n ? a : n);
  }
};

static void tuyaUdp(WiFiUDP& u) {
  int n = u.parsePacket();
  if (n <= 0) return;
  static uint8_t buf[1024];
  n = u.read(buf, sizeof(buf));
  std::string js;
  if (n <= 0 || !tuya::decodeUdp(buf, n, &js)) return;
  JsonDocument d;
  if (deserializeJson(d, js.c_str())) return;
  const char* id = d["gwId"] | "";
  const char* ip = d["ip"] | "";
  if (!*id || !*ip) return;
  COMPS_LOCK();
  for (auto& c : comps)
    if (c.type == C_TUYA && c.tId == id && strcmp(c.tIp, ip) != 0) {
      strlcpy(c.tIp, ip, sizeof(c.tIp));
      c.tNextTry = 0;
    }
  COMPS_UNLOCK();
}

static void tuyaTask(void*) {
  WiFiUDP u6666, u6667;
  bool udp = false;
  for (;;) {
    vTaskDelay(pdMS_TO_TICKS(40));
    if (WiFi.status() != WL_CONNECTED) continue;
    if (!udp) { u6666.begin(6666); u6667.begin(6667); udp = true; }
    tuyaUdp(u6666);
    tuyaUdp(u6667);

    // вибираємо одну роботу: спершу команди, потім планові опитування
    int idx = -1, want = -1;
    uint32_t gen = 0;
    String ip, id, key;
    int ver = 33, dp = 1;
    uint32_t now = millis();
    COMPS_LOCK();
    for (int pass = 0; pass < 2 && idx < 0; pass++) {
      for (size_t i = 0; i < comps.size(); i++) {
        Comp& c = comps[i];
        if (c.type != C_TUYA || c.tId.isEmpty() || c.tKey.isEmpty()) continue;
        if (c.tNextTry && (int32_t)(now - c.tNextTry) < 0) continue;
        bool due = pass == 0 ? c.tWant >= 0
                             : (!c.tLastQuery || now - c.tLastQuery > (c.tOnline ? 60000UL : 15000UL));
        if (!due) continue;
        idx = i; want = pass == 0 ? c.tWant : -1;
        ip = c.tIp; id = c.tId; key = c.tKey; ver = c.tVer; dp = c.tDp;
        c.tLastQuery = now ? now : 1;
        break;
      }
    }
    gen = compsGen;
    COMPS_UNLOCK();
    if (idx < 0) continue;

    int r = -1;
    std::string err;
    if (ip.isEmpty()) {
      err = "IP unknown, searching the network";
    } else {
      WiFiClient cl;
      if (!cl.connect(ip.c_str(), 6668, 2500)) err = "no connection (busy or offline)";
      else {
        cl.setNoDelay(true);
        ClientTransport tr(cl);
        tuya::Ver v = ver == 34 ? tuya::V34 : ver == 35 ? tuya::V35 : tuya::V33;
        r = tuya::exchange(tr, v, key.c_str(), id.c_str(), dp, want, (uint32_t)time(nullptr), &err);
      }
      cl.stop();
    }

    COMPS_LOCK();
    if (gen == compsGen && idx < (int)comps.size()) {
      Comp& c = comps[idx];
      if (r >= 0) {
        c.on = r == 1; c.tKnown = true; c.tOnline = true; c.tErr[0] = 0; c.tNextTry = 0;
        if (c.tWant == want) c.tWant = -1;
      } else {
        c.tOnline = false;
        strlcpy(c.tErr, err.c_str(), sizeof(c.tErr));
        c.tNextTry = millis() + 5000;   // повтор команди через 5 с
      }
    }
    COMPS_UNLOCK();
  }
}
#endif

// ---------------- setup / loop ----------------

void setup() {
  Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT && !defined(ESP8266)
  Serial0.begin(115200);
#endif
  delay(100);
  pinMode(BOOT_PIN, INPUT_PULLUP);

  uint8_t mac[6];
#if defined(ESP8266)
  WiFi.macAddress(mac);
#else
  // до старту Wi-Fi WiFi.macAddress() повертає нулі — читаємо MAC з eFuse
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
#endif
  char buf[16];
  snprintf(buf, sizeof(buf), "esp-%02x%02x%02x", mac[3], mac[4], mac[5]);
  deviceId = buf;

#if HAS_TUYA
  compsLock = xSemaphoreCreateMutex();
#endif
  prefs.begin();
  applyFlashedSettings();
  deviceName = prefs.getString("name", String("IoT ") + CHIP_NAME);
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");
  apiKey = prefs.getString("key", "");
  loadConfig();
  ledLoad();

  // спершу Wi-Fi (він ініціалізує мережевий стек), і лише потім сервер:
  // інакше на ESP32 server.begin() падає з «tcpip_send_msg_wait_sem: Invalid mbox»
  if (wifiSsid.isEmpty()) startAp();
  else startSta();
  setupServer();
  mqttApplySettings();
#if HAS_TUYA
  xTaskCreate(tuyaTask, "tuya", 8192, nullptr, 1, nullptr);
#endif
  Serial.printf("IoT Devices %s, id %s\n", fwVersion(), deviceId.c_str());
  Serial.print("IOTDEV "); Serial.println(infoJson());
}

uint32_t bootPressedAt = 0;

bool pinUsed(int p) {
  for (auto& c : comps) if (c.pin == p || (c.type == C_AHT || c.type == C_BME || c.type == C_SHT21 ? c.pin2 == p : false)) return true;
  return false;
}

void ledWrite(bool on) {
#if !defined(ESP8266)
  if (ledRgb) { neopixelWrite(ledPin, 0, 0, on ? 40 : 0); return; }
#endif
  digitalWrite(ledPin, (on != ledInv) ? HIGH : LOW);
}

void ledLoad() {
  ledDefaults();
  JsonDocument d;
  if (!deserializeJson(d, prefs.getString("led", "")) && d.is<JsonObject>()) {
    ledEn = d["en"] | true;
    ledPin = d["pin"] | ledPin;
    ledInv = d["inv"] | ledInv;
    ledRgb = d["rgb"] | ledRgb;
  }
  ledActive = false;
}

void ledLoop(uint32_t now) {
  // світлодіод вимкнено в налаштуваннях або його пін зайняв компонент — не чіпаємо
  bool usable = ledEn && validPin(ledPin) && !pinUsed(ledPin);
  if (!usable) { ledActive = false; return; }
  if (!ledActive) { pinMode(ledPin, OUTPUT); ledActive = true; ledLevel = !ledLevel; }
  bool want;
  if (apMode) want = true;
  else if (!wifiSsid.isEmpty() && WiFi.status() != WL_CONNECTED) want = (now / 1000) % 2 == 0;
  else want = false;
  if (want != ledLevel) { ledLevel = want; ledWrite(want); }
}

/**
 * Команди через USB/UART (115200): додаток після прошивки передає Wi-Fi тим самим кабелем.
 *   INFO                      -> IOTDEV {"id":...}
 *   WIFI {"ssid":"..","pass":".."} -> IOTDEV-OK wifi
 */
struct SerialPort { Stream* s; String buf; };

void handleSerialLine(Stream& out, String line) {
  line.trim();
  if (line == "INFO") {
    out.print("IOTDEV "); out.println(infoJson());
  } else if (line.startsWith("WIFI ")) {
    JsonDocument d;
    if (!deserializeJson(d, line.substring(5)) && d["ssid"].is<const char*>()) {
      prefs.putString("ssid", (const char*)d["ssid"]);
      prefs.putString("pass", (const char*)(d["pass"] | ""));
      out.println("IOTDEV-OK wifi");
      out.flush();
      scheduleRestart(500);
    } else {
      out.println("IOTDEV-ERR bad wifi json");
    }
  }
}

#if ARDUINO_USB_CDC_ON_BOOT && !defined(ESP8266)
SerialPort ports[] = {{&Serial, ""}, {&Serial0, ""}};   // нативний USB і UART0 (якщо на платі є USB-UART міст)
#else
SerialPort ports[] = {{&Serial, ""}};
#endif

void handleSerial() {
  for (auto& p : ports) {
    while (p.s->available()) {
      char ch = (char)p.s->read();
      if (ch == '\r') continue;
      if (ch != '\n') { if (p.buf.length() < 512) p.buf += ch; continue; }
      String line = p.buf; p.buf = "";
      handleSerialLine(*p.s, line);
    }
  }
}

void loop() {
  uint32_t now = millis();
  server.handleClient();
  if (apMode) dns.processNextRequest();

  // Wi-Fi: якщо довго не вдається підключитись — вмикаємо точку доступу для налаштування
  if (!wifiSsid.isEmpty()) {
    if (WiFi.status() == WL_CONNECTED) {
      lastStaOk = now;
      startMdns();
      if (!timeStarted) { startTime(); timeStarted = true; }
      if (apMode && now - staStartedAt > 5000) stopAp();
    } else if (!apMode && now - max(staStartedAt, lastStaOk) > STA_TIMEOUT) {
      startAp();
      WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
    }
  }

#if defined(ESP8266)
  MDNS.update();
#endif
  handleSerial();

  // утримання BOOT 5 с — скидання до заводських налаштувань (якщо пін не зайнятий компонентом)
  if (!pinUsed(BOOT_PIN) && digitalRead(BOOT_PIN) == LOW) {
    if (!bootPressedAt) bootPressedAt = now;
    else if (now - bootPressedAt > 5000) factoryReset();
  } else bootPressedAt = 0;

  handleButtons(now);
  readSensors(now);
  handleThermostats(now);
  mqttLoop(now);
  ledLoop(now);

  for (auto& c : comps) {
    if (c.type == C_RELAY && c.countdownEnd && (int32_t)(now - c.countdownEnd) >= 0) {
      setRelay(c, !c.on);
    }
    if (c.type == C_WS2812 && c.on && c.fx != "none") {
      static uint32_t lastFx = 0;
      if (now - lastFx > 30) { lastFx = now; applyStrip(c, now); }
    }
  }

  if (restartPending && (int32_t)(now - restartAt) >= 0) ESP.restart();
  delay(2);
}
