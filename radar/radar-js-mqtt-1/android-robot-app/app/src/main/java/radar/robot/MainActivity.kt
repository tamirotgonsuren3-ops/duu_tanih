package radar.robot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERM = 101
    }

    private val state = SensorState()
    private val ui = Handler(Looper.getMainLooper())

    private var sensors: SensorsCollector? = null
    private var locationMonitor: LocationMonitor? = null
    private var battery: BatteryMonitor? = null
    private var mqtt: MqttManager? = null
    private var usb: UsbSerialManager? = null
    private var camera: CameraController? = null

    @Volatile private var running = false
    private var publishLoop: Runnable? = null

    private val radarParser = RadarFrameParser { report -> onRadarReport(report) }

    // views
    private lateinit var etRobot: EditText
    private lateinit var etMqttHost: EditText
    private lateinit var etMqttTcpPort: EditText
    private lateinit var etHttpPort: EditText
    private lateinit var etIntervalMs: EditText
    private lateinit var etCamMs: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCamStart: Button
    private lateinit var btnCamStop: Button
    private lateinit var tvStatusMqtt: TextView
    private lateinit var tvStatusUsb: TextView
    private lateinit var tvStatusGps: TextView
    private lateinit var tvStatusSensors: TextView
    private lateinit var tvStatusBattery: TextView
    private lateinit var tvRadarText: TextView
    private lateinit var tvLog: TextView
    private lateinit var textureView: TextureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Prefs.init(this)

        bindViews()
        loadPrefs()
        requestPermissions()
        startUsbService()
    }

    private fun bindViews() {
        etRobot = findViewById(R.id.etRobot)
        etMqttHost = findViewById(R.id.etMqttHost)
        etMqttTcpPort = findViewById(R.id.etMqttTcpPort)
        etHttpPort = findViewById(R.id.etHttpPort)
        etIntervalMs = findViewById(R.id.etIntervalMs)
        etCamMs = findViewById(R.id.etCamMs)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCamStart = findViewById(R.id.btnCamStart)
        btnCamStop = findViewById(R.id.btnCamStop)
        tvStatusMqtt = findViewById(R.id.tvStatusMqtt)
        tvStatusUsb = findViewById(R.id.tvStatusUsb)
        tvStatusGps = findViewById(R.id.tvStatusGps)
        tvStatusSensors = findViewById(R.id.tvStatusSensors)
        tvStatusBattery = findViewById(R.id.tvStatusBattery)
        tvRadarText = findViewById(R.id.tvRadarText)
        tvLog = findViewById(R.id.tvLog)
        textureView = findViewById(R.id.textureView)

        btnStart.setOnClickListener { startAll() }
        btnStop.setOnClickListener { stopAll() }
        btnCamStart.setOnClickListener { startCamera() }
        btnCamStop.setOnClickListener { stopCamera() }
    }

    private fun loadPrefs() {
        etRobot.setText(Prefs.string("robot", "robot-1"))
        etMqttHost.setText(Prefs.string("host", "192.168.1.100"))
        etMqttTcpPort.setText(Prefs.int("tcpPort", 1883).toString())
        etHttpPort.setText(Prefs.int("httpPort", 8080).toString())
        etIntervalMs.setText(Prefs.int("interval", 1000).toString())
        etCamMs.setText(Prefs.int("camMs", 1000).toString())
    }

    private fun savePrefs() {
        Prefs.put("robot", etRobot.text.toString().trim())
        Prefs.put("host", etMqttHost.text.toString().trim())
        Prefs.put("tcpPort", etMqttTcpPort.text.toString().toIntOrNull() ?: 1883)
        Prefs.put("httpPort", etHttpPort.text.toString().toIntOrNull() ?: 8080)
        Prefs.put("interval", etIntervalMs.text.toString().toIntOrNull() ?: 1000)
        Prefs.put("camMs", etCamMs.text.toString().toIntOrNull() ?: 1000)
    }

    private fun requestPermissions() {
        val wanted = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.CAMERA)
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), REQ_PERM)
        }
    }

    private fun startUsbService() {
        if (usb != null) return
        usb = UsbSerialManager(
            this,
            { msg -> ui.post { tvStatusUsb.text = "USB radar: $msg"; appendLog(msg) } },
            { data -> radarParser.onData(data) }
        )
        usb!!.start()
    }

    private fun startAll() {
        savePrefs()
        running = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        val robot = etRobot.text.toString().trim().ifEmpty { "robot-1" }
        val host = etMqttHost.text.toString().trim()
        val tcpPort = etMqttTcpPort.text.toString().toIntOrNull() ?: 1883
        val httpPort = etHttpPort.text.toString().toIntOrNull() ?: 8080
        val interval = (etIntervalMs.text.toString().toIntOrNull() ?: 1000).coerceAtLeast(500)

        sensors = SensorsCollector(this, state).also { it.start() }
        locationMonitor = LocationMonitor(this, state).also { it.start() }
        battery = BatteryMonitor(this, state).also { it.start() }

        val clientId = "rb-" + robot.replace(Regex("[^a-zA-Z0-9]"), "")
        mqtt = MqttManager(
            host, tcpPort, clientId,
            { s -> ui.post { tvStatusMqtt.text = "MQTT: $s"; appendLog("MQTT $s") } },
            { msg -> ui.post { appendLog(msg) } }
        ).also { it.start() }

        startUsbService()

        lateinit var loop: Runnable
        loop = Runnable {
            if (!running) return@Runnable
            val c = mqtt
            if (c == null) {
                ui.postDelayed(loop, 1000)
                return@Runnable
            }
            c.publish("robots/$robot/status", buildStatusJson(robot).toString())
            ui.post {
                updateViewFields()
                ui.postDelayed(loop, interval.toLong())
            }
        }
        publishLoop = loop
        ui.post(loop)
        appendLog("started '$robot' broker=$host:$tcpPort camPort=$httpPort")
    }

    private fun buildStatusJson(robot: String): JSONObject = JSONObject().apply {
        put("name", robot)
        put("lat", state.lat)
        put("long", state.lon)
        put("alt", state.alt)
        put("speed", state.speed)
        put("accuracy", state.accuracy)
        put("hasLocation", state.hasLocation)
        put("battery", state.battery)
        put("charging", state.charging)
        put("voltage", state.voltage)
        put("accel", JSONArray().put(state.ax).put(state.ay).put(state.az))
        put("gyro", JSONArray().put(state.gx).put(state.gy).put(state.gz))
        put("heading", Math.round(state.heading * 10) / 10.0)
        put("ts", System.currentTimeMillis())
    }

    private fun onRadarReport(report: RadarReport) {
        val robot = etRobot.text.toString().trim().ifEmpty { "robot-1" }
        val c = mqtt
        val json = JSONObject().apply {
            put("name", robot)
            put("state", report.state)
            put("dist", report.dist)
            put("ena", report.ena)
            val arr = JSONArray()
            for (t in report.targets) {
                arr.put(JSONObject().put("g", t.gate).put("t", t.type).put("d", t.dist).put("e", t.ena))
            }
            put("targets", arr)
            put("ts", System.currentTimeMillis())
        }
        c?.publish("robots/$robot/radar", json.toString())
        ui.post {
            tvRadarText.text = if (report.state == 0) "no target" else "TARGET @ ${report.dist} cm (energy ${report.ena})"
        }
    }

    private fun startCamera() {
        val host = etMqttHost.text.toString().trim()
        val httpPort = etHttpPort.text.toString().toIntOrNull() ?: 8080
        val robot = etRobot.text.toString().trim().ifEmpty { "robot-1" }
        val interval = etCamMs.text.toString().toIntOrNull() ?: 1000
        camera?.stop()
        camera = CameraController(this, textureView, { msg -> appendLog(msg) }, { msg -> appendLog(msg) })
        camera!!.start(robot, host, httpPort, interval.toLong())
        btnCamStart.isEnabled = false
        btnCamStop.isEnabled = true
    }

    private fun stopCamera() {
        camera?.stop()
        camera = null
        btnCamStart.isEnabled = true
        btnCamStop.isEnabled = false
    }

    private fun stopAll() {
        running = false
        publishLoop?.let { ui.removeCallbacks(it) }
        publishLoop = null
        mqtt?.stop()
        mqtt = null
        sensors?.stop()
        sensors = null
        locationMonitor?.stop()
        locationMonitor = null
        battery?.stop()
        battery = null
        usb?.stop()
        usb = null
        tvStatusMqtt.text = "MQTT: --"
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        appendLog("stopped")
    }

    private fun updateViewFields() {
        tvStatusGps.text = if (state.hasLocation)
            "GPS: ${"%.6f".format(state.lat)}, ${"%.6f".format(state.lon)}" else "GPS: --"
        tvStatusSensors.text = "IMU: a(${"%.1f".format(state.ax)},${"%.1f".format(state.ay)},${"%.1f".format(state.az)}) " +
            "g(${"%.2f".format(state.gx)},${"%.2f".format(state.gy)},${"%.2f".format(state.gz)}) " +
            "h=${"%.0f".format(state.heading)}"
        tvStatusBattery.text = if (state.battery >= 0)
            "Battery: ${state.battery}% ${if (state.charging) "(charging) " else ""}${state.voltage}V"
        else "Battery: --"
    }

    private fun appendLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val max = 40
        val lines = (tvLog.text.split("\n") + "$t $msg").toMutableList()
        while (lines.size > max) lines.removeAt(0)
        tvLog.text = lines.joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        startUsbService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        stopCamera()
    }
}