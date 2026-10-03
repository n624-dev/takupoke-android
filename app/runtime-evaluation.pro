# A separate instrumentation APK subclasses the target Application to reject
# network requests. Target R8 cannot see this subclass: retain its virtual hook
# and the repository constructor ABI instead of inlining the default transport.
# Only the explicit synthetic evaluation variant includes these rules.
-keep class jp.n624.takupoke.android.TakupokeApplication { *; }
-keepclassmembers class jp.n624.takupoke.android.AppRepository { public <init>(...); }
# The same separate APK implements this interface to deny all app HTTP.
# R8 must keep its interface shape and virtual dispatch as an external ABI.
-keep interface jp.n624.takupoke.android.Transport { *; }
# AndroidJUnitRunner calls the app-owned tracing dependency from its separate
# APK. Retain this public ABI only in the explicit evaluation variant.
-keep class androidx.tracing.Trace { *; }
# Keep only this cross-APK entry. SDK/core/coroutine/schema/oracle code is in
# the same optimized target, allowing normal R8 rewriting of those libraries.
-keep class jp.n624.takupoke.android.LiteRtRuntimeEvaluationHarness { *; }
