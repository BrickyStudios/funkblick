# Gson reads and writes these by reflection (rules + settings in SharedPreferences, GitHub's release answer)
-keep class io.github.brickystudios.funkblick.core.Rule { *; }
-keep class io.github.brickystudios.funkblick.core.RuleKind { *; }
-keep class io.github.brickystudios.funkblick.core.RuleField { *; }
-keep class io.github.brickystudios.funkblick.scan.Settings { *; }
-keep class io.github.brickystudios.funkblick.scan.GhRelease { *; }
-keep class io.github.brickystudios.funkblick.scan.GhAsset { *; }
-keepattributes Signature, *Annotation*
-keep class * extends com.google.gson.reflect.TypeToken
# route files (core/Replay.kt): without these R8 drops the element types of RouteFile's lists, Gson fills them with
# maps, and opening a log fails with a ClassCastException (1.13/1.14: "This log can't be read" or a crash)
-keep class io.github.brickystudios.funkblick.core.RouteFile { *; }
-keep class io.github.brickystudios.funkblick.core.TrackPoint { *; }
-keep class io.github.brickystudios.funkblick.core.Estimate { *; }
