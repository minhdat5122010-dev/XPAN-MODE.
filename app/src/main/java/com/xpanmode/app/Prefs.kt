package com.xpanmode.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("xpan_mode", Context.MODE_PRIVATE)

    /** true = giao diện Hasselblad (xám đậm), false = giao diện tối giản (đen + nút cam) */
    var classic: Boolean
        get() = sp.getBoolean("classic", true)
        set(v) = sp.edit().putBoolean("classic", v).apply()

    var filter: Int
        get() = sp.getInt("filter", 0)
        set(v) = sp.edit().putInt("filter", v).apply()

    var entryKey: String?
        get() = sp.getString("entry", null)
        set(v) = sp.edit().putString("entry", v).apply()

    var saveRaw: Boolean
        get() = sp.getBoolean("raw", false)
        set(v) = sp.edit().putBoolean("raw", v).apply()

    var keepOriginal: Boolean
        get() = sp.getBoolean("keep_original", false)
        set(v) = sp.edit().putBoolean("keep_original", v).apply()

    var autoFrame: Boolean
        get() = sp.getBoolean("auto_frame", false)
        set(v) = sp.edit().putBoolean("auto_frame", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var deviceName: String
        get() = sp.getString("device_name", "") ?: ""
        set(v) = sp.edit().putString("device_name", v).apply()
}
