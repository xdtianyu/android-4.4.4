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
 * Test Operation class that validates the sign of measurements of a a given sensor.
 * The operation relies in the number of axes each sensor type reports.
 */
public class VerifySignumOperation extends SensorTestOperation {
    private final SensorManagerTestVerifier mSensor;
    private final int mAxisCount;
    private final int mReferenceValues[];
    private final double mNoiseThresholds[];

    /**
     * @param noiseThreshold Defines the threshold that needs to be crossed to consider a
     *                       measurement different from zero
     */
    public VerifySignumOperation(
            Context context,
            int sensorType,
            int samplingRateInUs,
            int referenceValues[],
            double noiseThreshold) {
        mAxisCount = SensorTestInformation.getAxisCount(sensorType);
        if(mAxisCount != referenceValues.length) {
            throw new InvalidParameterException(
                    String.format("%d reference values are expected.", mAxisCount));
        }
        for(int i = 0; i < referenceValues.length; ++i) {
            int value = referenceValues[i];
            if(value != 0 && value != -1 && value != +1) {
                throw new InvalidParameterException(
                        "A ReferenceValue can only be one of the following: -1, 0, +1");
            }
        }
        mSensor = new SensorManagerTestVerifier(
                context,
                sensorType,
                samplingRateInUs,
                0 /*reportLatencyInUs*/);
        // set expectations
        mReferenceValues = referenceValues;
        mNoiseThresholds = new double[mReferenceValues.length];
        for (int i = 0; i < mNoiseThresholds.length; i++) {
            mNoiseThresholds[i] = noiseThreshold;
        }
    }

    @Override
    public void doWork() {
        TestSensorEvent[] events = mSensor.collectEvents(100);
        VerificationResult result = SensorVerificationHelper.verifySignum(events, mReferenceValues,
                mNoiseThresholds);
        if (result.isFailed()) {
            Assert.fail(SensorCtsHelper.formatAssertionMessage(
                    "Measurement",
                    this,
                    mSensor.getUnderlyingSensor(),
                    result.getFailureMessage()));
        }
    }
}
