/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.holo.cts;

import com.android.cts.tradefed.build.CtsBuildHelper;
import com.android.ddmlib.Log;
import com.android.ddmlib.Log.LogLevel;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceTestCase;
import com.android.tradefed.testtype.IBuildReceiver;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test to check the Holo theme has not been changed.
 */
public class HoloHostTest extends DeviceTestCase implements IBuildReceiver {

    private static final String TAG = HoloHostTest.class.getSimpleName();

    private static final int CAPTURE_TIMEOUT = 1 * 1000;//1sec in ms

    private static final int ADB_TIMEOUT = 10 * 60 * 1000;//10mins in ms

    /** The package name of the APK. */
    private static final String PACKAGE = "android.holo.app";

    /** The file name of the APK. */
    private static final String APK = "CtsHoloDeviceApp.apk";

    /** The class name of the main activity in the APK. */
    private static final String CLASS = "HoloDeviceActivity";

    /** The command to launch the main activity. */
    private static final String START_CMD = String.format(
            "am start -W -a android.intent.action.MAIN -n %s/%s.%s", PACKAGE, PACKAGE, CLASS);

    private static final String STOP_CMD = String.format("am force-stop %s", PACKAGE);

    private static final String DENSITY_PROP = "ro.sf.lcd_density";

    // Intent extras
    protected final static String INTENT_STRING_EXTRA = " --es %s %s";

    protected final static String INTENT_BOOLEAN_EXTRA = " --ez %s %b";

    protected final static String INTENT_INTEGER_EXTRA = " --ei %s %d";

    // Intent extra keys
    private static final String EXTRA_THEME = "holo_theme_extra";

    private static final String EXTRA_LAYOUT = "holo_layout_extra";

    private static final String EXTRA_TIMEOUT = "holo_timeout_extra";

    private static final String[] THEMES = {
            "holo",
            "holo_dialog",
            "holo_dialog_minwidth",
            "holo_dialog_noactionbar",
            "holo_dialog_noactionbar_minwidth",
            "holo_dialogwhenlarge",
            "holo_dialogwhenlarge_noactionbar",
            "holo_inputmethod",
            "holo_light",
            "holo_light_darkactionbar",
            "holo_light_dialog",
            "holo_light_dialog_minwidth",
            "holo_light_dialog_noactionbar",
            "holo_light_dialog_noactionbar_minwidth",
            "holo_light_dialogwhenlarge",
            "holo_light_dialogwhenlarge_noactionbar",
            "holo_light_noactionbar",
            "holo_light_noactionbar_fullscreen",
            "holo_light_panel",
            "holo_noactionbar",
            "holo_noactionbar_fullscreen",
            "holo_panel",
            "holo_wallpaper",
            "holo_wallpaper_notitlebar"
    };

    private final int NUM_THEMES = THEMES.length;

    private static final String[] LAYOUTS = {
            "button",
            "button_pressed",
            "checkbox",
            "checkbox_checked",
            "chronometer"
    };

    private final int NUM_LAYOUTS = LAYOUTS.length;

    private final HashMap<String, File> mReferences = new HashMap<String, File>();

    /** A reference to the build. */
    private CtsBuildHelper mBuild;

    /** A reference to the device under test. */
    private ITestDevice mDevice;

    private ExecutorService mExecutionService;

    private ExecutorCompletionService<Boolean> mCompletionService;

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        // Get the build, this is used to access the APK.
        mBuild = CtsBuildHelper.createBuildHelper(buildInfo);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Get the device, this gives a handle to run commands and install APKs.
        mDevice = getDevice();
        // Remove any previously installed versions of this APK.
        mDevice.uninstallPackage(PACKAGE);
        // Get the APK from the build.
        File app = mBuild.getTestApp(APK);
        // Install the APK on the device.
        mDevice.installPackage(app, false);

