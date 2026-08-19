# JL-Mod Plus R8 rules
#
# JL-Mod Plus is not a conventional Android app: converted MIDlets are loaded from
# external DEX at runtime and resolve J2ME/vendor APIs by their original binary names.
# Those guest-visible APIs therefore form a runtime ABI. Keep their names and
# public/protected surface, while still allowing R8 to optimize method bodies.

# J2ME APIs and vendor extensions exposed to guest MIDlets.
-keep,allowoptimization public class com.j_phone.** { public protected *; }
-keep,allowoptimization public class com.jblend.** { public protected *; }
-keep,allowoptimization public class com.kddi.** { public protected *; }
-keep,allowoptimization public class com.mascotcapsule.micro3d.v3.* { public protected *; }
-keep,allowoptimization public class com.mexa.** { public protected *; }
-keep,allowoptimization public class com.mot.iden.** { public protected *; }
-keep,allowoptimization public class com.motorola.** { public protected *; }
-keep,allowoptimization public class com.nokia.mid.** { public protected *; }
-keep,allowoptimization public class com.samsung.util.** { public protected *; }
-keep,allowoptimization public class com.siemens.mp.** { public protected *; }
-keep,allowoptimization public class com.sonyericsson.accelerometer.** { public protected *; }
-keep,allowoptimization public class com.sprintpcs.media.** { public protected *; }
-keep,allowoptimization public class com.sun.midp.midlet.** { public protected *; }
-keep,allowoptimization public class com.vodafone.** { public protected *; }
-keep,allowoptimization public class javax.** { public protected *; }
-keep,allowoptimization public class mmpp.media.** { public protected *; }

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

# Gson 2.9.x reflection.
# Serialized fields use @SerializedName, so their wire names must remain stable.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson invokes these constructors/adapters reflectively from directly referenced model
# classes / @JsonAdapter metadata. Keep the reflective entry points but allow optimization.
-keepclassmembers,allowoptimization class ru.playsoftware.j2meloader.config.ProfileModel {
    public <init>();
}
-keepclassmembers,allowoptimization class ru.playsoftware.j2meloader.config.ShaderInfo {
    public <init>();
}
-keep,allowoptimization class ru.playsoftware.j2meloader.util.SparseIntArrayAdapter {
    public <init>();
    public *;
}

# Required by Gson versions older than 2.11 when R8 full mode is enabled.
-keepattributes Signature
-keep,allowobfuscation,allowshrinking,allowoptimization class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation,allowshrinking,allowoptimization class * extends com.google.gson.reflect.TypeToken

# JNI/reflection-heavy dependencies. Their externally resolved names remain fixed, but
# method bodies may still be optimized. Do not shrink or obfuscate these without a
# dependency-specific runtime audit.
-keep,allowoptimization class com.arthenica.mobileffmpeg.** { *; }
-keep,allowoptimization public class org.acra.** { public protected *; }

# Keep the existing compact obfuscation dictionary for application-internal code.
-obfuscationdictionary dictionary.pro
-classobfuscationdictionary dictionary.pro
