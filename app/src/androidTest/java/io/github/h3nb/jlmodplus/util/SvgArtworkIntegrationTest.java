package io.github.h3nb.jlmodplus.util;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.h3nb.jlmodplus.config.ConfigAssetFiles;
import io.github.h3nb.jlmodplus.librarydb.LibraryIconOverride;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SvgArtworkIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void screenSkinImportValidatesStagedTempAndRetainsSvgSource() throws Exception {
        File workdir = temp.newFolder("skin-root");
        File source = new File(temp.getRoot(), "Frame.svg");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(svg().getBytes(StandardCharsets.UTF_8));
        }
        ConfigAssetFiles.Imported result = ConfigAssetFiles.importFile(
                workdir, ConfigAssetFiles.Kind.SKIN, source);
        assertEquals("Frame.svg", result.name);
        assertTrue(new File(ConfigAssetFiles.directory(workdir, ConfigAssetFiles.Kind.SKIN),
                "Frame.svg").isFile());
    }

    @Test public void libraryIconNormalizesSvgToExistingPngContract() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = new File(temp.getRoot(), "Portrait.svg");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(svg().getBytes(StandardCharsets.UTF_8));
        }
        File prepared = LibraryIconOverride.prepare(context, Uri.fromFile(source));
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(prepared.getAbsolutePath());
            assertNotNull(bitmap);
            try {
                assertEquals(1024, bitmap.getWidth());
                assertEquals(512, bitmap.getHeight());
            } finally { bitmap.recycle(); }
        } finally { prepared.delete(); }
    }

    private static String svg() {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 10'>"
                + "<rect width='20' height='10' fill='blue'/></svg>";
    }
}
