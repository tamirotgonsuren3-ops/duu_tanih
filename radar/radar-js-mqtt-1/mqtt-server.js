/**
 * Radar MQTT Server
 * -----------------
 * - Serves radar.html       (default port 8080)
 * - MQTT broker over WS     (same port as the web app)
 * - MQTT broker over TCP    (default port 1883)
 * - Android robot registry  (/api/robots)
 * - Camera frame endpoints  (/cam/<name>, /cam/<name>/mjpeg, /cam/<name>/frame.jpg)
 *
 * If a configured port is already in use, a free port is auto-selected.
 * The web app discovers the real ports via /config.json.
 *
 * Usage:
 *   npm install
 *   npm start
 * Then open:  http://localhost:8080
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const aedes = require('aedes')();
const ws = require('ws');
const net = require('net');

const PREFERRED_TCP_PORT = parseInt(process.env.MQTT_TCP_PORT) || 1883;
const PREFERRED_HTTP_PORT = parseInt(process.env.HTTP_PORT) || 8080;

const MAX_CAM_FRAME = 3 * 1024 * 1024;   // 3 MB per camera frame
const ONLINE_TIMEOUT = 10000;            // ms without a status message => offline

/**
 * Listen on `preferred`; if EADDRINUSE, fall back to a random free port.
 * Resolves with the actual port in use.
 */
function tryListen(server, preferred, label) {
  return new Promise(function (resolve, reject) {
    let usedFree = false;

    function onListening() {
      server.removeListener('error', onError);
      resolve(server.address().port);
    }

    function onError(err) {
      if (err.code === 'EADDRINUSE' && !usedFree && preferred !== 0) {
        usedFree = true;
        console.warn(
          '[WARN] ' + label + ' port ' + preferred +
          ' is busy, choosing a random free port...'
        );
        server.once('error', onError); // re-arm for the fallback attempt
        server.listen(0);
      } else {
        server.removeListener('listening', onListening);
        reject(err);
      }
    }

    server.once('listening', onListening);
    server.once('error', onError);
    server.listen(preferred);
  });
}

let tcpPort = PREFERRED_TCP_PORT;
let httpPort = PREFERRED_HTTP_PORT;

// ---------- Static files ----------
const htmlPath = path.join(__dirname, 'radar.html');
const pahoPath = path.join(__dirname, 'paho-mqtt.js');
const leafletDir = path.join(__dirname, 'leaflet');
const lessonDir = path.join(__dirname, 'js-lesson');

