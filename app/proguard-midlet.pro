# Legacy standalone MIDlet sample R8 rules.
#
# The midlet flavor is disabled by default and retained as reference/porting support.
# Keep the dynamic MicroEmulator and MIDlet contracts below if that flavor is re-enabled.

-keep public class org.microemu.** { public protected *; }
-keep class io.github.h3nb.jlmodplus.util.SparseIntArrayAdapter { *; }
# Keep the BuildConfig
-keep class io.github.h3nb.jlmodplus.BuildConfig { *; }

-keep class io.github.h3nb.jlmodplus.crashes.models.* { *; }

# Preserve all public midlets.

-keep public class * extends javax.microedition.midlet.MIDlet

# Preserve all native method names and the names of their classes.

-keepclasseswithmembernames class * {
    native <methods>;
}

# Your midlet may contain more items that need to be preserved;
# typically classes that are dynamically created using Class.forName:

# -keep public class mypackage.MyClass
# -keep public interface mypackage.MyInterface
# -keep public class * implements mypackage.MyInterface
