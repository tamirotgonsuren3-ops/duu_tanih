package radar.robot

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "robot_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun string(key: String, def: String): String = sp.getString(key, def) ?: def
    fun int(key: String, def: Int): Int = sp.getInt(key, def)
    fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    fun put(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
}