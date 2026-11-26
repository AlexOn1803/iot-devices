// Анімації стрічки WS2812 (перенесені зі скетча tg_led_strip): рахуються у буфері FastLED (CRGB),
// а виводяться через Adafruit_NeoPixel — так пін задається в налаштуваннях плати, а не при збірці.
#pragma once
#define FASTLED_INTERNAL
#include <FastLED.h>

struct FxDef { const char* id; const char* name; bool usesColor; };

// "none" — свій колір (статично); порядок — як у списку в панелі/додатку
static const FxDef FX_LIST[] = {
  {"none", "Свій колір", true},
  {"breathe", "Дихання", true},
  {"wipe", "Wipe", true},
  {"confetti", "Confetti", false},
  {"sinelon", "Sinelon", true},
  {"juggle", "Juggle", false},
  {"juggle2", "Juggle 2", false},
  {"plasma", "Plasma", false},
  {"rainbow", "Rainbow", false},
  {"glitter", "Rainbow Glitter", false},
  {"snow", "Snow", false},
  {"matrix", "Matrix Rain", false},
  {"running", "Running Lights", false},
  {"lava", "Lava", false},
  {"ocean", "Ocean", false},
  {"aurora", "Aurora", false},
  {"pacifica", "Pacifica", false},
  {"theater", "Theater Chase", true},
  {"theater_rainbow", "Theater Rainbow", false},
  {"pride", "Pride", false},
};
static const int FX_COUNT = sizeof(FX_LIST) / sizeof(FX_LIST[0]);

inline int fxIndex(const String& id) {
  for (int i = 0; i < FX_COUNT; i++) if (id == FX_LIST[i].id) return i;
  return -1;
}

struct FxState {
  CRGB* leds = nullptr;
  int n = 0;
  int lastFx = -1;
  uint8_t hue = 0;
  int step = 0;
  bool dir = true;
  uint16_t t = 0;
  uint16_t pc1 = 0, pc2 = 0, pc3 = 0, pc4 = 0;   // pacifica
  uint32_t lastMs = 0;
  uint16_t pseudo = 0, hue16 = 0;                 // pride
  uint32_t lastFrame = 0;
  void alloc(int count) { free(); n = count; leds = new CRGB[count]; fill_solid(leds, n, CRGB::Black); }
  void free() { delete[] leds; leds = nullptr; n = 0; }
};

DEFINE_GRADIENT_PALETTE(fx_aurora_gp){0, 0, 40, 15, 70, 0, 200, 70, 120, 0, 255, 130, 170, 0, 140, 170, 215, 50, 20, 180, 255, 130, 0, 200};

static const CRGBPalette16 fxPac1 = {0x000507, 0x000409, 0x00030B, 0x00030D, 0x000210, 0x000212, 0x000114, 0x000117,
                                     0x000019, 0x00001C, 0x000026, 0x000031, 0x00003B, 0x000046, 0x14554B, 0x28AA50};
static const CRGBPalette16 fxPac2 = {0x000507, 0x000409, 0x00030B, 0x00030D, 0x000210, 0x000212, 0x000114, 0x000117,
                                     0x000019, 0x00001C, 0x000026, 0x000031, 0x00003B, 0x000046, 0x0C5F52, 0x19BE5F};
static const CRGBPalette16 fxPac3 = {0x000208, 0x00030E, 0x000514, 0x00061A, 0x000820, 0x000927, 0x000B2D, 0x000C33,
                                     0x000E39, 0x001040, 0x001450, 0x001860, 0x001C70, 0x002080, 0x1040BF, 0x2060FF};

static void fxPacLayer(FxState& s, const CRGBPalette16& p, uint16_t cistart, uint16_t wavescale, uint8_t bri, uint16_t ioff) {
  uint16_t ci = cistart, waveangle = ioff, half = (wavescale / 2) + 20;
  for (int i = 0; i < s.n; i++) {
    waveangle += 250;
    uint16_t s16 = sin16(waveangle) + 32768;
    ci += scale16(s16, half) + half;
    uint16_t sindex16 = sin16(ci) + 32768;
    s.leds[i] += ColorFromPalette(p, scale16(sindex16, 240), bri, LINEARBLEND);
  }
}

