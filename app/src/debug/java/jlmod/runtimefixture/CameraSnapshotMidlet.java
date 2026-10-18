// SPDX-License-Identifier: Apache-2.0
package jlmod.runtimefixture;

import java.io.*;
import java.util.Properties;
import javax.microedition.lcdui.*;
import javax.microedition.media.*;
import javax.microedition.media.control.VideoControl;
import javax.microedition.midlet.MIDlet;

/** Manual physical-camera probe: decodes snapshot bytes and draws them without transforms. */
public final class CameraSnapshotMidlet extends MIDlet {
    private Player player;
    private VideoControl video;
    private volatile Image snapshot;
    private volatile String status = "Camera probe";
    private volatile boolean destroyed;
    private File root;
    private Form form;
    private String mode;
    private final Canvas canvas = new Canvas() {
        protected void paint(Graphics g) {
            g.setColor(0x182838);
            g.fillRect(0, 0, getWidth(), getHeight());
            Image image = snapshot;
            if (image != null) g.drawImage(image, 0, 24, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xffffff);
            g.drawString(status, 0, 0, Graphics.TOP | Graphics.LEFT);
        }
    };

    public void startApp() {
        if (root != null) return;
        root = new File(getAppProperty("JLMod-Camera-Probe-Root"));
        Display.getDisplay(this).setCurrent(canvas);
        new Thread(() -> {
            String last = "";
            while (!destroyed) {
                try {
                    Properties command = new Properties();
                    File file = new File(root, "command.properties");
                    if (file.isFile()) try (InputStream in = new FileInputStream(file)) { command.load(in); }
                    String id = command.getProperty("id", "");
                    if (!id.isEmpty() && !id.equals(last)) {
                        last = id;
                        Properties report = new Properties();
                        report.setProperty("id", id);
                        try { execute(command, report); }
                        catch (Exception error) { report.setProperty("error", error.toString()); }
                        status = report.toString();
                        canvas.repaint();
                        try (OutputStream out = new FileOutputStream(new File(root, "report.properties"))) {
                            report.store(out, "Camera probe");
                        }
                    }
                    Thread.sleep(100);
                } catch (Exception error) { status = error.toString(); canvas.repaint(); }
            }
        }, "CameraProbe").start();
    }

    private void execute(Properties command, Properties report) throws Exception {
        String action = command.getProperty("action");
        if ("open".equals(action)) {
            if (player != null) player.close();
            snapshot = null;
            mode = command.getProperty("mode", "canvas");
            player = Manager.createPlayer(command.getProperty("locator", "capture://video"));
            player.realize();
            video = (VideoControl) player.getControl("VideoControl");
            if ("form".equals(mode)) {
                form = new Form("Camera probe");
                form.append((Item) video.initDisplayMode(VideoControl.USE_GUI_PRIMITIVE, null));
                video.setDisplaySize(240, 180);
                Display.getDisplay(this).setCurrent(form);
            } else {
                video.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, canvas);
                video.setDisplayLocation(0, 24);
                video.setDisplaySize(320, 240);
                Display.getDisplay(this).setCurrent(canvas);
            }
            video.setVisible(true);
            player.start();
        } else if ("capture".equals(action)) {
            byte[] bytes = video.getSnapshot(command.getProperty("encoding"));
            String name = command.getProperty("id") + ".snapshot";
            try (OutputStream out = new FileOutputStream(new File(root, name))) { out.write(bytes); }
            snapshot = Image.createImage(bytes, 0, bytes.length);
            report.setProperty("width", Integer.toString(snapshot.getWidth()));
            report.setProperty("height", Integer.toString(snapshot.getHeight()));
            report.setProperty("bytes", Integer.toString(bytes.length));
            report.setProperty("file", name);
            video.setVisible(false);
            if ("form".equals(mode)) {
                form.deleteAll();
                form.append(new ImageItem("Snapshot", snapshot, Item.LAYOUT_DEFAULT, null));
            }
        } else if ("preview".equals(action)) {
            snapshot = null;
            video.setVisible(true);
        } else if ("hide".equals(action)) {
            video.setVisible(false);
        } else if ("display".equals(action)) {
            video.setDisplaySize(Integer.parseInt(command.getProperty("width")),
                    Integer.parseInt(command.getProperty("height")));
        } else if ("stop".equals(action)) {
            player.stop();
        } else if ("start".equals(action)) {
            player.start();
        } else if ("close".equals(action)) {
            player.close();
        } else throw new IllegalArgumentException(action);
        report.setProperty("state", Integer.toString(player.getState()));
    }

    public void pauseApp() {}
    public void destroyApp(boolean unconditional) {
        destroyed = true;
        if (player != null) player.close();
    }
}
