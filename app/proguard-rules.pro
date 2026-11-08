# Gson reads and writes these by reflection (rules + settings in SharedPreferences, GitHub's release answer)
-keep class io.github.brickystudios.funkblick.core.Rule { *; }
-keep class io.github.brickystudios.funkblick.core.RuleKind { *; }
-keep class io.github.brickystudios.funkblick.core.RuleField { *; }
-keep class io.github.brickystudios.funkblick.scan.Settings { *; }
-keep class io.github.brickystudios.funkblick.scan.GhRelease { *; }
-keep class io.github.brickystudios.funkblick.scan.GhAsset { *; }
-keepattributes Signature, *Annotation*
-keep class * extends com.google.gson.reflect.TypeToken
