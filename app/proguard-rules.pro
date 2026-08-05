# The AIDL stubs are reached over Binder, and ZXing's encoder is loaded by name
# in a couple of places. Neither is minified in this project today, but keeping
# the rules here means turning minification on later does not silently break
# printing.
-keep class woyou.aidlservice.jiuiv5.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
