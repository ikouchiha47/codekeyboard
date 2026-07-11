package com.codekeyboard

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class SettingsModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "SettingsModule"

    init {
        KeyboardSettings.init(reactContext)
    }

    @ReactMethod
    fun getString(key: String, callback: com.facebook.react.bridge.Callback) {
        callback.invoke(KeyboardSettings.getString(key))
    }

    @ReactMethod
    fun setString(key: String, value: String) {
        KeyboardSettings.setString(key, value)
    }

    @ReactMethod
    fun getBoolean(key: String, callback: com.facebook.react.bridge.Callback) {
        callback.invoke(KeyboardSettings.getBoolean(key))
    }

    @ReactMethod
    fun setBoolean(key: String, value: Boolean) {
        KeyboardSettings.setBoolean(key, value)
    }

    @ReactMethod
    fun getInt(key: String, callback: com.facebook.react.bridge.Callback) {
        callback.invoke(KeyboardSettings.getInt(key))
    }

    @ReactMethod
    fun setInt(key: String, value: Int) {
        KeyboardSettings.setInt(key, value)
    }
}
