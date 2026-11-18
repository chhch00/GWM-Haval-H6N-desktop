package com.h6n.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.core.AdbGrant
import com.h6n.launcher.core.AmapNavi
import com.h6n.launcher.core.AppIconCache
import com.h6n.launcher.core.AutoClose
import com.h6n.launcher.core.DoorAvm
import com.h6n.launcher.core.EdgeGesture
import com.h6n.launcher.core.FloatButton
import com.h6n.launcher.core.HangWatchdog
import com.h6n.launcher.core.PerfStats
import com.h6n.launcher.core.SceneAnnouncer
import com.h6n.launcher.core.SignalHub
import com.h6n.launcher.core.SimSignals
import com.h6n.launcher.core.Storage
import com.h6n.launcher.core.TireAlert
import com.h6n.launcher.core.UpdateManager
import com.h6n.launcher.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级装配点。
 *
 * 刻意不引入 DI 框架：车机端依赖图很小（配置、车身信号、媒体、看门狗），
 * 手写一个 Application 容器比 Hilt 的注解处理 + 运行时代理更省内存，
 * 也避免 AGP/KSP 版本与车机 ROM 的兼容坑。
 */
class H6NApplication : Application() {

    /** 进程级协程作用域：SupervisorJob 保证一个子任务失败不会连坐其他模块。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val config: ConfigRepository by lazy { ConfigRepository.get(this) }

    override fun onCreate() {
        super.onCreate()
        // 0) 日志出口先立起来（6.0.102）：后面每一步的 Log.x 都会同步落盘到
        //    getExternalFilesDir("logs")/h6n.log，车机上出问题可直接导出给人看。
        //    放最前面是因为"启动黑屏"这类问题恰恰发生在后面的装配步骤里。
        runCatching { com.h6n.launcher.core.H6Log.init(this) }

        // 进程运行时长打点（设置页「运行时状态」显示 2h13m 这类读数）
        PerfStats.attach()

        // 1.5) 无线 ADB 的「上次已打通」不能跨进程沿用（6.0.106）：
        //      adbd 的 TCP 端口不跨重启，静默沿用会让 auto() 永远判「已通」而不再自连。
        runCatching { AdbGrant.onProcessStart(this) }

        // 1) 配置：先挂收集器，读盘与旧配置迁移都在 IO 线程完成，不占启动主线程
        config.attach(appScope)

        // 1.1) 图标缓存拿到 Resources：自适应图标要裁掉 108dp 画布外的出血区
        AppIconCache.attach(this)

        // 2) 车身信号：走 ICarInfo Binder，读不到时自动降级为空信号
        SignalHub.attach(this, appScope)

        // 2.1) 门窗模拟信号：默认关；打开时只覆盖 windowPos / sunroof / doors，
        //      手机没接车机也能验证车模的琥珀车窗与车门开合动画。
        SimSignals.attach(this, appScope)

        // 2.2) 场景播报（旧版 SceneEngine 的 Kotlin 移植）：车门开关 / 换挡 / 熄火未关车窗。
        //      挂在信号总线上，Binder、模拟源写进来的每一帧都会过一遍判定。
        SceneAnnouncer.attach(this, appScope)

        // 2.2.1) 胎压告警：低于 240kPa / 四轮极差 15kPa 时弹窗 + 播报（6.0.65）。
        //      和场景播报分开是因为它要弹窗（需要 Activity），而 SceneAnnouncer 只管声音；
        //      播报走的仍是 SceneAnnouncer 的 TTS 与 AudioRoute 通道，听感是一致的。
        TireAlert.attach(this, appScope)

        // 2.2.2) 开门自动 360（6.0.69）：非主驾车门打开 → 弹后视画面 → 5 秒后自动关闭。
        //      独立订阅信号总线，不受「播报提醒」总开关影响（那是声音，这是画面）。
        //      360 通道是 SVPAS 的裸广播，不出网、不写 Binder，详见 DoorAvm 的类注释。
        DoorAvm.attach(this, appScope)

        // 2.2.3) 熄火自动关窗（6.0.103）：熄火（+ 锁车）后关四窗，天窗可选。
        //      写的是座舱模块的 VR 请求条目（没有返回值），执行后必回读开度裁决；
        //      默认关闭，设置 → 车辆 → 熄火自动关窗 里开，先点「试关窗」确认值域。
        AutoClose.attach(this, appScope)

        // 2.3) 在线更新与存储守护：读出厂更新源、清掉装完留下的安装包
        //      Storage 必须先 attach（UpdateManager 启动线程里会调 Storage.cleanOldApks）
        Storage.attach(this)
        UpdateManager.attach(this)

        // 2.4) 高德导航信息（红绿灯 / 车道）：只在 Application 里注册一次广播接收器。
        //      桌面是 HOME 进程，收不到包时设置页的「广播状态」会是 0 条，便于排查。
        AmapNavi.start(this)

        // 2.5) 自动化：悬浮按钮 + 侧边手势（6.0.47）。
        //      此刻无障碍服务通常还没连上，窗口会先按应用级 2038 挂；
        //      H6A11yService.onServiceConnected 会再调一次 onHostChanged 改用 2032 重挂。
        runCatching { FloatButton.sync(this) }
        runCatching { EdgeGesture.sync(this) }

        // 3) 主线程卡死看门狗（旧版 HangGuard 的 Kotlin 等价物）
        HangWatchdog.install(this)
    }

    /**
     * 系统内存降级回调（v6.0.45）。
     *
     * 桌面是 HOME 进程，几乎不会被回收，但整机内存紧张时系统仍会给它发 onTrimMemory。
     * 这里主动把图标缓存让出去（5MB 上限 → 2MB → 1MB → 全清）：下次重建应用墙只是
     * 重新解析几十枚图标，比被 LMK 整进程杀掉、HOME 重新启动便宜得多。
     * 缓存淘汰不 recycle Drawable —— 屏幕上正在显示的图标可能还引用着它。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> AppIconCache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> AppIconCache.trimTo(1 * 1024 * 1024)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> AppIconCache.trimTo(2 * 1024 * 1024)
        }
    }

    private companion object {
        const val TAG = "H6NApplication"
    }
}
