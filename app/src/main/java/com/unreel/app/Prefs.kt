package com.unreel.app

import android.content.Context

object Prefs {
    private const val FILE = "unreel"
    const val HIDE_TAB = "hide_tab"
    const val BLOCK_VIEWER = "block_viewer"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, key: String): Boolean = prefs(context).getBoolean(key, true)

    fun set(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }
}
