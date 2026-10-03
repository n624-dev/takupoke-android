-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**
# LiteRT 0.17.1 has no consumer keep rules. Its JNI library resolves Kotlin
# DTOs, exception constructors and callback methods by literal class/member
# names (including InputData, BenchmarkInfo and LiteRtLmJniException).
-keep class com.google.ai.edge.litertlm.** { *; }
