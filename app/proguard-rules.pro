# JL-Mod Plus R8 rules
#
# JL-Mod Plus is not a conventional Android app: converted MIDlets are loaded from
# external DEX at runtime and resolve J2ME/vendor APIs by their original binary names.
# Those guest-visible APIs therefore form a runtime ABI. Keep their names and
# public/protected surface conservatively; R8 cannot see external MIDlet callers.

# J2ME APIs and vendor extensions exposed to guest MIDlets.
-keep public class com.j_phone.** { public protected *; }
-keep public class com.jblend.** { public protected *; }
-keep public class com.kddi.** { public protected *; }
-keep public class com.mascotcapsule.micro3d.v3.* { public protected *; }
-keep public class com.mexa.** { public protected *; }
-keep public class com.mot.iden.** { public protected *; }
-keep public class com.motorola.** { public protected *; }
-keep public class com.nokia.mid.** { public protected *; }
-keep public class com.samsung.util.** { public protected *; }
-keep public class com.siemens.mp.** { public protected *; }
-keep public class com.sonyericsson.accelerometer.** { public protected *; }
-keep public class com.sprintpcs.media.** { public protected *; }
-keep public class com.sun.midp.midlet.** { public protected *; }
-keep public class com.vodafone.** { public protected *; }
-keep public class javax.** { public protected *; }
-keep public class mmpp.media.** { public protected *; }

# MicroEmulator runtime reflection.
# ImplFactory derives implementation names by replacing a Delegate suffix with Impl,
# so both sides of these pairs must retain their binary names and callable surface.
-keep,allowoptimization public interface org.microemu.microedition.io.ConnectorDelegate { public protected *; }
-keep,allowoptimization public class org.microemu.microedition.io.ConnectorImpl { public protected *; }
-keep,allowoptimization public interface org.microemu.microedition.io.PushRegistryDelegate { public protected *; }
-keep,allowoptimization public class org.microemu.microedition.io.PushRegistryImpl { public protected *; }
-keep,allowoptimization public interface org.microemu.cldc.file.FileSystemRegistryDelegate { public protected *; }
-keep,allowoptimization public class org.microemu.cldc.file.FileSystemRegistryImpl { public protected *; }

# ConnectorImpl constructs org.microemu.cldc.<protocol>.Connection names from URL schemes
# and instantiates them reflectively. Keep only those dynamic protocol entry points,
# rather than the entire org.microemu implementation tree.
-keep,allowoptimization public class org.microemu.cldc.**.Connection { public protected *; }

# Gson 2.14, FFmpegKit, and ACRA ship their own consumer ProGuard/R8 configuration.
# Keep dependency-specific reflection/JNI rules upstream instead of duplicating broad
# package-wide keeps here.

# Keep the existing compact obfuscation dictionary for application-internal code.
-obfuscationdictionary dictionary.pro
-classobfuscationdictionary dictionary.pro
