package com.codekeyboard

import android.content.Context
import android.content.SharedPreferences

object KeyboardSettings {
    private const val PREFS_NAME = "codekeyboard_prefs"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getString(key: String, default: String = ""): String {
        return prefs?.getString(key, default) ?: default
    }

    fun setString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs?.getBoolean(key, default) ?: default
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getInt(key: String, default: Int = 0): Int {
        return prefs?.getInt(key, default) ?: default
    }

    fun setInt(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
    }
}
