/*
 * robot_radar.ino
 * ---------------
 * HLK-LD2415H 24 GHz radar + Arduino -> Android phone over USB (OTG).
 *
 * Wiring (LD2415H -> Arduino):
 *   LD2415H 5V  -> Arduino 5V
 *   LD2415H GND -> Arduino GND
 *   LD2415H RX  -> Arduino TX pin
 *   LD2415H TX  -> Arduino RX pin
 *
 * NOTE: LD2415H UART = 115200 8N1.
 *
 * Boards:
 *   * Uno / Nano: this sketch uses SoftwareSerial on D2/D3.
 *   * Mega / Leonardo / Due / ESP32: uncomment the hardware-serial option below.
 *
 * The sketch only READS the radar and forwards parsed detections to the phone.
 * Every valid LD2415H frame is printed to the USB/Serial port as one JSON line:
 *
 *   RADAR {"state":1,"dist":420,"ena":55,"targets":[{"g":2,"t":0,"d":420,"e":55}]}
 *
 *   state 0 = nobody, 1 = moving target, 2 = static target
 *   dist/ena = strongest target distance (cm) / energy
 *   targets[] = per-gate list: g = gate, t = type, d = cm, e = energy
 *
 * Phone -> Arduino commands (typed into the Android app or USB serial monitor):
 *   PING    -> replies "PONG"
 *   RADAR?  -> repeats the behaviour (nothing extra, kept for future commands)
 */

// Uncomment ONE serial option.
#define USE_SOFTWARE_SERIAL 1
// #define USE_HARDWARE_SERIAL1 1   // Mega / Leonardo / ESP32(Serial1) etc.

// LD2415H protocol report frame
//   F4 F3 F2 F1  -> header (4 bytes)
//   word length (2 bytes, little endian) = 12 + 6*N
//   M (2), state (1), moving dist(2), moving ena(2),
//   static dist(2), static ena(2), gate count N(1), then N * 6 bytes per gate,
//   then 2 bytes CRC16 (CCITT, poly 0x1021, init 0xFFFF).

#ifdef USE_SOFTWARE_SERIAL
#include <SoftwareSerial.h>
#define RADAR_RX_PIN 2
#define RADAR_TX_PIN 3
SoftwareSerial RadarSS(RADAR_RX_PIN, RADAR_TX_PIN); // hardware RX -> D2, TX -> D3
#endif

static uint8_t frame[96];   // max 4 + (12+6*8) + 2 = 66 bytes
static uint16_t frameLen = 0;

// ---------------- CRC16 (same as HLK examples) ----------------
static uint16_t crc16(const uint8_t *p, uint16_t len) {
  uint16_t crc = 0xFFFF;
  while (len--) {
    crc ^= (uint16_t)(*p++) << 8;
    for (uint8_t i = 0; i < 8; i++) {
      crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
  }
  return crc;
}

static Stream* radarPort() {
#ifdef USE_SOFTWARE_SERIAL
  return &RadarSS;
#elif defined(USE_HARDWARE_SERIAL1)
  return &Serial1;
#else
  return NULL;
#endif
}

static void pushRadarByte(uint8_t b) {
  if (frameLen < 4) {
    // collect header; must start with 0xF4
    if (frameLen == 0 && b != 0xF4) return;
    frame[frameLen++] = b;
    if (frameLen == 4) {
      if (frame[0] == 0xF4 && frame[1] == 0xF3 && frame[2] == 0xF2 && frame[3] == 0xF1) {
        // header ok, keep collecting payload
      } else {
        frameLen = 0; // lost sync
      }
    }
    return;
  }

  frame[frameLen++] = b;
  uint16_t wordLen = (uint16_t)frame[2] | ((uint16_t)frame[3] << 8);
  uint16_t expect = 4 + wordLen + 2;
  if (frameLen == expect) {
    processFrame(expect);
    frameLen = 0;
  } else if (frameLen > expect && expect > 0) {
    frameLen = 0; // out of bounds, resync
  }
}

static void processFrame(uint16_t len) {
  // CRC check (accept either byte order)
  uint16_t crc = crc16(frame, len - 2);
  bool okHi = frame[len-2] == ((crc >> 8) & 0xFF) && frame[len-1] == (crc & 0xFF);
  bool okLo = frame[len-1] == ((crc >> 8) & 0xFF) && frame[len-2] == (crc & 0xFF);
  if (!okHi && !okLo) return; // bad frame, drop

  uint8_t state = frame[6];

  uint8_t n = frame[15];
  if (n > 8) n = 8;
  uint16_t wordLen = (uint16_t)frame[2] | ((uint16_t)frame[3] << 8);
  if (wordLen >= 12) {
    uint16_t nDerived = (wordLen - 12) / 6;
    if (nDerived <= 8) n = (uint8_t)nDerived;
  }

  uint16_t dist = 0, ena = 0;
  uint8_t i;
  for (i = 0; i < n; i++) {
    uint16_t p = 16 + i * 6;
    uint16_t e = frame[p + 4] | (frame[p + 5] << 8);
    if (e > ena) {
      ena = e;
      dist = frame[p + 2] | (frame[p + 3] << 8);
    }
  }

  static char out[300];
  uint16_t o = (uint16_t)sprintf(out, "RADAR {\"state\":%u,\"dist\":%u,\"ena\":%u,\"targets\":[", state, dist, ena);
  for (i = 0; i < n; i++) {
    uint16_t p = 16 + i * 6;
    o += (uint16_t)sprintf(out + o, "%s{\"g\":%u,\"t\":%u,\"d\":%u,\"e\":%u}",
                           (i ? "," : ""), frame[p], frame[p + 1],
                           (uint16_t)(frame[p + 2] | (frame[p + 3] << 8)),
                           (uint16_t)(frame[p + 4] | (frame[p + 5] << 8)));
  }
  o += (uint16_t)sprintf(out + o, "]}");
  (void)o;
  Serial.println(out);
}

static void monitorSerial() {
  while (Serial.available()) {
    String s = Serial.readStringUntil('\n');
    s.trim();
    if (s == "PING") Serial.println("PONG");
  }
}

void setup() {
  Serial.begin(115200);
#ifdef USE_SOFTWARE_SERIAL
  RadarSS.begin(115200);
  Serial.println("ROBOT_RADAR READY (SoftwareSerial D2/D3)");
#elif defined(USE_HARDWARE_SERIAL1)
  Serial1.begin(115200);
  Serial.println("ROBOT_RADAR READY (HardwareSerial1)");
#endif
  Serial.println("type PING to test");
}

void loop() {
  monitorSerial();
  Stream* rp = radarPort();
  if (rp) {
    while (rp->available()) pushRadarByte((uint8_t)rp->read());
  }
  delay(2);
}