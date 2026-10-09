# Robot Radar Link (Android)

Native Android app for each mini robot. It:

- Reads the **HLK-LD2415H** 24GHz radar through an **Arduino** connected via **OTG USB**
  (usb-serial-for-android, 115200 8N1). The Arduino forwards compact JSON lines
  (see `arduino/robot_radar`), the phone parses and relays them to MQTT.
- Publishes the robot state every second to
  `robots/<name>/status`:
  ```json
  {"name":"robot-1","lat":47.91,"long":106.89,"alt":1280.5,"speed":0.2,
   "battery":86,"charging":false,"voltage":4.02,
   "accel":[0.1,9.8,0.3],"gyro":[0.01,-0.02,0.03],"heading":90.0,"ts":1757482200000}
  ```
- Publishes radar detections immediately to `robots/<name>/radar`:
  ```json
  {"name":"robot-1","state":1,"dist":420,"ena":55,
   "targets":[{"g":2,"t":0,"d":420,"e":55}],"ts":1757482200000}
  ```
  `state`: 0=no target, 1=moving, 2=static. `dist`/`ena` = strongest target in cm/energy.
- Streams the **back camera** as MJPEG to the Node.js server (`POST /cam/<name>`
  JPEG body; viewer at `GET /cam/<name>/mjpeg` every 1 s). This becomes the
  robot's "IP camera".
- Connection settings (robot name, broker IP, MQTT TCP port, HTTP/camera port,
  publish intervals) are edited in the app GUI and saved in SharedPreferences.

## Build

1. Open this folder (`android-robot-app/`) in **Android Studio** (Koala / Ladybug or newer).
   Gradle downloads the wrapper automatically; required: JDK 17, SDK platform 34.
2. Let it sync (dependencies: Paho MQTT, usb-serial-for-android via JitPack).
3. `Build » Build APK(s)` (or Run on a device). Install the APK on the phone.

You can also build from a terminal once the Gradle wrapper exists:

```
./gradlew assembleDebug
```

Gradle/JDK and the Android SDK are NOT included here on purpose - generate the
wrapper from Android Studio (`File » Sync`, or `gradle wrapper`).

## First run on the phone

1. Allow the requested permissions: **Location**, **Camera**.
2. Enter the broker host = the PC running `mqtt-server.js` (its LAN IP, e.g. `192.168.1.100`),
   TCP port `1883`, HTTP/camera port `8080`.
3. Set a unique **robot name** per robot (e.g. `robot-1`).
4. Plug the Arduino OTG into the phone (USB debugging dialog / permission request - allow).
   The status line should show `USB connected`.
5. Tap **START** - the phone begins publishing status; the radar page shows the
   robot online and its radar detection ring on the map.
6. Tap **CAMERA ON** to start the MJPEG stream (visible in the web Robots page).

## Wiring (phone <- OTG -> Arduino <- UART -> LD2415H)

```
[Phone]  <-USB OTG cable->  [Arduino USB port]
[Arduino pin D2 (RX)]  <-  [LD2415H TX]
[Arduino pin D3 (TX)]  ->  [LD2415H RX]
[Arduino 5V]           ->  [LD2415H 5V/VIN]
[Arduino GND]          ~   [LD2415H GND]
```
Same wiring is used by `arduino/robot_radar/robot_radar.ino` (SoftwareSerial D2/D3,
115200 8N1). On boards with a second hardware UART (Mega/Leonardo/ESP32) uncomment
`USE_HARDWARE_SERIAL1`.

## Notes / limits

- The browser-less native app uses plain TCP MQTT (port 1883) - the Node.js
  broker must be reachable from the phone over the same Wi-Fi.
- The LD2415H reports **range + energy only** (no azimuth), so radar blips are
  drawn ahead of the robot at the reported distance plus a radial band around it.
- `android:usesCleartextTraffic="true"` is enabled so the app can POST frames to
  the plain-HTTP Node.js server.