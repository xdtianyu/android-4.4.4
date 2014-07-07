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

package android.holo.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;

/**
 * Iterates through all themes and all layouts, starting the Activity to capture the images.
 */
public class CaptureActivity extends Activity {

    private static final int REQUEST_CODE = 1;

    private static final int NUM_THEMES = 24;

    private static final int NUM_LAYOUTS = 5;

    private final CountDownLatch mLatch = new CountDownLatch(1);

    private int mCurrentTheme = 0;

    private int mCurrentLayout = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        generateNextImage();
    }

    /**
     * Starts the activity to generate the next image.
     */
    private void generateNextImage() {
        Intent intent = new Intent(this, HoloDeviceActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(HoloDeviceActivity.EXTRA_THEME, mCurrentTheme);
        intent.putExtra(HoloDeviceActivity.EXTRA_LAYOUT, mCurrentLayout);
        startActivityForResult(intent, REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                mCurrentLayout++;
                if (mCurrentLayout >= NUM_LAYOUTS) {
                    mCurrentLayout = 0;
                    mCurrentTheme++;
                }
                if (mCurrentTheme < NUM_THEMES) {
                    generateNextImage();
                } else {
                    finish();
                }
            } else {
                finish();
            }
        }
    }

    public void finish() {
        mLatch.countDown();
        super.finish();
    }

    public void waitForCompletion() throws InterruptedException {
        mLatch.await();
    }
}
