/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.cts.helpers.sensorTestOperations;

import android.content.Context;
import android.hardware.cts.helpers.SensorCtsHelper;
import android.hardware.cts.helpers.SensorManagerTestVerifier;
import android.hardware.cts.helpers.SensorTestInformation;
import android.hardware.cts.helpers.SensorTestOperation;
import android.hardware.cts.helpers.SensorVerificationHelper;
import android.hardware.cts.helpers.SensorVerificationHelper.VerificationResult;
import android.hardware.cts.helpers.TestSensorEvent;

import junit.framework.Assert;

/**
 * Test Operation class that validates the standard deviation of a given sensor.
 */
public class VerifyStandardDeviationOperation extends SensorTestOperation {
    private SensorManagerTestVerifier mSensor;
    private int mAxisCount;
    private double[] mExpectedStandardDeviation;

    public VerifyStandardDeviationOperation(
            Context context,
            int sensorType,
            int samplingRateInUs,
            int reportLatencyInUs,
            float expectedStandardDeviation) {
        mSensor = new SensorManagerTestVerifier(
                context,
                sensorType,
                samplingRateInUs,
                reportLatencyInUs);
        // set expectations
        mAxisCount = SensorTestInformation.getAxisCount(mSensor.getUnderlyingSensor().getType());
        mExpectedStandardDeviation = new double[mAxisCount];
        for (int i = 0; i < mExpectedStandardDeviation.length; i++) {
            mExpectedStandardDeviation[i] = expectedStandardDeviation;
        }
    }

    @Override
    public void doWork() {
        TestSensorEvent[] events = mSensor.collectEvents(100);
        VerificationResult result = SensorVerificationHelper.verifyStandardDeviation(events,
                mExpectedStandardDeviation);
        if (result.isFailed()) {
            Assert.fail(SensorCtsHelper.formatAssertionMessage(
                    "StandardDeviation",
                    this,
                    mSensor.getUnderlyingSensor(),
                    result.getFailureMessage()));
        }
    }
}
