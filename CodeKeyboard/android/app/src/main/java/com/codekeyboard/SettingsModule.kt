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
    fun getFallthroughBehavior(): String {
        return KeyboardSettings.getFallthroughBehavior()
    }

    @ReactMethod
    fun setFallthroughBehavior(behavior: String) {
        KeyboardSettings.setFallthroughBehavior(behavior)
    }
}
