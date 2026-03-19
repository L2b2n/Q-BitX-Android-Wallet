# Keep JNI classes
-keep class org.qbitx.wallet.crypto.DilithiumNative { *; }
-keep class org.qbitx.wallet.crypto.AddressUtils { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class org.qbitx.wallet.network.** { *; }
