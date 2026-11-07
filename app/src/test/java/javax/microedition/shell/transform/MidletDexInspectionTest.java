/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package javax.microedition.shell.transform;

import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.android.dx.command.dexer.ConversionResult;
import com.android.dx.command.dexer.Main;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Base64;

public class MidletDexInspectionTest {
    @TempDir public File temporary;

    @Test
    public void legacyDexWithoutTailRejectsFinalObjectAndArrayRoots() throws Exception {
        // Class bytes produced by javac --release 8 for the source below.
        // An inline fixture avoids javax.tools on Android's unit-test boot classpath.
        // public final class LegacyRoot {
        //   static final Object ROOT = new Object();
        //   static final int[] VALUES = new int[] {1, 2};
        //   static final String LABEL = "constant";
        // }
        File classFile = new File(temporary, "LegacyRoot.class");
        byte[] bytecode = Base64.getDecoder().decode(
                "yv66vgAAADQAGwoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCQAIAAkHAAoMAAsADAEACkxlZ2FjeVJvb3QBAARST09UAQASTGphdmEvbGFuZy9PYmplY3Q7" +
                "CQAIAA4MAA8AEAEABlZBTFVFUwEAAltJAQAFTEFCRUwBABJMamF2YS9sYW5nL1N0cmluZzsBAA1Db25zdGFudFZhbHVlCAAVAQAIY29uc3RhbnQBAARDb2RlAQAPTGluZU51bWJlclRhYmxl" +
                "AQAIPGNsaW5pdD4BAApTb3VyY2VGaWxlAQAPTGVnYWN5Um9vdC5qYXZhADEACAACAAAAAwAYAAsADAAAABgADwAQAAAAGAARABIAAQATAAAAAgAUAAIAAQAFAAYAAQAWAAAAHQABAAEAAAAFKrcAAbEAAAAB" +
                "ABcAAAAGAAEAAAABAAgAGAAGAAEAFgAAADUABAAAAAAAGbsAAlm3AAGzAAcFvApZAwRPWQQFT7MADbEAAAABABcAAAAKAAIAAAACAAoAAwABABkAAAACABo=");
        Files.write(classFile.toPath(), bytecode);

        File payload = new File(temporary, "converted.dex");
        Main.Arguments arguments = new Main.Arguments();
        arguments.fileNames = new String[] { new File(temporary, "." + File.separator + classFile.getName()).getPath() };
        arguments.outName = payload.getPath();
        arguments.numThreads = 1;
        ConversionResult converted = Main.runWithResult(arguments);
        assertTrue(converted.getDiagnostics().toString(), converted.isSuccess());

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
        assertTrue(renamed, "Expected a transformed discovery-tail method ID");
        Files.write(payload.toPath(), bytes);
        assertFalse(MidletTransformMetadata.isRuntimeCompatible(descriptor, payload), "Final reference roots require an instrumented class initializer");
    }

    @Test
    public void legacyDexWithoutTailRejectsMutablePrimitiveRoot() throws Exception {
        // javac --release 8 -g:none:
        // public final class LegacyPrimitiveRoot { static int score; }
        File dex = convertClass("LegacyPrimitiveRoot",
                "yv66vgAAADQADAoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWBwAIAQATTGVnYWN5UHJpbWl0aXZlUm9vdAEABXNjb3JlAQABSQEABENvZGUAMQAHAAIAAAABAAgACQAKAAAAAQABAAUABgABAAsAAAARAAEAAQAAAAUqtwABsQAAAAAAAA==");
        Map<String, String> descriptor = new HashMap<>();
        MidletTransformMetadata.mark(descriptor);
        assertTrue(MidletTransformMetadata.isRuntimeCompatible(descriptor, dex));

        byte[] bytes = Files.readAllBytes(dex.toPath());
        byte[] tail = "tailSeen".getBytes(StandardCharsets.US_ASCII);
        boolean removed = false;
        for (int i = 0; i <= bytes.length - tail.length; i++) {
            boolean match = true;
            for (int j = 0; j < tail.length; j++) {
                if (bytes[i + j] != tail[j]) { match = false; break; }
            }
            if (match) {
                bytes[i] = (byte) 'f';
                removed = true;
            }
        }
        assertTrue(removed, "The converter must instrument mutable primitive roots");
        Files.write(dex.toPath(), bytes);
        assertFalse(MidletTransformMetadata.isRuntimeCompatible(descriptor, dex), "An uninstrumented static primitive contradicts the transform marker");
    }

    @Test
    public void legacyDexWithoutTailAcceptsCompileTimePrimitiveConstant() throws Exception {
        // javac --release 8 -g:none:
        // public final class LegacyPrimitiveConstant { static final int SCORE = 7; }
        File dex = convertClass("LegacyPrimitiveConstant",
                "yv66vgAAADQADgoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWBwAIAQAXTGVnYWN5UHJpbWl0aXZlQ29uc3RhbnQBAAVTQ09SRQEAAUkBAA1Db25zdGFudFZhbHVlAwAAAAcBAARDb2RlADEABwACAAAAAQAYAAkACgABAAsAAAACAAwAAQABAAUABgABAA0AAAARAAEAAQAAAAUqtwABsQAAAAAAAA==");
        assertFalse(new String(Files.readAllBytes(dex.toPath()), StandardCharsets.US_ASCII)
                        .contains("tailSeen"), "A constant-only class should not publish a managed tail");
        Map<String, String> descriptor = new HashMap<>();
        MidletTransformMetadata.mark(descriptor);
        assertTrue(MidletTransformMetadata.isRuntimeCompatible(descriptor, dex));
    }

    private File convertClass(String className, String base64) throws Exception {
        File classFile = new File(temporary, className + ".class");
        Files.write(classFile.toPath(), Base64.getDecoder().decode(base64));
        File payload = new File(temporary, className + ".dex");
        Main.Arguments args = new Main.Arguments();
        args.fileNames = new String[] {
                new File(temporary, "." + File.separator + classFile.getName()).getPath()
        };
        args.outName = payload.getPath();
        args.numThreads = 1;
        ConversionResult converted = Main.runWithResult(args);
        assertTrue(converted.getDiagnostics().toString(), converted.isSuccess());
        return payload;
    }
}
