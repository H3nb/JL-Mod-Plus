import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Standalone R&D fixture, not JL-Mod Plus production code. Compiled with --release 8. */
public final class LocatorProbe {
    private static final String GAME = "rndguest.Game";
    private static final String PLAYER = "rndguest.Game$Player";
    private static int checks;

    interface Edge {}
    static final class FieldEdge implements Edge {
        final String declaringClass, name, type;
        FieldEdge(String declaringClass, String name, String type) {
            this.declaringClass = declaringClass; this.name = name; this.type = type;
        }
    }
    static final class IndexEdge implements Edge {
        final int index;
        IndexEdge(int index) { this.index = index; }
    }
    static final class Descriptor {
        final String jarSha256;
        final String staticRootClass; // null: MIDlet root
        final List<Edge> edges;
        Descriptor(String jarSha256, String staticRootClass, Edge... edges) {
            this.jarSha256 = jarSha256; this.staticRootClass = staticRootClass;
            this.edges = Arrays.asList(edges);
        }
    }
    static final class Runtime {
        final String jarSha256;
        final ClassLoader guestLoader;
        final Object root;
        final Set<String> initializedClasses;
        Runtime(String jarSha256, ClassLoader guestLoader, Object root, String... initializedClasses) {
            this.jarSha256 = jarSha256; this.guestLoader = guestLoader;
            this.root = root; this.initializedClasses = new HashSet<>(Arrays.asList(initializedClasses));
        }
    }
    static final class Binding {
        final Object owner;
        final Field field;
        Binding(Object owner, Field field) { this.owner = owner; this.field = field; }
        int read() throws Exception { return field.getInt(owner); }
    }
    static final class ResolveException extends Exception {
        ResolveException(String message) { super(message); }
    }

    private static Field requireField(Class<?> type, FieldEdge edge) throws Exception {
        Class<?> cursor = type;
        while (cursor != null && !cursor.getName().equals(edge.declaringClass)) {
            cursor = cursor.getSuperclass();
        }
        if (cursor == null) throw new ResolveException("Declaring class mismatch");
        Field field;
        try { field = cursor.getDeclaredField(edge.name); }
        catch (NoSuchFieldException e) { throw new ResolveException("Missing field"); }
        if (!field.getType().getName().equals(edge.type)) {
            throw new ResolveException("Field type mismatch");
        }
        field.setAccessible(true);
        return field;
    }

    private static Binding resolve(Runtime runtime, Descriptor descriptor) throws Exception {
        if (!runtime.jarSha256.equals(descriptor.jarSha256)) {
            throw new ResolveException("Source JAR mismatch");
        }
        if (descriptor.edges.isEmpty() || descriptor.edges.size() > 24) {
            throw new ResolveException("Invalid traversal depth");
        }
        Object current;
        if (descriptor.staticRootClass == null) {
            current = runtime.root;
            if (current == null || current.getClass().getClassLoader() != runtime.guestLoader) {
                throw new ResolveException("No trusted guest MIDlet root");
            }
        } else {
            // This registry is populated only after successful guest class initialization.
            // Do not call Class.forName(..., true, ...), and do not discover unknown statics.
            if (!runtime.initializedClasses.contains(descriptor.staticRootClass)) {
                throw new ResolveException("Static class is not initialized");
            }
            current = Class.forName(descriptor.staticRootClass, false, runtime.guestLoader);
            if (((Class<?>)current).getClassLoader() != runtime.guestLoader) {
                throw new ResolveException("Foreign static class");
            }
        }
        for (int i = 0; i < descriptor.edges.size(); i++) {
            Edge edge = descriptor.edges.get(i);
            boolean last = i == descriptor.edges.size() - 1;
            if (edge instanceof FieldEdge) {
                FieldEdge fieldEdge = (FieldEdge) edge;
                boolean staticOwner = current instanceof Class;
                Class<?> type = staticOwner ? (Class<?>) current : current.getClass();
                if (type.getClassLoader() != runtime.guestLoader) {
                    throw new ResolveException("Foreign object type");
                }
                Field field = requireField(type, fieldEdge);
                if (Modifier.isStatic(field.getModifiers()) != staticOwner) {
                    throw new ResolveException("Static/instance mismatch");
                }
                if (last) {
                    if (field.getType() != int.class || Modifier.isFinal(field.getModifiers())) {
                        throw new ResolveException("Target is not editable int");
                    }
                    return new Binding(staticOwner ? null : current, field);
                }
                current = field.get(staticOwner ? null : current);
                if (current == null) throw new ResolveException("Intermediate is null");
            } else if (edge instanceof IndexEdge) {
                IndexEdge index = (IndexEdge) edge;
                if (last || !(current instanceof Object[])) throw new ResolveException("Invalid array route");
                int length = Array.getLength(current);
                if (index.index < 0 || index.index >= length) throw new ResolveException("Array index invalid");
                current = Array.get(current, index.index);
                if (current == null) throw new ResolveException("Array slot is null");
            } else {
                throw new ResolveException("Unknown locator edge");
            }
        }
        throw new AssertionError("No target");
    }

