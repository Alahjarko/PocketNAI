# 保留 kotlinx.serialization 生成的序列化器。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class net.pocketnai.**$$serializer { *; }
-keepclasseswithmembers class net.pocketnai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp 在 Android 上会尝试引用可选的 Conscrypt / BouncyCastle，缺失即可。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
