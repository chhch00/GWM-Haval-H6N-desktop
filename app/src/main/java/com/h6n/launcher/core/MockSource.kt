package com.h6n.launcher.core

import com.h6n.launcher.domain.ConfigSnapshot

/**
 * 用 `ui_config.mock` 造一帧车身信号。
 *
 * 为什么需要它：车身 Binder 的读取通道还没接（要逐个核对 itemId），
 * 但卡片排版/配色/单位必须先看效果。打开 `mock.enabled` 后桌面显示的就是
 * 这套固定值，关掉就回到真实信号（读不到显示 "--"）。
 */
object MockSource {

    fun from(snap: ConfigSnapshot): CarSignal {
        val tire = snap.list("mock.tireKpa")
            ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val tireTemp = snap.list("mock.tireTempC")
            ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val doors = snap.list("mock.doors")
            ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        // 静态模拟帧的车窗/天窗： SimSignals 打开时会再盖一层（见 SignalHub.decorate）
        val windows = snap.list("mock.windows")
            ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        val roof = snap.int("mock.sunroof", -1)
        val speed = snap.int("mock.speedKmh", 0)
        val range = snap.int("mock.range", -1)
        val fuel = snap.int("mock.fuel", -1)
        val voltRaw = snap.int("mock.voltRaw", -1)

        return CarSignal(
            speedKmh = speed,
            avgSpeed = snap.int("mock.speedMin", -1),
            maxSpeed = snap.int("mock.speedMax", -1),
            gear = when (snap.int("mock.gear", 0)) {
                0 -> "P"
                1 -> "R"
                2 -> "N"
                3 -> "D"
                else -> "?"
            },
            driveMode = snap.int("mock.driveMode", -1),
            coolantC = snap.int("mock.waterC", -999).takeIf { it > -100 }?.toFloat() ?: Float.NaN,
            atfC = snap.int("mock.atfC", -999).takeIf { it > -100 }?.toFloat() ?: Float.NaN,
            tireKpa = tire,
            tireTempC = tireTemp,
            rangeKm = range,
            fuelPct = if (fuel >= 0) (fuel * 100 / 55) else -1,
            fuelLiters = fuel.toFloat(),
            volt = if (voltRaw > 0) voltRaw / 100f else Float.NaN,
            battPct = 85,
            sulfurPct = 12,
            mileageKm = snap.int("mock.mileage", -1),
            oilLifePct = 62,
            maintKmLeft = 4300,
            windowPos = windows,
            sunroof = roof,
            doors = doors,
            btConnected = snap.bool("mock.bt", false),
            signalLevel = 3,
            wifiLevel = 2,
            engineOn = true,
            source = "mock"
        )
    }
}
