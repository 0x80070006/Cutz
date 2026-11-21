# AndroidSVG uses reflection-free parsing; keep its public API to be safe with R8 full mode.
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
