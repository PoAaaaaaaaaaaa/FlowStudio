# JNI 桥必须原样保留：C 函数名就是符号名，混淆一个字节都调不到。
-keepclasseswithmembernames class com.volt.flowstudio.audio.NativeEngine {
    native <methods>;
}
-keep class com.volt.flowstudio.audio.NativeEngine { *; }

# 自定义 View 由代码 new 出来，但保留以防万一
-keep class com.volt.flowstudio.ui.** { *; }

# ContentProvider 由系统按类名实例化
-keep class com.volt.flowstudio.session.ExportedFileProvider { *; }
-keep class com.volt.flowstudio.AudioService { *; }
-keep class com.volt.flowstudio.FlowApp { *; }
-keep class com.volt.flowstudio.MainActivity { *; }

# org.json 是平台实现，不用管
-dontwarn org.json.**