/** Затримка між кадрами за швидкістю 1–10 (як у скетчі: 50…5 мс). */
inline uint32_t fxDelay(int speed) { return map(constrain(speed, 1, 10), 1, 10, 50, 5); }

/** Один кадр ефекту у s.leds. color — свій колір, speed — 1…10. */
inline void fxFrame(FxState& s, int fx, CRGB color, int speed, uint32_t now) {
  if (!s.leds || s.n <= 0) return;
  CRGB* leds = s.leds;
  const int N = s.n;
  if (fx != s.lastFx) { s.lastFx = fx; s.step = 0; s.dir = true; fill_solid(leds, N, CRGB::Black); }
  const char* id = (fx >= 0 && fx < FX_COUNT) ? FX_LIST[fx].id : "none";
  String e = id;
  if (e == "wipe") {
    leds[s.step % N] = s.dir ? color : CRGB(CRGB::Black);
    if (++s.step >= N) { s.step = 0; s.dir = !s.dir; }
  } else if (e == "confetti") {
    fadeToBlackBy(leds, N, 10);
    if (random8(10) < 8) leds[random16(N)] = CHSV(random8(), 255, 255);
  } else if (e == "sinelon") {
    fadeToBlackBy(leds, N, 20);
    leds[beatsin16(13, 0, N - 1)] = color;
  } else if (e == "juggle") {
    fadeToBlackBy(leds, N, 20);
    for (int i = 0; i < 5; i++) leds[beatsin16(i * 2 + 7, 0, N - 1)] |= CHSV(s.hue + i * 32, 255, 255);
    s.hue++;
  } else if (e == "juggle2") {
    fadeToBlackBy(leds, N, 28);
    for (int i = 0; i < 7; i++) leds[beatsin16(i * 3 + 12, 0, N - 1)] |= CHSV(i * 36, 255, 255);
  } else if (e == "plasma") {
    for (int i = 0; i < N; i++) leds[i] = CHSV(s.hue + sin8(i * 8), 255, 255);
    s.hue += 2;
  } else if (e == "rainbow") {
    for (int i = 0; i < N; i++) leds[i] = CHSV(s.hue + i * 6, 255, 255);
    s.hue++;
  } else if (e == "glitter") {
    for (int i = 0; i < N; i++) leds[i] = CHSV(s.hue + i * 8, 255, 255);
    if (random8(8) == 0) leds[random16(N)] = CRGB::White;
    s.hue++;
  } else if (e == "snow") {
    fadeToBlackBy(leds, N, 20);
    if (random8(20) == 0) leds[random16(N)] = CRGB::White;
  } else if (e == "matrix") {
    fadeToBlackBy(leds, N, 50);
    leds[random16(N)] = CRGB(0, random8(100, 255), 0);
  } else if (e == "running") {
    for (int i = 0; i < N; i++) leds[i] = CHSV(s.hue + i * 10, 255, sin8(i * 20 + s.hue));
    s.hue += 5;
  } else if (e == "lava") {
    for (int i = 0; i < N; i++) leds[i] = CRGB(255, random8(50, 120), 0);
  } else if (e == "ocean") {
    for (int i = 0; i < N; i++) leds[i] = CHSV(140 + sin8(i * 10 + s.hue) / 4, 255, 200);
    s.hue += 3;
  } else if (e == "aurora") {
    static const CRGBPalette16 pal = fx_aurora_gp;
    s.t += 3;
    for (int i = 0; i < N; i++) {
      uint8_t ci = inoise8(i * 18 + s.t / 2, s.t);
      ci = qsub8(ci, 40);
      ci = qadd8(ci, ci / 2);
      uint8_t b = inoise8(i * 9 + 10000, s.t * 2 + 20000);
      b = qsub8(b, 70);
      b = qadd8(b, b);
      leds[i] = ColorFromPalette(pal, ci, b);
    }
  } else if (e == "pacifica") {
    uint32_t dms = (now - s.lastMs) * speed / 5;
    s.lastMs = now;
    uint16_t sf1 = beatsin16(3, 179, 269), sf2 = beatsin16(4, 179, 269);
    uint32_t d1 = (dms * sf1) / 256, d2 = (dms * sf2) / 256, d21 = (d1 + d2) / 2;
    s.pc1 += d1 * beatsin88(1011, 10, 13);
    s.pc2 -= d21 * beatsin88(777, 8, 11);
    s.pc3 -= d1 * beatsin88(501, 5, 7);
    s.pc4 -= d2 * beatsin88(257, 4, 6);
    fill_solid(leds, N, CRGB(2, 6, 10));
    fxPacLayer(s, fxPac1, s.pc1, beatsin16(3, 11 * 256, 14 * 256), beatsin8(10, 70, 130), 0 - beat16(301));
    fxPacLayer(s, fxPac2, s.pc2, beatsin16(4, 6 * 256, 9 * 256), beatsin8(17, 40, 80), beat16(401));
    fxPacLayer(s, fxPac3, s.pc3, 6 * 256, beatsin8(9, 10, 38), 0 - beat16(503));
    fxPacLayer(s, fxPac3, s.pc4, 5 * 256, beatsin8(8, 10, 28), beat16(601));
    uint8_t base = beatsin8(9, 55, 65), wave = beat8(7);
    for (int i = 0; i < N; i++) {
      uint8_t th = scale8(sin8(wave), 20) + base;
      wave += 7;
      uint8_t l = leds[i].getAverageLight();
      if (l > th) { uint8_t o = l - th, o2 = qadd8(o, o); leds[i] += CRGB(o, o2, qadd8(o2, o2)); }
    }
    for (int i = 0; i < N; i++) { leds[i].blue = scale8(leds[i].blue, 145); leds[i].green = scale8(leds[i].green, 200); leds[i] |= CRGB(2, 5, 7); }
  } else if (e == "theater" || e == "theater_rainbow") {
    for (int i = 0; i < N; i++) {
      if (i % 3 != s.step / 4) leds[i] = CRGB::Black;
      else if (e == "theater") leds[i] = color;
      else leds[i] = CHSV(s.hue + i * 4, 255, 255);
    }
    s.step = (s.step + 1) % 12;
    s.hue++;
  } else if (e == "pride") {
    uint8_t sat8 = beatsin88(87, 220, 250), depth = beatsin88(341, 96, 224);
    uint16_t binc = beatsin88(203, 25 * 256, 40 * 256);
    uint8_t mult = beatsin88(147, 23, 60);
    uint16_t h16 = s.hue16, hinc = beatsin88(113, 1, 3000);
    uint16_t dms = (now - s.lastMs) * speed / 5;
    s.lastMs = now;
    s.pseudo += dms * mult;
    s.hue16 += dms * beatsin88(400, 5, 9);
    uint16_t bt = s.pseudo;
    for (int i = 0; i < N; i++) {
      h16 += hinc;
      bt += binc;
      uint16_t b16 = sin16(bt) + 32768;
      uint16_t bri16 = (uint32_t)b16 * b16 / 65536;
      uint8_t bri8 = (uint32_t)bri16 * depth / 65536;
      bri8 += (255 - depth);
      nblend(leds[(N - 1) - i], CRGB(CHSV(h16 / 256, sat8, bri8)), 64);
    }
  } else if (e == "breathe") {
    uint8_t k = 40 + scale8(sin8(now / 6), 215);
    CRGB c = color;
    c.nscale8_video(k);
    fill_solid(leds, N, c);
  } else {  // none — свій колір
    fill_solid(leds, N, color);
  }
}
