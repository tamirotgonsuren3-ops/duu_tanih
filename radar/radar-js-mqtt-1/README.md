# Radar Detection System

Portable radar web application + local MQTT broker + Android robot integration.
Copy the whole folder to any computer, `npm install && npm start`, open
`http://localhost:8080` - no other installation needed.

## Quick start

```
npm install
npm start
```
Then open http://localhost:8080. The page auto-connects to the built-in broker
(default: MQTT TCP 1883, WebSocket + web app on 8080). If a port is busy a free
one is picked automatically; the web app detects it from `/config.json`.

## Layout

| Path | Purpose |
|------|---------|
| `radar.html` | Web app: map / radar view, controls, robots page |
| `mqtt-server.js` | aedes MQTT broker (TCP 1883 + WS 8080), HTTP file server, robot registry, camera endpoints |
| `paho-mqtt.js` | Local copy of the Paho MQTT (WebSocket) client |
| `leaflet/` | Local map library + Google Mutant plugin |
| `arduino/robot_radar/` | Arduino sketch: HLK-LD2415H radar -> JSON over USB |
| `android-robot-app/` | Android Studio project for the phone app on each mini robot |

## MQTT topics

| Topic | Payload | Direction |
|-------|---------|-----------|
| `radar/detections` | `{datetime, lat, long, altitude, speed, direction, object_name}` | any MQTT client -> web radar |
| `robots/<name>/status` | `{name, lat, long, battery, accel, gyro, heading, ts, ...}` | Android robot -> web |
| `robots/<name>/radar` | `{name, state, dist, ena, targets[]}` | Android robot -> web |
| `robots/<name>/cmd` | reserved for future commands | web -> robot |

## Web app

- **Home** page: rotating radar on the map (map provider / dark-black theme /
  green-red-blue + custom color picker), historical detection table (20/page),
  and the Android robots table below it. Robots are drawn on the map with their
  own range circle, range rings, heading and current detections.
- **Robots** page (sidebar): register/add robots, see online/offline status,
  battery, GPS and last radar reading. Clicking a row opens the robot settings
  dialog: range (default 200 m), range rings, radar color, GPS / manual position,
  heading, "center map here", and a live camera view.

## Android robots

Each physical mini robot is an Arduino + HLK-LD2415H radar on a phone over OTG USB.
The phone app (`android-robot-app/`) publishes sensors (GPS, battery,
accelerometer, gyroscope, heading) and forwards the radar detections; it also
streams its camera as an MJPEG feed. See `android-robot-app/README.md` for build
and wiring instructions.

### Camera endpoints (Node.js server side)

| Method / path | Description |
|---------------|-------------|
| `POST /cam/<name>` | JPEG body = one camera frame from a robot |
| `GET /cam/<name>/mjpeg` | live MJPEG stream for the web UI |
| `GET /cam/<name>/frame.jpg` | latest single frame |
| `GET /api/robots` | server-side robot registry (online/offline) |

## Simulated data (no hardware)

Click **Simulate** in the sidebar to feed fake aircraft detections. Mosquitto CLIs
also work:

```
mosquitto_pub -h localhost -t "radar/detections" -m '{"datetime":"2026-09-10 14:30:00","lat":47.91,"long":106.88,"altitude":500,"speed":120,"direction":45,"object_name":"target-01"}'
```

## Notes

- Map tiles need internet (CARTO/OSM/OpenTopoMap/Esri/Google). Without internet
  the map is grey but the radar keeps working.
- Google Maps requires an API key from `console.cloud.google.com`; without it the
  map falls back to OpenStreetMap.
- The Android app is meant to compile with Android Studio (JDK 17, SDK 34).