function sendFile(res, filePath, contentType) {
  fs.readFile(filePath, function (err, data) {
    if (err) {
      res.writeHead(404);
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
}

// ---------- Android robot registry (server side mirror of MQTT) ----------
const robots = {}; // name -> { name, lastSeen, lastStatus, lastRadar }

// ---------- Camera frame store ----------
const cams = {};   // name -> { buf, ts, streams:Set<res> }

function readBody(req, limit, cb) {
  const chunks = [];
  let size = 0;
  req.on('data', function (d) {
    size += d.length;
    if (size > limit) { req.destroy(); cb(null); return; }
    chunks.push(d);
  });
  req.on('end', function () { cb(Buffer.concat(chunks)); });
  req.on('error', function () { cb(null); });
}

function writeFrame(res, buf) {
  res.write('--radarframe\r\nContent-Type: image/jpeg\r\nContent-Length: ' + buf.length + '\r\n\r\n');
  res.write(buf);
  res.write('\r\n');
}

function storeCamFrame(name, buf) {
  const c = cams[name] || (cams[name] = { buf: null, ts: 0, streams: new Set() });
  c.buf = buf;
  c.ts = Date.now();
  c.streams.forEach(function (res) {
    try { writeFrame(res, buf); } catch (e) { /* stream gone */ }
  });
}

function handleCameraRoute(req, res, pathParts) {
  if (pathParts[0] !== 'cam' || pathParts.length < 2) return false;
  const name = decodeURIComponent(pathParts[1]);
  // sanity: name must be a simple token
  if (!/^[a-zA-Z0-9_-]{1,64}$/.test(name)) {
    res.writeHead(400); res.end('bad name');
    return true;
  }
  const action = pathParts[2] || '';
  res.setHeader('Access-Control-Allow-Origin', '*');

  if (req.method === 'POST' && action === '') {
    readBody(req, MAX_CAM_FRAME, function (buf) {
      if (!buf || buf.length === 0) {
        res.writeHead(400); res.end('empty frame');
        return;
      }
      storeCamFrame(name, buf);
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('ok ' + buf.length);
    });
    return true;
  }

  if (req.method === 'GET' && action === 'mjpeg') {
    const c = cams[name] || (cams[name] = { buf: null, ts: 0, streams: new Set() });
    res.writeHead(200, {
      'Content-Type': 'multipart/x-mixed-replace; boundary=radarframe',
      'Cache-Control': 'no-store, no-cache, must-revalidate'
    });
    if (c.buf) writeFrame(res, c.buf);
    c.streams.add(res);
    res.on('close', function () { c.streams.delete(res); });
    return true;
  }

  if (req.method === 'GET' && action === 'frame.jpg') {
    const c = cams[name];
    if (c && c.buf) {
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' });
      res.end(c.buf);
    } else {
      res.writeHead(404); res.end('no frame yet');
    }
    return true;
  }

  return true; // anything else under /cam/* belongs to us
}

// ---------- HTTP server ----------
const httpServer = http.createServer(function (req, res) {
  const pathParts = url.parse(req.url).pathname.split('/').filter(Boolean);

  if (req.url === '/' || req.url === '/radar.html') {
    sendFile(res, htmlPath, 'text/html; charset=utf-8');
  } else if (req.url === '/paho-mqtt.js') {
    sendFile(res, pahoPath, 'application/javascript');
  } else if (req.url === '/leaflet/leaflet.js') {
    sendFile(res, path.join(leafletDir, 'leaflet.js'), 'application/javascript');
  } else if (req.url === '/leaflet/leaflet.css') {
    sendFile(res, path.join(leafletDir, 'leaflet.css'), 'text/css');
  } else if (req.url === '/leaflet/GoogleMutant.js') {
    sendFile(res, path.join(leafletDir, 'GoogleMutant.js'), 'application/javascript');
  } else if (req.url === '/config.json') {
    const cfg = {
      host: 'localhost',
      mqttTcpPort: tcpPort,
      wsPort: httpPort,
      httpPort: httpPort,
      topic: 'radar/detections',
      robotTopicPrefix: 'robots'
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(cfg));
  } else if (req.url === '/favicon.ico') {
    res.writeHead(204);
    res.end();
  } else if (req.url === '/js-lesson' || req.url === '/js-lesson/') {
    res.writeHead(302, { Location: '/js-lesson/index.html' });
    res.end();
  } else if (req.url.indexOf('/js-lesson/') === 0) {
    const rel = pathParts.slice(1).join(path.sep); // strip leading 'js-lesson'
    const fp = path.join(lessonDir, rel);
    if (fp.indexOf(lessonDir + path.sep) !== 0) {
      res.writeHead(403); res.end('forbidden');
      return;
    }
    let ct = 'text/plain; charset=utf-8';
    if (/\.html?$/.test(fp)) ct = 'text/html; charset=utf-8';
    else if (/\.css$/.test(fp)) ct = 'text/css';
    else if (/\.md$/.test(fp)) ct = 'text/markdown; charset=utf-8';
    else if (/\.js$/.test(fp)) ct = 'application/javascript';
    else if (/\.(png|jpe?g|gif|svg|ico)$/.test(fp)) ct = 'image/' + (/\.svg$/.test(fp) ? 'svg+xml' : (/\.png$/.test(fp) ? 'png' : 'jpeg'));
    else if (/\.pdf$/.test(fp)) ct = 'application/pdf';
    sendFile(res, fp, ct);
  } else if (req.url === '/api/robots') {
    res.setHeader('Access-Control-Allow-Origin', '*');
    const now = Date.now();
    const list = Object.keys(robots).map(function (k) {
      const r = robots[k];
      return {
        name: k,
        online: (now - r.lastSeen) < ONLINE_TIMEOUT,
        lastSeen: r.lastSeen,
        lastStatus: r.lastStatus,
        lastRadar: r.lastRadar
      };
    });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ robots: list }));
  } else if (handleCameraRoute(req, res, pathParts)) {
    // handled above
  } else {
    res.writeHead(404);
    res.end('Not found');
  }
});

