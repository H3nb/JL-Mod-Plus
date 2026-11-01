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
import java.util.Base64;

public class MidletDexInspectionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void legacyDexWithoutTailRejectsFinalObjectAndArrayRoots() throws Exception {
        // Class bytes produced by javac --release 8 for the source below.
        // An inline fixture avoids javax.tools on Android's unit-test boot classpath.
        // public final class LegacyRoot {
        //   static final Object ROOT = new Object();
        //   static final int[] VALUES = new int[] {1, 2};
        //   static final String LABEL = "constant";
        // }
        File classFile = new File(temporary.getRoot(), "LegacyRoot.class");
        byte[] bytecode = Base64.getDecoder().decode(
                "yv66vgAAADQAGwoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCQAIAAkHAAoMAAsADAEACkxlZ2FjeVJvb3QBAARST09UAQASTGphdmEvbGFuZy9PYmplY3Q7" +
                "CQAIAA4MAA8AEAEABlZBTFVFUwEAAltJAQAFTEFCRUwBABJMamF2YS9sYW5nL1N0cmluZzsBAA1Db25zdGFudFZhbHVlCAAVAQAIY29uc3RhbnQBAARDb2RlAQAPTGluZU51bWJlclRhYmxl" +
                "AQAIPGNsaW5pdD4BAApTb3VyY2VGaWxlAQAPTGVnYWN5Um9vdC5qYXZhADEACAACAAAAAwAYAAsADAAAABgADwAQAAAAGAARABIAAQATAAAAAgAUAAIAAQAFAAYAAQAWAAAAHQABAAEAAAAFKrcAAbEAAAAB" +
                "ABcAAAAGAAEAAAABAAgAGAAGAAEAFgAAADUABAAAAAAAGbsAAlm3AAGzAAcFvApZAwRPWQQFT7MADbEAAAABABcAAAAKAAIAAAACAAoAAwABABkAAAACABo=");
        Files.write(classFile.toPath(), bytecode);

        File payload = new File(temporary.getRoot(), "converted.dex");
        Main.Arguments arguments = new Main.Arguments();
        arguments.fileNames = new String[] { new File(temporary.getRoot(), "." + File.separator + classFile.getName()).getPath() };
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
        assertTrue("Expected a transformed discovery-tail method ID", renamed);
        Files.write(payload.toPath(), bytes);
        assertFalse("Final reference roots require an instrumented class initializer",
                MidletTransformMetadata.isRuntimeCompatible(descriptor, payload));
    }
}
