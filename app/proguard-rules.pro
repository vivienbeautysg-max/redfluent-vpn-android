# RedFluent VPN Android — R8 / ProGuard 规则
#
# release 构建开了 isMinifyEnabled + isShrinkResources，build.gradle.kts 引用了本文件，
# 但文件一直不存在 → release 变体从来没构建成功过（2026-09-11 才发现）。
#
# libbox.aar 自带 consumer 规则（-keep class go.** / io.nekohasekai.**），AGP 会自动合并，
# 所以引擎本身的绑定类不用在这里再写一遍。

# 我们自己实现 libbox 接口的桥接类：这些方法是由 Go 侧回调进来的，
# 对 Java/Kotlin 侧的静态分析看起来"没人调用"，不 keep 会被 R8 当死代码删掉。
-keep class com.redfluent.vpn.vpn.** { *; }

# kotlinx.serialization：@Serializable 的 serializer 是编译期生成 + 运行时按名字查找的。
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.redfluent.vpn.data.**$$serializer { *; }
-keepclassmembers class com.redfluent.vpn.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.redfluent.vpn.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 崩溃日志要能还原到源码行号。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