// MQTT broker over WebSocket - upgrades on the same HTTP server (any path)
const wsServer = new ws.Server({ server: httpServer });
// Errors are handled by `tryListen` on httpServer; swallow the re-emitted copy here.
wsServer.on('error', function () {});
wsServer.on('connection', function (socket) {
  const stream = ws.createWebSocketStream(socket);
  aedes.handle(stream);
});

// ---------- MQTT broker over TCP ----------
const tcpServer = net.createServer(aedes.handle);

(async function start() {
  try {
    httpPort = await tryListen(httpServer, PREFERRED_HTTP_PORT, 'HTTP web app');
    tcpPort = await tryListen(tcpServer, PREFERRED_TCP_PORT, 'MQTT-TCP');

    console.log('\n---------------------------------------------------');
    console.log('  Radar MQTT Server running');
    console.log('  - Web app  : http://localhost:' + httpPort);
    console.log('  - MQTT WS  : localhost:' + httpPort);
    console.log('  - MQTT TCP : localhost:' + tcpPort);
    console.log('  - Config   : http://localhost:' + httpPort + '/config.json');
    console.log('  - Robots   : http://localhost:' + httpPort + '/api/robots');
    console.log('  - Cameras  : http://localhost:' + httpPort + '/cam/<robot>/mjpeg');
    console.log('---------------------------------------------------\n');

    if (httpPort !== PREFERRED_HTTP_PORT || tcpPort !== PREFERRED_TCP_PORT) {
      console.log('[INFO] Some default ports were busy; free ports were chosen automatically.');
      console.log('[INFO] The web app auto-detects the real ports from /config.json.\n');
    }
  } catch (err) {
    console.error('[FATAL] Could not start servers:', err.message);
    process.exit(1);
  }
})();

// ---------- Broker events ----------
aedes.on('client', function (client) {
  console.log('[MQTT] client connected:', client.id);
});
aedes.on('clientDisconnect', function (client) {
  console.log('[MQTT] client disconnected:', client.id);
});
aedes.on('publish', function (packet, client) {
  const topic = packet.topic;
  if (client) {
    console.log('[MQTT] publish ->', topic, '|', packet.payload.toString().slice(0, 80));
  }
  const m = /^robots\/([^/]+)\/(status|radar)$/.exec(topic);
  if (m) {
    const name = m[1];
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(name)) return;
    let parsed = null;
    try { parsed = JSON.parse(packet.payload.toString()); } catch (e) { /* ignore */ }
    if (parsed) {
      const r = robots[name] || (robots[name] = { name: name, lastSeen: 0, lastStatus: null, lastRadar: null });
      r.lastSeen = Date.now();
      if (m[2] === 'status') r.lastStatus = parsed;
      else r.lastRadar = parsed;
    }
    const keys = Object.keys(robots);
    if (keys.length > 300) { // keep memory bounded
      delete robots[keys[0]];
    }
  }
});
aedes.on('subscribe', function (subscriptions, client) {
  if (client) {
    console.log('[MQTT] subscribe <-', subscriptions[0].topic, '|', client.id);
  }
});