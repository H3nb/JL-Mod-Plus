/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.porting;

import com.nokia.mid.ui.SoftNotification;
import com.nokia.mid.ui.SoftNotificationException;
import com.nokia.mid.ui.SoftNotificationListener;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * A small manual MIDlet for exercising the Nokia SoftNotification API.
 *
 * <p>The notification is posted only after the user selects the command so
 * Android 13+ permission prompts and disabled-notification behavior can be
 * tested deliberately.</p>
 */
public final class SoftNotificationTestMidlet extends MIDlet
        implements CommandListener, SoftNotificationListener {

    private static final int NOTIFICATION_ID = 0x4A42;

    private Form form;
    private StringItem status;
    private SoftNotification notification;
    private boolean operationRunning;

    private Command postCommand;
    private Command removeCommand;
    private Command exitCommand;

    @Override
    public void startApp() throws MIDletStateChangeException {
        if (form == null) {
            form = new Form("SoftNotification Test");
            form.append("Use Post to exercise com.nokia.mid.ui.SoftNotification.");
            form.append("On Android 13+, the permission prompt may appear on first post.");
            form.append("Support property: "
                    + System.getProperty("com.nokia.mid.ui.softnotification"));
            status = new StringItem(null, "Status: idle");
            form.append(status);

            postCommand = new Command("Post", Command.ITEM, 1);
            removeCommand = new Command("Remove", Command.ITEM, 2);
            exitCommand = new Command("Exit", Command.EXIT, 3);
            form.addCommand(postCommand);
            form.addCommand(removeCommand);
            form.addCommand(exitCommand);
            form.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(form);
    }

    @Override
    public void pauseApp() {
    }

    @Override
    public void destroyApp(boolean unconditional) throws MIDletStateChangeException {
    }

    @Override
    public void commandAction(Command command, Displayable displayable) {
        if (command == exitCommand) {
            notifyDestroyed();
        } else if (command == postCommand) {
            runAsync(new Runnable() {
                @Override
                public void run() {
                    postNotification();
                }
            });
        } else if (command == removeCommand) {
            runAsync(new Runnable() {
                @Override
                public void run() {
                    removeNotification();
                }
            });
        }
    }

    private synchronized void runAsync(final Runnable operation) {
        if (operationRunning) {
            setStatus("Status: another operation is still running");
            return;
        }
        operationRunning = true;
        setStatus("Status: working...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    operation.run();
                } finally {
                    synchronized (SoftNotificationTestMidlet.this) {
                        operationRunning = false;
                    }
                }
            }
        }).start();
    }

    private void postNotification() {
        try {
            SoftNotification candidate = SoftNotification.newInstance(NOTIFICATION_ID);
            candidate.setText("SoftNotification test notification", "JL-Mod Plus test");
            candidate.setSoftkeyLabels("Open", "Dismiss");
            candidate.setListener(this);
            candidate.post();
            notification = candidate;
            setStatus("Status: posted, id=" + candidate.getId());
        } catch (SoftNotificationException e) {
            setStatus("Status: post failed: " + describe(e));
        }
    }

    private void removeNotification() {
        try {
            SoftNotification candidate = notification;
            if (candidate == null) {
                candidate = SoftNotification.newInstance(NOTIFICATION_ID);
            }
            candidate.remove();
            notification = null;
            setStatus("Status: removed");
        } catch (SoftNotificationException e) {
            setStatus("Status: remove failed: " + describe(e));
        }
    }

    @Override
    public void notificationSelected(SoftNotification selected) {
        setStatus("Status: notification selected, id=" + selected.getId());
    }

    @Override
    public void notificationDismissed(SoftNotification dismissed) {
        setStatus("Status: notification dismissed, id=" + dismissed.getId());
    }

    private void setStatus(final String value) {
        Display display = Display.getDisplay(this);
        if (display == null) {
            return;
        }
        display.callSerially(new Runnable() {
            @Override
            public void run() {
                if (status != null) {
                    status.setText(value);
                }
            }
        });
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.length() == 0) {
            return exception.getClass().getName();
        }
        return message;
    }
}
