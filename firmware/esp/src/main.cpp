// IoT Devices — універсальна прошивка для ESP8266 / ESP32 / S2 / S3 / C3.
// Що підключено до яких пінів, задається JSON-конфігом з додатку (POST /api/config),
// тож перепрошивати плату під кожен пристрій не треба.

#include "platform.h"
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

// ---------------- компоненти ----------------

enum CompType { C_RELAY, C_BUTTON, C_PWM, C_WS2812, C_DHT, C_DS18B20, C_AHT, C_ANALOG, C_INPUT, C_THERMO, C_BME, C_UNKNOWN };

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
  String thermoState = "idle"; // idle | on | off | nosensor
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

void freeComps() {
  for (auto& c : comps) {
    if (c.strip) { c.strip->clear(); c.strip->show(); delete c.strip; }
    delete c.dht; delete c.ds; delete c.ow; delete c.aht; delete c.bme; delete c.bmp;
    if (c.ledcCh >= 0 && validPin(c.pin)) pwmDetach(c.pin);
  }
  comps.clear();
  nextLedc = 0;
}

/** Створює компоненти з JSON-конфігу (поле "comps"). */
void buildComps(JsonArrayConst arr) {
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
    if (c.type == C_AHT || c.type == C_BME) { c.pin = o["sda"] | -1; }
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
        break;
      default:
        break;
    }
    comps.push_back(c);
  }
}

String configJson;  // збережений конфіг як є

void loadConfig() {
  configJson = prefs.getString("cfg", "{\"comps\":[]}");
  JsonDocument doc;
  if (deserializeJson(doc, configJson)) { doc.clear(); doc["comps"].to<JsonArray>(); }
  deviceName = prefs.getString("name", deviceName);
  buildComps(doc["comps"].as<JsonArrayConst>());
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

void setRelay(Comp& c, bool on) {
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
void handleThermostats(uint32_t now) {
  for (auto& t : comps) {
    if (t.type != C_THERMO) continue;
    if (t.sensor < 0 || t.sensor >= (int)comps.size() || t.target < 0 || t.target >= (int)comps.size()) continue;
    Comp& sensor = comps[t.sensor];
    Comp& relay = comps[t.target];
    if (relay.type != C_RELAY) continue;
    t.temp = sensor.temp;
    if (!t.enabled) { t.thermoState = "idle"; t.sensorBadSince = 0; continue; }
    if (isnan(sensor.temp)) {
      if (!t.sensorBadSince) t.sensorBadSince = now ? now : 1;
      t.thermoState = "nosensor";
      if (t.heat && relay.on && now - t.sensorBadSince > 60000) setRelay(relay, false);
      continue;
    }
    t.sensorBadSince = 0;
    int want = -1;
    if (t.heat) { if (sensor.temp <= t.onAt) want = 1; else if (sensor.temp >= t.offAt) want = 0; }
    else { if (sensor.temp >= t.onAt) want = 1; else if (sensor.temp <= t.offAt) want = 0; }
    if (want >= 0 && (bool)want != relay.on && (t.lastSwitch == 0 || now - t.lastSwitch > 30000)) {
      t.lastSwitch = now;
      setRelay(relay, want == 1);
    }
    t.thermoState = relay.on ? "on" : "off";
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

String stateJson() {
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
      case C_DHT: case C_AHT:
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
        putNum(o, "onAt", c.onAt); putNum(o, "offAt", c.offAt); putNum(o, "temp", c.temp);
        o["sensor"] = c.sensor; o["target"] = c.target; o["state"] = c.thermoState;
        break;
      default:
        break;
    }
  }
  doc["uptime"] = now / 1000;
  doc["rssi"] = WiFi.RSSI();
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

void handleSet() {
  if (!requireAuth()) return;
  JsonDocument doc;
  if (!parseBody(doc)) return;
  // можна надіслати один об'єкт або масив
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
    } else if (c.type == C_THERMO) {
      if (o["enabled"].is<bool>()) c.enabled = o["enabled"];
      if (o["onAt"].is<float>()) c.onAt = o["onAt"];
      if (o["offAt"].is<float>()) c.offAt = o["offAt"];
      if (o["mode"].is<const char*>()) c.heat = String((const char*)o["mode"]) != "cool";
      c.lastSwitch = 0;
      persistThermo(i);
    }
  }
  sendJson(200, stateJson());
}

void handleConfigPost() {
  if (!requireAuth()) return;
  JsonDocument doc;
  if (!parseBody(doc)) return;
  if (doc["name"].is<const char*>()) {
    deviceName = (const char*)doc["name"];
    prefs.putString("name", deviceName);
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

bool otaAuthorized = false;

void setupServer() {
  const char* headers[] = {"X-Key"};
  server.collectHeaders(headers, 1);

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
    String s; serializeJson(doc, s);
    sendJson(200, s);
  });
  server.on("/api/config", HTTP_POST, handleConfigPost);
  server.on("/api/wifi", HTTP_POST, handleWifi);
  server.on("/api/scan", HTTP_GET, handleScan);
  server.on("/api/claim", HTTP_POST, handleClaim);
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

// ---------------- setup / loop ----------------

void setup() {
  Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT && !defined(ESP8266)
  Serial0.begin(115200);
#endif
  delay(100);
  pinMode(BOOT_PIN, INPUT_PULLUP);

  uint8_t mac[6];
  WiFi.macAddress(mac);
  char buf[16];
  snprintf(buf, sizeof(buf), "esp-%02x%02x%02x", mac[3], mac[4], mac[5]);
  deviceId = buf;

  prefs.begin();
  deviceName = prefs.getString("name", String("IoT ") + CHIP_NAME);
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");
  apiKey = prefs.getString("key", "");
  loadConfig();

  setupServer();
  if (wifiSsid.isEmpty()) startAp();
  else startSta();
  Serial.printf("IoT Devices %s, id %s\n", fwVersion(), deviceId.c_str());
  Serial.print("IOTDEV "); Serial.println(infoJson());
}

uint32_t bootPressedAt = 0;

bool pinUsed(int p) {
  for (auto& c : comps) if (c.pin == p || (c.type == C_AHT || c.type == C_BME ? c.pin2 == p : false)) return true;
  return false;
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
