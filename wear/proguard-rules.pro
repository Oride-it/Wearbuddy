# Wearbuddy R8 规则。
# 数据层全部是显式 JSON 解析（org.json），没有反射；Compose / Coroutines /
# Lifecycle 自带 consumer 规则。这里只做少量补充。

# 崩溃堆栈可读性
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保持构建配置常量（关于页版本号）
-keep class com.wear.wearbuddy.BuildConfig { *; }

# org.json 在部分设备上由 boot classpath 提供
-dontwarn org.json.**
