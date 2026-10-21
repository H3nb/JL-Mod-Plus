/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.midp;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.microedition.lcdui.MidpStageBDeviceTest;
import javax.microedition.lcdui.ChoiceGroupSelectionDeviceTest;

/** Explicit suite includes compatibility classes under the javax.microedition package. */
@RunWith(Suite.class)
@Suite.SuiteClasses({MidpStageBDeviceTest.class, ChoiceGroupSelectionDeviceTest.class})
public class MidpStageBTestSuite {
}
