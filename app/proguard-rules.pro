# =====================================================================
# 6.0.92：开启 R8（release isMinifyEnabled = true）
#
# 取舍原则（贴合本工程既有红线）：
#   1) 类名一律保留 —— Manifest 组件、ComponentName、无障碍 XML、efs 插桩
#      核查、友盟崩溃栈排查全部按类名定位；方法名 / 字段名仍然混淆，
#      反编译后能看到的只是一堆 a()/b()/c()，逻辑照样要一行行推。
#   2) 不开 shrinkResources —— AmapNavi 用 getIdentifier("lane_pdf_$icon")
#      在运行时拼资源名取 drawable，资源收缩会把这些车道图标当「无用」删掉。
#   3) 关 fullMode（见 gradle.properties）—— 工程大量依赖系统隐藏 API 反射、
#      无障碍服务、通知监听、Binder；fullMode 的类合并 / 接口移除 / 无反射假设
#      对这类应用风险过高，收益不值。
#   4) 保留行号 —— 车机崩溃基本只能靠友盟栈，必须能对回源码行。
#
# 已核实：本工程所有反射（SystemProperties / Car / ServiceManager /
# MediaController / StorageVolume / StatusBarManager）均指向系统类，系统类不参与
# 混淆，因此不存在「反射自家类被改名」的隐患。
# =====================================================================

# ---- 本应用：保留类名，成员仍可混淆 ----
-keepnames class com.h6n.launcher.**

# ---- 入口与关键结构：完整保留 ----
# H6NApplication：efs 插件插 attachBaseContext / onTraceApp 的位置，结构不能动
# ScaledActivity：efs blackList 排除的对象，且是 Main/Settings 的父类，禁止合并
# 其余为 Manifest 声明组件 + 系统按类名/ComponentName 定位的服务
#
# MainActivity / SettingsActivity 刻意 **不** 在这里 keep：
#   它们由下面的「* extends android.app.Activity」保住类名与构造器，生命周期 /
#   dispatchKeyEvent / onKeyDown 等是父类 override，R8 必须保留签名；
#   而类内部私有方法与 Kotlin 合成 access$ 方法会被正常混淆 / 内联 ——
#   这才是真正需要加固的核心逻辑（各 5000+ 行），全 keep 等于白加。
-keep class com.h6n.launcher.H6NApplication { *; }
-keep class com.h6n.launcher.ui.common.ScaledActivity { *; }
-keep class com.h6n.launcher.ui.main.NavOverlayService { *; }
-keep class com.h6n.launcher.ui.main.H6A11yService { *; }
-keep class com.h6n.launcher.core.H6MediaListener { *; }
-keep class com.h6n.launcher.core.BootReceiver { *; }

# ---- Android 组件基类：构造与生命周期由系统反射调用 ----
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---- 属性：栈迹 / 泛型 / 注解 ----
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin 元数据（DataStore / 协程内部会读）----
-keep class kotlin.Metadata { *; }

# ========== 以下沿用 6.0.91 及以前的规则 ==========
-keep class com.h6n.launcher.core.** { *; }
-keep class com.h6n.launcher.domain.** { *; }
-keepnames class * extends android.os.Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ========== 友盟统计SDK混淆规则 ==========
-keep class com.umeng.** {*;}
-keep class org.repackage.** {*;}
-keep class com.uyumao.** { *; }
-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}

# ========== 友盟APM SDK混淆规则 ==========
-keep class com.uc.** { *; }
-keep class com.efs.** { *; }
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ========== 6.0.93 加壳：壳类不在 R8 输入里 ==========

# ========== 缺失类警告：不中断构建 ==========
# 友盟 SDK 内部引用了部分可选/厂商类，APK 里并不存在，属正常情况。
-dontwarn com.umeng.**
-dontwarn com.efs.**
-dontwarn com.uc.**
-dontwarn com.uyumao.**
-dontwarn org.repackage.**
-dontwarn androidx.**
-dontwarn kotlinx.**
