# R8 rules for the Symbiosis additions.
#
# WHY THESE ARE NEEDED AT ALL
#
#   R8 removes and renames anything it cannot see being used. It reads Kotlin
#   and Java call sites - and nothing else. Three kinds of code in this fork
#   are reached by NAME at runtime, which R8 has no way to know about:
#
#     * classes named in AndroidManifest.xml (the probe service)
#     * fragments the navigation graph instantiates by class name
#     * anything the native side calls back into
#
#   Without a rule, R8 renames or deletes them and the app fails at run time
#   with ClassNotFoundException - in a release build only, which is exactly
#   the build a person would be given.
#
#   The default proguard-android-optimize.txt already keeps classes that
#   declare native methods (-keepclasseswithmembernames ... native), so
#   KenjiBridge's own name survives on its own. That is checked, not assumed:
#   the rule is quoted in the AGP defaults. Everything else here is not
#   covered by it.

# The probe service. Named as a string in the manifest and started by the
# system in the :kenji process; R8 sees no call site at all.
-keep class org.yuzu.yuzu_emu.utils.KenjiProbeService { *; }
-keep class org.yuzu.yuzu_emu.activities.KenjiPlayerActivity { *; }

# Screens the navigation graph creates by name.
#
# The launcher and the manual matter most: the launcher is the start
# destination, so a renamed class means the app opens on
# ClassNotFoundException instead of a library, and the manual is the screen
# that explains where everything is.
-keep class org.yuzu.yuzu_emu.fragments.EnginesFragment { *; }
-keep class org.yuzu.yuzu_emu.fragments.NativeLauncherFragment { *; }
-keep class org.yuzu.yuzu_emu.fragments.ManualFragment { *; }
# The tiles' decisions and the manual text, reached only from those screens.
-keep class org.yuzu.yuzu_emu.utils.NativeLibraryPresenter { *; }
-keep class org.yuzu.yuzu_emu.utils.NativeLibraryPresenter$* { *; }
-keep class org.yuzu.yuzu_emu.utils.Manual { *; }
-keep class org.yuzu.yuzu_emu.utils.Manual$* { *; }

# The bridge, belt and braces. The native side resolves
# Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_* by symbol name, so the class
# name and the method names both have to survive.
-keepclasseswithmembernames class org.yuzu.yuzu_emu.utils.KenjiBridge {
    native <methods>;
}
-keep class org.yuzu.yuzu_emu.utils.KenjiBridge { *; }

# Engine bookkeeping. EngineLoader.Engine is stored by its `id` string, so a
# renamed enum entry would quietly read back as "not the engine you chose" -
# and the fallback would send everyone to Eden with no explanation.
-keep class org.yuzu.yuzu_emu.utils.EngineLoader { *; }
-keep class org.yuzu.yuzu_emu.utils.EngineLoader$Engine { *; }
-keep class org.yuzu.yuzu_emu.utils.EngineLoader$State { *; }
-keep class org.yuzu.yuzu_emu.utils.EngineLoader$State$* { *; }
-keep class org.yuzu.yuzu_emu.utils.EngineDownloader { *; }
-keep class org.yuzu.yuzu_emu.utils.EnginePreference { *; }
-keep class org.yuzu.yuzu_emu.utils.Spaces { *; }

# NativeSymbiosis is the other JNI surface - 54 entry points resolved by name
# from libyuzu-android.so.
-keep class org.yuzu.yuzu_emu.utils.NativeSymbiosis { *; }

# Keep line numbers so a crash report from a release build is still readable.
# Costs a few kilobytes and saves an afternoon.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class org.kenjinx.android.KenjinxNative { *;
}