    /** Re-evaluate the complete root-to-field path before writing, fail closed if identity moved. */
    private static void checkedWrite(Runtime runtime, Descriptor descriptor, Binding oldBinding,
                                     int expectedOldValue, int replacement) throws Exception {
        Binding now = resolve(runtime, descriptor);
        if (now.owner != oldBinding.owner || !now.field.equals(oldBinding.field)
                || now.read() != expectedOldValue) {
            throw new ResolveException("Stale binding / changed value");
        }
        now.field.setInt(now.owner, replacement);
    }

    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }
    private static void rejected(Checked action, String why) throws Exception {
        try { action.run(); } catch (ResolveException expected) { check(true, why); return; }
        throw new AssertionError("Expected failure: " + why);
    }
    interface Checked { void run() throws Exception; }

    private static String sha256(File jar) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar.toPath()));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        String hash = sha256(jar);
        URL url = jar.toURI().toURL();
        URLClassLoader firstLoader = new URLClassLoader(new URL[]{url}, null);
        URLClassLoader nextLoader = new URLClassLoader(new URL[]{url}, null);
        Class<?> firstType = firstLoader.loadClass(GAME);
        Class<?> nextType = nextLoader.loadClass(GAME);
        Object firstGame = firstType.getConstructor().newInstance();
        Object nextGame = nextType.getConstructor().newInstance();
        Runtime first = new Runtime(hash, firstLoader, firstGame, GAME);
        Runtime next = new Runtime(hash, nextLoader, nextGame, GAME);
        check(firstType != nextType, "separate runtime class identity");
        Descriptor money = new Descriptor(hash, null,
            new FieldEdge(GAME, "player", PLAYER), new FieldEdge(PLAYER, "money", "int"));
        Binding before = resolve(first, money);
        check(before.read() == 50, "first session should resolve");
        Binding after = resolve(next, money);
        check(after.read() == 50, "fresh class loader should resolve via structural descriptor");
        checkedWrite(next, money, after, 50, 777);
        check(resolve(next, money).read() == 777, "fresh session target should change");
        check(resolve(first, money).read() == 50, "original runtime must remain untouched");

        Descriptor global = new Descriptor(hash, GAME, new FieldEdge(GAME, "globalMoney", "int"));
        check(resolve(next, global).read() == 15, "initialized static field should resolve");
        rejected(() -> resolve(new Runtime(hash, nextLoader, nextGame), global),
                 "uninitialized static class must not be read");
        rejected(() -> resolve(next, new Descriptor("wrong", null, money.edges.toArray(new Edge[0]))),
                 "mismatched source JAR must fail");
        rejected(() -> resolve(next, new Descriptor(hash, null,
                 new FieldEdge(GAME, "player", PLAYER), new FieldEdge(PLAYER, "money", "long"))),
                 "mismatched primitive type must fail");
        rejected(() -> resolve(next, new Descriptor(hash, null,
                 new FieldEdge(GAME, "player", PLAYER), new FieldEdge(PLAYER, "missing", "int"))),
                 "missing field must fail");
        rejected(() -> resolve(next, new Descriptor(hash, null,
                 new FieldEdge(GAME, "player", PLAYER), new FieldEdge(PLAYER, "money", "int"),
                 new IndexEdge(0))), "primitive target cannot be followed");

        Binding saved = resolve(next, money);
        nextType.getField("player").set(nextGame, firstType == nextType ? null :
                nextLoader.loadClass(PLAYER).getConstructor(int.class).newInstance(50));
        rejected(() -> checkedWrite(next, money, saved, 777, 999),
                 "same-value replacement owner must not steal stale binding");
        check(resolve(next, money).read() == 50, "replacement owner remains unchanged");

        Descriptor party = new Descriptor(hash, null, new FieldEdge(GAME,"party","[L"+PLAYER+";"),
                new IndexEdge(0), new FieldEdge(PLAYER,"money","int"));
        Binding left = resolve(next, party);
        Object array = nextType.getField("party").get(nextGame);
        Object a = Array.get(array,0), b = Array.get(array,1);
        Array.set(array,0,b); Array.set(array,1,a);
        Binding swapped = resolve(next, party);
        check(left.owner != swapped.owner && left.read() == swapped.read(),
             "structurally valid array index can silently change semantic entity");
        rejected(() -> checkedWrite(next, party, left, 50, 123),
                 "stale index-to-owner mapping must reject write");
        System.out.println("PASS checks=" + checks + " sha256=" + hash);
        System.out.println("Observations: cross-loader structural resolve=YES; " +
            "stale owner rebinding=BLOCKED; semantic ambiguity of array indexes=DEMONSTRATED.");
        firstLoader.close(); nextLoader.close();
    }
}
