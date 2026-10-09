package radar.robot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.exp

class SensorsCollector(private val context: Context, private val state: SensorState) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastHeadingTime = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    state.ax = event.values[0]
                    state.ay = event.values[1]
                    state.az = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    state.gx = event.values[0]
                    state.gy = event.values[1]
                    state.gz = event.values[2]
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, orientation)
                    val deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    val h = (deg + 360f) % 360f
                    val now = System.currentTimeMillis()
                    if (lastHeadingTime > 0) {
                        val dt = (now - lastHeadingTime) / 1000f
                        val alpha = (1f - exp(-dt / 0.5f)).coerceIn(0f, 1f)
                        state.heading = (state.heading + alpha * shortestAngle(state.heading, h) + 360f) % 360f
                    } else {
                        state.heading = h
                    }
                    lastHeadingTime = now
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun shortestAngle(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    fun start() {
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_UI)
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sm.unregisterListener(listener)
    }
}