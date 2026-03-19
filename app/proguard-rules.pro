# Keep JNI classes
-keep class org.qbitx.wallet.crypto.DilithiumNative { *; }
-keep class org.qbitx.wallet.crypto.AddressUtils { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class org.qbitx.wallet.network.** { *; }

# Tink / security-crypto missing annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**

# Keep data classes used by Gson
-keep class org.qbitx.wallet.data.** { *; }
