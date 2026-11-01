/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell.transform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.dx.command.dexer.ConversionResult;
import com.android.dx.command.dexer.Main;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class MidletDexInspectionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void legacyDexWithoutTailRejectsFinalObjectAndArrayRoots() throws Exception {
        // Compile a real class and convert it with the production DEX pipeline.
        // Static final does not mean a reference has a ConstantValue initializer.
        File sourceDir = temporary.newFolder("source");
        File source = new File(sourceDir, "LegacyRoot.java");
        Files.write(source.toPath(), (
                "public final class LegacyRoot {\n" +
                "  static final Object ROOT = new Object();\n" +
                "  static final int[] VALUES = new int[] { 1, 2 };\n" +
                "  static final String LABEL = \"constant\";\n" +
                "}\n").getBytes(StandardCharsets.UTF_8));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JVM tests require a JDK compiler", compiler);
        assertEquals(0, compiler.run(null, null, null, "--release", "8",
                "-d", sourceDir.getPath(), source.getPath()));

        File payload = new File(temporary.getRoot(), "converted.dex");
        Main.Arguments arguments = new Main.Arguments();
        arguments.fileNames = new String[] { new File(sourceDir, "LegacyRoot.class").getPath() };
        arguments.outName = payload.getPath();
        arguments.numThreads = 1;
        ConversionResult converted = Main.runWithResult(arguments);
        assertTrue(converted.toString(), converted.isSuccess());

        Map<String, String> descriptor = new HashMap<>();
        MidletTransformMetadata.mark(descriptor);
        assertTrue(MidletTransformMetadata.isRuntimeCompatible(descriptor, payload));

        // Simulate an older converter that retained the version marker but did
        // not emit the discovery tail. Rename only the DEX method identifier,
        // retaining the genuine static-field table and access flags.
        byte[] bytes = Files.readAllBytes(payload.toPath());
        byte[] tail = "tailSeen".getBytes(StandardCharsets.US_ASCII);
        boolean renamed = false;
        for (int i = 0; i <= bytes.length - tail.length; i++) {
            boolean match = true;
            for (int j = 0; j < tail.length; j++) {
                if (bytes[i + j] != tail[j]) { match = false; break; }
            }
            if (match) {
                bytes[i] = (byte) 'f';
                renamed = true;
            }
        }
        assertTrue("Expected a transformed discovery-tail method ID", renamed);
        Files.write(payload.toPath(), bytes);
        assertFalse("Final reference roots require an instrumented class initializer",
                MidletTransformMetadata.isRuntimeCompatible(descriptor, payload));
    }
}
