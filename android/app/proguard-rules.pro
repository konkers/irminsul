-keep class com.google.protobuf.** { *; }
-keep class com.example.irminsul.protobuf.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}