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
package android.hardware.cts.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Set of static helper methods to verify sensor CTS tests.
 */
public class SensorVerificationHelper {

    private static final int MESSAGE_LENGTH = 3;

    /**
     * Class which holds results from the verification.
     */
    public static class VerificationResult {
        private boolean mFailed = false;
        private String mMessage = null;
        private Map<String, Object> mValueMap = new HashMap<String, Object>();

        public void fail(String messageFormat, Object ... args) {
            mFailed = true;
            mMessage = String.format(messageFormat, args);
        }

        public boolean isFailed() {
            return mFailed;
        }

        public String getFailureMessage() {
            return mMessage;
        }

        public void putValue(String key, Object value) {
            mValueMap.put(key, value);
        }

        public Object getValue(String key) {
            return mValueMap.get(key);
        }
    }

    /**
     * Private constructor for static class.
     */
    private SensorVerificationHelper() {}

    /**
     * Verify that the events are in the correct order.
     *
     * @param events The array of {@link TestSensorEvent}
     * @return a {@link VerificationResult} containing the verification info including the keys
     *     "count" which is the number of events out of order and "positions" which contains an
     *     array of indexes that were out of order.
     * @throws IllegalStateException if number of events less than 1.
     */
    public static VerificationResult verifyEventOrdering(TestSensorEvent[] events) {
        VerificationResult result = new VerificationResult();
        List<Integer> indices = new ArrayList<Integer>();
        long maxTimestamp = events[0].timestamp;
        for (int i = 1; i < events.length; i++) {
            long currentTimestamp = events[i].timestamp;
            if (currentTimestamp < maxTimestamp) {
                indices.add(i);
            } else if (currentTimestamp > maxTimestamp) {
                maxTimestamp = currentTimestamp;
            }
        }

        result.putValue("count", indices.size());
        result.putValue("positions", indices);

        if (indices.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(indices.size()).append(" events out of order: ");
            for (int i = 0; i < Math.min(indices.size(), MESSAGE_LENGTH); i++) {
                int index = indices.get(i);
                sb.append(String.format("position=%d, previous=%d, timestamp=%d; ", index,
                        events[index - 1].timestamp, events[index].timestamp));
            }
            if (indices.size() > MESSAGE_LENGTH) {
                sb.append(indices.size() - MESSAGE_LENGTH).append(" more");
            } else {
                // Delete the "; "
                sb.delete(sb.length() - 2, sb.length());
            }

            result.fail(sb.toString());
        }

        return result;
    }

    /**
     * Verify that the sensor frequency matches the expected frequency.
     *
     * @param events The array of {@link TestSensorEvent}
     * @param expected The expected frequency in Hz
     * @param threshold The acceptable margin of error in Hz
     * @return a {@link VerificationResult} containing the verification info including the key
     *     "frequency" which is the computed frequency of the events in Hz.
     * @throws IllegalStateException if number of events less than 1.
     */
    public static VerificationResult verifyFrequency(TestSensorEvent[] events, double expected,
            double threshold) {
        VerificationResult result = new VerificationResult();
        List<Long> timestampDelayValues = SensorCtsHelper.getTimestampDelayValues(events);
        double frequency = SensorCtsHelper.getFrequency(
                SensorCtsHelper.getMean(timestampDelayValues), TimeUnit.NANOSECONDS);
        result.putValue("frequency", frequency);

        if (Math.abs(frequency - expected) > threshold) {
            result.fail("Frequency out of range: frequency=%.2fHz, expected=%.2f+/-%.2fHz",
                    frequency, expected, threshold);
        }
        return result;
    }

    /**
     * Verify that the jitter is in an acceptable range
     *
     * @param events The array of {@link TestSensorEvent}
     * @param threshold The acceptable margin of error in nanoseconds
     * @return a {@link VerificationResult} containing the verification info including the keys
     *     "jitter" which is the list of computed jitter values and "jitter95Percentile" which is
     *     95th percentile of the jitter values.
     * @throws IllegalStateException if number of events less than 2.
     */
    public static VerificationResult verifyJitter(TestSensorEvent[] events, double threshold) {
        VerificationResult result = new VerificationResult();
        List<Double> jitterValues = SensorCtsHelper.getJitterValues(events);
        double jitter95Percentile = SensorCtsHelper.get95PercentileValue(jitterValues);
        result.putValue("jitter", jitterValues);
        result.putValue("jitter95Percentile", jitter95Percentile);

        if (jitter95Percentile > threshold) {
            result.fail("Jitter out of range: jitter at 95th percentile=%.0fns, expected=<%.0fns",
                    jitter95Percentile, threshold);
        }
        return result;
    }

    /**
     * Verify that the means matches the expected measurement.
     *
     * @param events The array of {@link TestSensorEvent}
     * @param expected The array of expected values
     * @param threshold The array of thresholds
     * @return a {@link VerificationResult} containing the verification info including the key
     *     "mean" which is the computed means for each value of the sensor.
     * @throws IllegalStateException if number of events less than 1.
     */
    public static VerificationResult verifyMean(TestSensorEvent[] events, double[] expected,
            double[] threshold) {
        VerificationResult result = new VerificationResult();
        double[] means = SensorCtsHelper.getMeans(events);
        result.putValue("means", means);

        boolean failed = false;
        StringBuilder meanSb = new StringBuilder();
        StringBuilder expectedSb = new StringBuilder();

        if (means.length > 1) {
            meanSb.append("(");
            expectedSb.append("(");
        }
        for (int i = 0; i < means.length && !failed; i++) {
            if (Math.abs(means[i] - expected[i]) > threshold[i]) {
                failed = true;
            }
            meanSb.append(String.format("%.2f", means[i]));
            if (i != means.length - 1) meanSb.append(", ");
            expectedSb.append(String.format("%.2f+/-%.2f", expected[i], threshold[i]));
            if (i != means.length - 1) expectedSb.append(", ");
        }
        if (means.length > 1) {
            meanSb.append(")");
            expectedSb.append(")");
        }

        if (failed) {
            result.fail("Mean out of range: mean=%s, expected=%s",
                    meanSb.toString(), expectedSb.toString());
        }
        return result;
    }

