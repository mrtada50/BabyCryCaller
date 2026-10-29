package com.babycry.caller

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("p", Context.MODE_PRIVATE)

    fun number(c: Context): String = sp(c).getString("number", "") ?: ""
    fun setNumber(c: Context, v: String) = sp(c).edit().putString("number", v).apply()

    fun status(c: Context): String = sp(c).getString("status", "") ?: ""
    fun setStatus(c: Context, v: String) = sp(c).edit().putString("status", v).apply()

    fun persistent(c: Context): Boolean = sp(c).getBoolean("persistent", false)
    fun setPersistent(c: Context, v: Boolean) = sp(c).edit().putBoolean("persistent", v).apply()

    fun lastStop(c: Context): String = sp(c).getString("lastStop", "") ?: ""
    fun setLastStop(c: Context, v: String) = sp(c).edit().putString("lastStop", v).apply()
}
