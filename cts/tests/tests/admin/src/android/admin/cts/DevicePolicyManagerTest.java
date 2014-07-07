/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.admin.cts;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.test.AndroidTestCase;
import android.util.Log;

import java.util.List;

/**
 * Test that exercises {@link DevicePolicyManager}. The test requires that the
 * CtsDeviceAdminReceiver be installed via the CtsDeviceAdmin.apk and be
 * activated via "Settings > Location & security > Select device administrators".
 */
public class DevicePolicyManagerTest extends AndroidTestCase {

    private static final String TAG = DevicePolicyManagerTest.class.getSimpleName();

    private DevicePolicyManager mDevicePolicyManager;
    private ComponentName mComponent;
    private boolean mDeviceAdmin;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDevicePolicyManager = (DevicePolicyManager)
                mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mComponent = DeviceAdminInfoTest.getReceiverComponent();
        mDeviceAdmin =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN);
        setBlankPassword();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        setBlankPassword();
    }

    private void setBlankPassword() {
        if (!mDeviceAdmin) {
            return;
        }
        // Reset the password to nothing for future tests...
        mDevicePolicyManager.setPasswordQuality(mComponent,
                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 0);
        assertTrue(mDevicePolicyManager.resetPassword("", 0));
    }

    public void testGetActiveAdmins() {
        if (!mDeviceAdmin) {
            Log.w(TAG, "Skipping testGetActiveAdmins");
            return;
        }
        List<ComponentName> activeAdmins = mDevicePolicyManager.getActiveAdmins();
        assertFalse(activeAdmins.isEmpty());
        assertTrue(activeAdmins.contains(mComponent));
        assertTrue(mDevicePolicyManager.isAdminActive(mComponent));
    }

    public void testGetMaximumFailedPasswordsForWipe() {
        if (!mDeviceAdmin) {
            Log.w(TAG, "Skipping testGetMaximumFailedPasswordsForWipe");
            return;
        }
        mDevicePolicyManager.setMaximumFailedPasswordsForWipe(mComponent, 3);
        assertEquals(3, mDevicePolicyManager.getMaximumFailedPasswordsForWipe(mComponent));

        mDevicePolicyManager.setMaximumFailedPasswordsForWipe(mComponent, 5);
        assertEquals(5, mDevicePolicyManager.getMaximumFailedPasswordsForWipe(mComponent));
    }

    public void testPasswordQuality_something() {
        if (!mDeviceAdmin) {
            Log.w(TAG, "Skipping testPasswordQuality_something");
            return;
        }
        mDevicePolicyManager.setPasswordQuality(mComponent,
                DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING,
                mDevicePolicyManager.getPasswordQuality(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertTrue(mDevicePolicyManager.resetPassword("123", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 10);
        assertEquals(10, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd123", 0));

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 3);
        assertEquals(3, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        assertTrue(mDevicePolicyManager.resetPassword("123", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
    }

    public void testPasswordQuality_numeric() {
        if (!mDeviceAdmin) {
            Log.w(TAG, "Skipping testPasswordQuality_numeric");
            return;
        }
        mDevicePolicyManager.setPasswordQuality(mComponent,
                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC,
                mDevicePolicyManager.getPasswordQuality(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertTrue(mDevicePolicyManager.resetPassword("123", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 10);
        assertEquals(10, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd123", 0));

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 3);
        assertEquals(3, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        assertTrue(mDevicePolicyManager.resetPassword("123", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
    }

    public void testPasswordQuality_alphabetic() {
        if (!mDeviceAdmin) {
            Log.w(TAG, "Skipping testPasswordQuality_alphabetic");
            return;
        }
        mDevicePolicyManager.setPasswordQuality(mComponent,
                DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC,
                mDevicePolicyManager.getPasswordQuality(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 10);
        assertEquals(10, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd123", 0));

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 3);
        assertEquals(3, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
    }

    public void testPasswordQuality_alphanumeric() {
        if (!mDeviceAdmin) {
            Log.w(TAG, "Skipping testPasswordQuality_alphanumeric");
            return;
        }
        mDevicePolicyManager.setPasswordQuality(mComponent,
                DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC);
        assertEquals(DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC,
                mDevicePolicyManager.getPasswordQuality(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 10);
        assertEquals(10, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertFalse(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd123", 0));

        mDevicePolicyManager.setPasswordMinimumLength(mComponent, 3);
        assertEquals(3, mDevicePolicyManager.getPasswordMinimumLength(mComponent));
        assertTrue(mDevicePolicyManager.isActivePasswordSufficient());

        assertFalse(mDevicePolicyManager.resetPassword("123", 0));
        assertFalse(mDevicePolicyManager.resetPassword("abcd", 0));
        assertTrue(mDevicePolicyManager.resetPassword("abcd123", 0));
    }
}
