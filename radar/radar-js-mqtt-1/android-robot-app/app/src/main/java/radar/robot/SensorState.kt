package radar.robot

/**
 * Latest snapshot of the phone sensor state.
 * All fields are written by worker threads and read by the MQTT publisher.
 */
class SensorState {
    @Volatile var lat: Double = 0.0
    @Volatile var lon: Double = 0.0
    @Volatile var alt: Double = 0.0
    @Volatile var speed: Float = 0f
    @Volatile var accuracy: Float = 0f
    @Volatile var hasLocation: Boolean = false

    @Volatile var battery: Int = -1
    @Volatile var charging: Boolean = false
    @Volatile var voltage: Float = 0f

    @Volatile var ax: Float = 0f
    @Volatile var ay: Float = 0f
    @Volatile var az: Float = 0f

    @Volatile var gx: Float = 0f
    @Volatile var gy: Float = 0f
    @Volatile var gz: Float = 0f

    @Volatile var heading: Float = 0f

    @Volatile var ts: Long = System.currentTimeMillis()
}