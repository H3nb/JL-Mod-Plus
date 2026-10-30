// Original JL-Mod Plus graphics research fixture (no emulator sources copied).
// Compile with the offline J2ME Dev Kit:
// sh scripts/compile.sh midp20 /tmp/graphics-fixture-classes research/graphics/fixtures/GraphicsPixelProbeMidlet.java
// This compiles against MIDP 2.0 only. Runtime validation remains pending.
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.midlet.MIDlet;

public final class GraphicsPixelProbeMidlet extends MIDlet {
    private final Canvas screen = new Canvas() {
        protected void paint(Graphics g) {
            String result = runProbe();
            g.setColor(0xffffff);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(0);
            g.drawString(result, 2, 2, Graphics.TOP | Graphics.LEFT);
        }
    };

    public void startApp() { Display.getDisplay(this).setCurrent(screen); }
    public void pauseApp() { }
    public void destroyApp(boolean unconditional) { }

    private static String runProbe() {
        try {
            Image image = Image.createImage(32, 32);
            Graphics g = image.getGraphics();
            g.setColor(0xff0000);
            g.fillRect(0, 0, 32, 32);
            g.setClip(4, 4, 4, 4);
            g.setColor(0x00ff00);
            g.fillRect(0, 0, 32, 32);
            g.setClip(0, 0, 32, 32);
            g.copyArea(4, 4, 4, 4, 16, 16, Graphics.TOP | Graphics.LEFT);
            int[] rgb = new int[32 * 32];
            image.getRGB(rgb, 0, 32, 0, 0, 32, 32);
            if ((rgb[0] & 0xffffff) != 0xff0000) return "FAIL background";
            if ((rgb[5 * 32 + 5] & 0xffffff) != 0x00ff00) return "FAIL clip";
            if ((rgb[17 * 32 + 17] & 0xffffff) != 0x00ff00) return "FAIL copyArea";
            if ((rgb[12 * 32 + 12] & 0xffffff) != 0xff0000) return "FAIL outside";
            return "PASS pixels";
        } catch (Throwable t) {
            return "FAIL " + t.getClass().getName();
        }
    }
}
