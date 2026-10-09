package radar.robot

import org.json.JSONObject

data class Target(val gate: Int, val type: Int, val dist: Int, val ena: Int)
data class RadarReport(val state: Int, val dist: Int, val ena: Int, val targets: List<Target>)

/**
 * Decodes the JSON lines sent by the Arduino on the USB serial port:
 *   RADAR {"state":1,"dist":120,"ena":80,"targets":[{"g":1,"t":0,"d":120,"e":80}]}
 */
class RadarFrameParser(private val listener: (RadarReport) -> Unit) {

    private val sb = StringBuilder()
    private var last: RadarReport? = null

    fun lastReport(): RadarReport? = last

    fun onData(data: ByteArray) {
        for (b in data) {
            if (b.toInt() == '\n'.toInt()) {
                val line = sb.toString().trim()
                sb.setLength(0)
                handleLine(line)
            } else {
                sb.append(b.toChar())
            }
        }
    }

    private fun handleLine(line: String) {
        if (!line.startsWith("RADAR ")) return
        try {
            val obj = JSONObject(line.substring(6))
            val state = obj.optInt("state", 0)
            val dist = obj.optInt("dist", 0)
            val ena = obj.optInt("ena", 0)
            val targetsJson = obj.optJSONArray("targets")
            val targets = ArrayList<Target>()
            if (targetsJson != null) {
                for (i in 0 until targetsJson.length()) {
                    val o = targetsJson.getJSONObject(i)
                    targets.add(Target(o.optInt("g"), o.optInt("t"), o.optInt("d"), o.optInt("e")))
                }
            }
            val report = RadarReport(state, dist, ena, targets)
            last = report
            listener(report)
        } catch (e: Exception) { /* malformed line, ignore */ }
    }
}