    /**
     * Verify that the mean of the magnitude of the sensors vector is within the expected range.
     *
     * @param events The array of {@link TestSensorEvent}
     * @param expected The expected value
     * @param threshold The threshold
     * @return a {@link VerificationResult} containing the verification info including the key
     *     "magnitude" which is the mean of the computed magnitude of the sensor values.
     * @throws IllegalStateException if number of events less than 1.
     */
    public static VerificationResult verifyMagnitude(TestSensorEvent[] events, double expected,
            double threshold) {
        VerificationResult result = new VerificationResult();
        Collection<Double> magnitudes = new ArrayList<Double>(events.length);

        for (TestSensorEvent event : events) {
            double norm = 0;
            for (int i = 0; i < event.values.length; i++) {
                norm += event.values[i] * event.values[i];
            }
            magnitudes.add(Math.sqrt(norm));
        }

        double mean = SensorCtsHelper.getMean(magnitudes);
        result.putValue("magnitude", mean);

        if (Math.abs(mean - expected) > threshold) {
            result.fail(String.format("Magnitude mean out of range: mean=%s, expected=%s+/-%s",
                    mean, expected, threshold));
        }
        return result;
    }

    /**
     * Verify that the sign of each of the sensor values is correct.
     * <p>
     * If the value of the measurement is in [-threshold, threshold], the sign is considered 0. If
     * it is less than -threshold, it is considered -1. If it is greater than threshold, it is
     * considered 1.
     * </p>
     *
     * @param events
     * @param threshold The threshold that needs to be crossed to consider a measurement nonzero
     * @return a {@link VerificationResult} containing the verification info including the key
     *     "mean" which is the computed means for each value of the sensor.
     * @throws IllegalStateException if number of events less than 1.
     */
    public static VerificationResult verifySignum(TestSensorEvent[] events, int[] expected,
            double[] threshold) {
        VerificationResult result = new VerificationResult();
        for (int i = 0; i < expected.length; i++) {
            if (!(expected[i] == -1 || expected[i] == 0 || expected[i] == 1)) {
                throw new IllegalArgumentException("Expected value must be -1, 0, or 1");
            }
        }
        double[] means = SensorCtsHelper.getMeans(events);
        result.putValue("means", means);

        boolean failed = false;
        StringBuilder meanSb = new StringBuilder();
        StringBuilder expectedSb = new StringBuilder();

        if (means.length > 1) {
            meanSb.append("(");
            expectedSb.append("(");
        }
        for (int i = 0; i < means.length; i++) {
            meanSb.append(String.format("%.2f", means[i]));
            if (i != means.length - 1) meanSb.append(", ");

            if (expected[i] == 0) {
                if (Math.abs(means[i]) > threshold[i]) {
                    failed = true;
                }
                expectedSb.append(String.format("[%.2f, %.2f]", -threshold[i], threshold[i]));
            } else {
                if (expected[i] > 0) {
                    if (means[i] <= threshold[i]) {
                        failed = true;
                    }
                    expectedSb.append(String.format("(%.2f, inf)", threshold[i]));
                } else {
                    if (means[i] >= -1 * threshold[i]) {
                        failed = true;
                    }
                    expectedSb.append(String.format("(-inf, %.2f)", -1 * threshold[i]));
                }
            }
            if (i != means.length - 1) expectedSb.append(", ");
        }
        if (means.length > 1) {
            meanSb.append(")");
            expectedSb.append(")");
        }

        if (failed) {
            result.fail("Signum out of range: mean=%s, expected=%s",
                    meanSb.toString(), expectedSb.toString());
        }
        return result;
    }

    /**
     * Verify that the standard deviations is within the expected range.
     *
     * @param events The array of {@link TestSensorEvent}
     * @param threshold The array of thresholds
     * @return a {@link VerificationResult} containing the verification info including the key
     *     "stddevs" which is the computed standard deviations for each value of the sensor.
     * @throws IllegalStateException if number of events less than 1.
     */
    public static VerificationResult verifyStandardDeviation(TestSensorEvent[] events,
            double[] threshold) {
        VerificationResult result = new VerificationResult();
        double[] standardDeviations = SensorCtsHelper.getStandardDeviations(events);
        result.putValue("stddevs", standardDeviations);

        boolean failed = false;
        StringBuilder meanSb = new StringBuilder();
        StringBuilder expectedSb = new StringBuilder();

        if (standardDeviations.length > 1) {
            meanSb.append("(");
            expectedSb.append("(");
        }
        for (int i = 0; i < standardDeviations.length && !failed; i++) {
            if (standardDeviations[i] > threshold[i]) {
                failed = true;
            }
            meanSb.append(String.format("%.2f", standardDeviations[i]));
            if (i != standardDeviations.length - 1) meanSb.append(", ");
            expectedSb.append(String.format("0+/-%.2f", threshold[i]));
            if (i != standardDeviations.length - 1) expectedSb.append(", ");
        }
        if (standardDeviations.length > 1) {
            meanSb.append(")");
            expectedSb.append(")");
        }

        if (failed) {
            result.fail("Standard deviation out of range: mean=%s, expected=%s",
                    meanSb.toString(), expectedSb.toString());
        }
        return result;
    }
}
