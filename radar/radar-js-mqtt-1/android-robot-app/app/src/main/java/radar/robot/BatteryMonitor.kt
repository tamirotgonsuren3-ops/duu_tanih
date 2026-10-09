package radar.robot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class BatteryMonitor(private val context: Context, private val state: SensorState) {

    private var receiver: BatteryReceiver? = null

    private class BatteryReceiver(val state: SensorState) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) state.battery = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            state.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val v = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (v > 0) state.voltage = v / 1000f
        }
    }

    fun start() {
        receiver = BatteryReceiver(state)
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun stop() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) {}
        }
        receiver = null
    }
}