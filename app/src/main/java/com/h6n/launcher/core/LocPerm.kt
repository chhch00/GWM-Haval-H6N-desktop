package com.h6n.launcher.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 定位权限（状态栏天气按 GPS 判城市要用）。
 *
 * 车机是 `targetSdk 27`，定位属于**危险权限**，装完必须运行时授权一次，
 * 否则 `LocationManager` 直接抛 SecurityException，天气只能退回 IP 定位
 * （IP 经常落到运营商出口城市，报出来就是「隔壁市」的天气）。
 *
 * 这里只管「查 / 要 / 只自动问一次」三件事，不保存业务状态。
 */
object LocPerm {

    const val REQ = 1902
    private const val SP = "h6_loc"
    private const val K_ASKED = "asked"

    fun granted(c: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            c, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) return true
        return ContextCompat.checkSelfPermission(
            c, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 精确定位（GPS provider 必须 FINE）。 */
    fun fine(c: Context): Boolean =
        ContextCompat.checkSelfPermission(
            c, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun request(act: Activity) {
        val need = ArrayList<String>(2)
        if (!fine(act)) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(
                act, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (need.isEmpty()) return
        runCatching { act.requestPermissions(need.toTypedArray(), REQ) }
    }

    /** 冷启动自动问过一次就不再打扰（用户拒绝后仍可在「设置 → 天气」手动授权）。 */
    fun shouldAskOnBoot(c: Context): Boolean =
        !granted(c) && !c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_ASKED, false)

    fun markAsked(c: Context) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_ASKED, true).apply()
    }
}