        final String zip = String.format("/%s.zip",
                getDensityBucket(Integer.parseInt(mDevice.getProperty(DENSITY_PROP))));
        Log.logAndDisplay(LogLevel.INFO, TAG, "Loading resources from " + zip);

        final ZipInputStream in = new ZipInputStream(this.getClass().getResourceAsStream(zip));
        try {
            ZipEntry ze;
            final byte[] buffer = new byte[1024];
            while ((ze = in.getNextEntry()) != null) {
                final String name = ze.getName();
                final File tmp = File.createTempFile("ref_" + name, ".png");
                final FileOutputStream out = new FileOutputStream(tmp);
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
                out.flush();
                out.close();
                mReferences.put(name, tmp);
            }
        } finally {
            in.close();
        }

        mExecutionService = Executors.newFixedThreadPool(2);// 2 worker threads
        mCompletionService = new ExecutorCompletionService<Boolean>(mExecutionService);
    }

    @Override
    protected void tearDown() throws Exception {
        // Delete the temp files
        for (File ref : mReferences.values()) {
            ref.delete();
        }
        mExecutionService.shutdown();
        // Remove the APK.
        mDevice.uninstallPackage(PACKAGE);
        super.tearDown();
    }

    public void testHoloThemes() throws Exception {
        int numTasks = 0;
        for (int i = 0; i < NUM_THEMES; i++) {
            final String themeName = THEMES[i];
            for (int j = 0; j < NUM_LAYOUTS; j++) {
                final String name = String.format("%s_%s", themeName, LAYOUTS[j]);
                if (runCapture(i, j)) {
                    final File ref = mReferences.get(name + ".png");
                    mCompletionService.submit(new ComparisonTask(mDevice, ref, name));
                    numTasks++;
                } else {
                    Log.logAndDisplay(LogLevel.ERROR, TAG, "Capture failed: " + name);
                }
            }
        }
        boolean success = true;
        for (int i = 0; i < numTasks; i++) {
            success = mCompletionService.take().get() && success;
        }
        assertTrue("Failures in Holo test", success);
    }

    private boolean runCapture(int themeId, int layoutId) throws Exception {
        final StringBuilder sb = new StringBuilder(START_CMD);
        sb.append(String.format(INTENT_INTEGER_EXTRA, EXTRA_THEME, themeId));
        sb.append(String.format(INTENT_INTEGER_EXTRA, EXTRA_LAYOUT, layoutId));
        sb.append(String.format(INTENT_INTEGER_EXTRA, EXTRA_TIMEOUT, CAPTURE_TIMEOUT));
        final String startCommand = sb.toString();
        // Clear logcat
        mDevice.executeAdbCommand("logcat", "-c");
        // Stop any existing instances
        mDevice.executeShellCommand(STOP_CMD);
        // Start activity
        mDevice.executeShellCommand(startCommand);

        boolean success = false;
        boolean waiting = true;
        while (waiting) {
            // Dump logcat.
            final String logs = mDevice.executeAdbCommand("logcat", "-d", CLASS + ":I", "*:S");
            // Search for string.
            final Scanner in = new Scanner(logs);
            while (in.hasNextLine()) {
                final String line = in.nextLine();
                if (line.startsWith("I/" + CLASS)) {
                    final String s = line.split(":")[1].trim();
                    if (s.equals("OKAY")) {
                        success = true;
                        waiting = false;
                    } else if (s.equals("ERROR")) {
                        success = false;
                        waiting = false;
                    }
                }
            }
        }

        return success;
    }

    private static String getDensityBucket(int density) {
        switch (density) {
            case 120:
                return "ldpi";
            case 160:
                return "mdpi";
            case 213:
                return "tvdpi";
            case 240:
                return "hdpi";
            case 320:
                return "xhdpi";
            case 400:
                return "400dpi";
            case 480:
                return "xxhdpi";
            case 640:
                return "xxxhdpi";
            default:
                return "" + density;
        }
    }
}
