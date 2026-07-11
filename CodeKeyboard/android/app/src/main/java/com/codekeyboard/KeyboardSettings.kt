package com.codekeyboard

import android.content.Context
import android.content.SharedPreferences

object KeyboardSettings {
    private const val PREFS_NAME = "codekeyboard_prefs"
    
    // Settings keys
    private const val KEY_FALLTHROUGH_BEHAVIOR = "fallthrough_behavior"
    
    // Fallthrough behavior options
    const val FALLTHROUGH_INSERT_TEXT = "insert_text"
    const val FALLTHROUGH_DO_NOTHING = "do_nothing"
    
    private var prefs: SharedPreferences? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getFallthroughBehavior(): String {
        return prefs?.getString(KEY_FALLTHROUGH_BEHAVIOR, FALLTHROUGH_INSERT_TEXT) 
            ?: FALLTHROUGH_INSERT_TEXT
    }
    
    fun setFallthroughBehavior(behavior: String) {
        prefs?.edit()?.putString(KEY_FALLTHROUGH_BEHAVIOR, behavior)?.apply()
    }
    
    fun shouldInsertTextOnFallthrough(): Boolean {
        return getFallthroughBehavior() == FALLTHROUGH_INSERT_TEXT
    }
}
