/*
 * Copyright The Android Open Source Project
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

import java.security.InvalidParameterException;

/**
 * Test Operation class that validates the measurements of a a given sensor.
 * The operation relies on the number of axes each sensor type reports.
 * The verification calculates the mean for each axis on the measurements, and verifies that they
 * fall into the expected intervals.
 */
public class VerifyMeasurementsOperation extends SensorTestOperation {
    private final SensorManagerTestVerifier mSensor;
    private final int mAxisCount;
    private final double[] mReferenceValues;
    private final double[] mThreshold;

    public VerifyMeasurementsOperation(
            Context context,
            int sensorType,
            int samplingRateInUs,
            int reportLatencyInUs,
            double referenceValues[],
            float threshold) {
        mAxisCount = SensorTestInformation.getAxisCount(sensorType);
        if(mAxisCount != referenceValues.length) {
            throw new InvalidParameterException(
                    String.format("%d reference values are expected.", mAxisCount));
        }
        mSensor = new SensorManagerTestVerifier(
                context,
                sensorType,
                samplingRateInUs,
                reportLatencyInUs);
        // set expectations
        mReferenceValues = referenceValues;
        mThreshold = new double[mAxisCount];
        for (int i = 0; i < mThreshold.length; i++) {
            mThreshold[i] = threshold;
        }
    }

    @Override
    public void doWork() {
        TestSensorEvent[] events = mSensor.collectEvents(100);
        VerificationResult result = SensorVerificationHelper.verifyMean(events, mReferenceValues,
                mThreshold);
        if (result.isFailed()) {
            Assert.fail(SensorCtsHelper.formatAssertionMessage(
                    "Measurement",
                    this,
                    mSensor.getUnderlyingSensor(),
                    result.getFailureMessage()));
        }
    }
}
