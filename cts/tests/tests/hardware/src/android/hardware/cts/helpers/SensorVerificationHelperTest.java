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

package android.hardware.cts.helpers;

import android.hardware.cts.helpers.SensorVerificationHelper.VerificationResult;

import junit.framework.TestCase;

import java.util.List;

/**
 * Unit tests for the {@link SensorVerificationHelper} class.
 */
public class SensorVerificationHelperTest extends TestCase {

    /**
     * Test {@link SensorVerificationHelper#verifyEventOrdering(TestSensorEvent[])}.
     */
    @SuppressWarnings("unchecked")
    public void testVerifyEventOrdering() {
        float[] values = {0, 1, 2, 3, 4};

        long[] timestamps1 = {0, 0, 0, 0, 0};
        TestSensorEvent[] events1 = getSensorEvents(timestamps1, values);
        VerificationResult result = SensorVerificationHelper.verifyEventOrdering(events1);
        assertFalse(result.isFailed());
        assertEquals(0, result.getValue("count"));

        long[] timestamps2 = {0, 1, 2, 3, 4};
        TestSensorEvent[] events2 = getSensorEvents(timestamps2, values);
        result = SensorVerificationHelper.verifyEventOrdering(events2);
        assertFalse(result.isFailed());
        assertEquals(0, result.getValue("count"));

        long[] timestamps3 = {0, 2, 1, 3, 4};
        TestSensorEvent[] events3 = getSensorEvents(timestamps3, values);
        result = SensorVerificationHelper.verifyEventOrdering(events3);
        assertTrue(result.isFailed());
        assertEquals(1, result.getValue("count"));
        List<Integer> indices = (List<Integer>) result.getValue("positions");
        assertTrue(indices.contains(2));

        long[] timestamps4 = {4, 0, 1, 2, 3};
        TestSensorEvent[] events4 = getSensorEvents(timestamps4, values);
        result = SensorVerificationHelper.verifyEventOrdering(events4);
        assertTrue(result.isFailed());
        assertEquals(4, result.getValue("count"));
        indices = (List<Integer>) result.getValue("positions");
        assertTrue(indices.contains(1));
        assertTrue(indices.contains(2));
        assertTrue(indices.contains(3));
        assertTrue(indices.contains(4));
    }

    /**
     * Test {@link SensorVerificationHelper#verifyFrequency(TestSensorEvent[], double, double)}.
     */
    public void testVerifyFrequency() {
        float[] values = {0, 1, 2, 3, 4};
        long[] timestamps = {0, 1000000, 2000000, 3000000, 4000000};  // 1000Hz
        TestSensorEvent[] events = getSensorEvents(timestamps, values);

        VerificationResult result = SensorVerificationHelper.verifyFrequency(events, 1000.0, 1.0);
        assertFalse(result.isFailed());
        assertEquals(1000.0, (Double) result.getValue("frequency"), 0.01);

        result = SensorVerificationHelper.verifyFrequency(events, 950.0, 100.0);
        assertFalse(result.isFailed());
        assertEquals(1000.0, (Double) result.getValue("frequency"), 0.01);

        result = SensorVerificationHelper.verifyFrequency(events, 1050.0, 100.0);
        assertFalse(result.isFailed());
        assertEquals(1000.0, (Double) result.getValue("frequency"), 0.01);

        result = SensorVerificationHelper.verifyFrequency(events, 950.0, 25.0);
        assertTrue(result.isFailed());
        assertEquals(1000.0, (Double) result.getValue("frequency"), 0.01);
    }

    /**
     * Test {@link SensorVerificationHelper#verifyJitter(TestSensorEvent[], double)}.
     */
    public void testVerifyJitter() {
        final int SAMPLE_SIZE = 100;
        float[] values = new float[SAMPLE_SIZE];
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            values[i] = i;
        }

        long[] timestamps1 = new long[SAMPLE_SIZE];  // 100 samples at 1000Hz
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            timestamps1[i] = i * 100000;
        }
        TestSensorEvent[] events1 = getSensorEvents(timestamps1, values);
        VerificationResult result = SensorVerificationHelper.verifyJitter(events1, 100000);
        assertFalse(result.isFailed());
        assertEquals(0.0, (Double) result.getValue("jitter95Percentile"), 0.01);

        long[] timestamps2 = new long[SAMPLE_SIZE];  // 90 samples at 1000Hz, 10 samples at 2000Hz
        long timestamp = 0;
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            timestamps2[i] = timestamp;
            timestamp += (i % 10 == 0) ? 500000 : 1000000;
        }
        TestSensorEvent[] events2 = getSensorEvents(timestamps2, values);
        result = SensorVerificationHelper.verifyJitter(events2, 100000);
        assertTrue(result.isFailed());
        assertNotNull(result.getValue("jitter"));
        assertNotNull(result.getValue("jitter95Percentile"));
    }

    /**
     * Test {@link SensorVerificationHelper#verifyMean(TestSensorEvent[], double[], double[])}.
     */
    public void testVerifyMean() {
        long[] timestamps = {0, 1, 2, 3, 4};
        float[] values1 = {0, 1, 2, 3, 4};
        float[] values2 = {1, 2, 3, 4, 5};
        float[] values3 = {0, 1, 4, 9, 16};
        TestSensorEvent[] events = getSensorEvents(timestamps, values1, values2, values3);

        double[] expected1 = {2.0, 3.0, 6.0};
        double[] threshold1 = {0.1, 0.1, 0.1};
        VerificationResult result = SensorVerificationHelper.verifyMean(events, expected1,
                threshold1);
        assertFalse(result.isFailed());
        double[] means = (double[]) result.getValue("means");
        assertEquals(2.0, means[0], 0.01);
        assertEquals(3.0, means[1], 0.01);
        assertEquals(6.0, means[2], 0.01);

        double[] expected2 = {2.5, 2.5, 5.5};
        double[] threshold2 = {0.6, 0.6, 0.6};
        result = SensorVerificationHelper.verifyMean(events, expected2, threshold2);
        assertFalse(result.isFailed());

        double[] expected3 = {2.5, 2.5, 5.5};
        double[] threshold3 = {0.1, 0.6, 0.6};
        result = SensorVerificationHelper.verifyMean(events, expected3, threshold3);
        assertTrue(result.isFailed());

        double[] expected4 = {2.5, 2.5, 5.5};
        double[] threshold4 = {0.6, 0.1, 0.6};
        result = SensorVerificationHelper.verifyMean(events, expected4, threshold4);
        assertTrue(result.isFailed());

        double[] expected5 = {2.5, 2.5, 5.5};
        double[] threshold5 = {0.6, 0.6, 0.1};
        result = SensorVerificationHelper.verifyMean(events, expected5, threshold5);
        assertTrue(result.isFailed());
    }

    /**
     * Test {@link SensorVerificationHelper#verifyMagnitude(TestSensorEvent[], double, double)}.
     */
    public void testVerifyMagnitude() {
        long[] timestamps = {0, 1, 2, 3, 4};
        float[] values1 = {0, 4, 3, 0, 6};
        float[] values2 = {3, 0, 4, 0, 0};
        float[] values3 = {4, 3, 0, 4, 0};
        TestSensorEvent[] events = getSensorEvents(timestamps, values1, values2, values3);

        double expected = 5.0;
        double threshold = 0.1;
        VerificationResult result = SensorVerificationHelper.verifyMagnitude(events, expected,
                threshold);
        assertFalse(result.isFailed());
        assertEquals(5.0, (Double) result.getValue("magnitude"), 0.01);

        expected = 4.5;
        threshold = 0.6;
        result = SensorVerificationHelper.verifyMagnitude(events, expected, threshold);
        assertFalse(result.isFailed());

        expected = 5.5;
        threshold = 0.6;
        result = SensorVerificationHelper.verifyMagnitude(events, expected, threshold);
        assertFalse(result.isFailed());

        expected = 4.5;
        threshold = 0.1;
        result = SensorVerificationHelper.verifyMagnitude(events, expected, threshold);
        assertTrue(result.isFailed());

        expected = 5.5;
        threshold = 0.1;
        result = SensorVerificationHelper.verifyMagnitude(events, expected, threshold);
        assertTrue(result.isFailed());
    }

    /**
     * Test {@link SensorVerificationHelper#verifySignum(TestSensorEvent[], int[], double[])}.
     */
    public void testVerifySignum() {
        long[] timestamps = {0};
        float[][] values = {{1}, {0.2f}, {0}, {-0.2f}, {-1}};
        TestSensorEvent[] events = getSensorEvents(timestamps, values);

        int[] expected1 = {1, 1, 0, -1, -1};
        double[] threshold1 = {0.1, 0.1, 0.1, 0.1, 0.1};
        VerificationResult result = SensorVerificationHelper.verifySignum(events, expected1,
                threshold1);
        assertFalse(result.isFailed());
        assertNotNull(result.getValue("means"));

        int[] expected2 = {1, 0, 0, 0, -1};
        double[] threshold2 = {0.5, 0.5, 0.5, 0.5, 0.5};
        result = SensorVerificationHelper.verifySignum(events, expected2, threshold2);
        assertFalse(result.isFailed());

        int[] expected3 = {0, 1, 0, -1, 0};
        double[] threshold3 = {1.5, 0.1, 0.1, 0.1, 1.5};
        result = SensorVerificationHelper.verifySignum(events, expected3, threshold3);
        assertFalse(result.isFailed());

        int[] expected4 = {1, 0, 0, 0, 1};
        double[] threshold4 = {0.5, 0.5, 0.5, 0.5, 0.5};
        result = SensorVerificationHelper.verifySignum(events, expected4, threshold4);
        assertTrue(result.isFailed());

        int[] expected5 = {-1, 0, 0, 0, -1};
        double[] threshold5 = {0.5, 0.5, 0.5, 0.5, 0.5};
        result = SensorVerificationHelper.verifySignum(events, expected5, threshold5);
        assertTrue(result.isFailed());
    }

    /**
     * Test {@link SensorVerificationHelper#verifyStandardDeviation(TestSensorEvent[], double[])}.
     */
    public void testVerifyStandardDeviation() {
        long[] timestamps = {0, 1, 2, 3, 4};
        float[] values1 = {0, 1, 2, 3, 4};  // sqrt(2.0)
        float[] values2 = {1, 2, 3, 4, 5};  // sqrt(2.0)
        float[] values3 = {0, 2, 4, 6, 8};  // sqrt(8.0)
        TestSensorEvent[] events = getSensorEvents(timestamps, values1, values2, values3);

        double[] threshold1 = {2, 2, 3};
        VerificationResult result = SensorVerificationHelper.verifyStandardDeviation(events,
                threshold1);
        assertFalse(result.isFailed());
        double[] means = (double[]) result.getValue("stddevs");
        assertEquals(Math.sqrt(2.0), means[0], 0.01);
        assertEquals(Math.sqrt(2.0), means[1], 0.01);
        assertEquals(Math.sqrt(8.0), means[2], 0.01);

        double[] threshold2 = {1, 2, 3};
        result = SensorVerificationHelper.verifyStandardDeviation(events, threshold2);
        assertTrue(result.isFailed());

        double[] threshold3 = {2, 1, 3};
        result = SensorVerificationHelper.verifyStandardDeviation(events, threshold3);
        assertTrue(result.isFailed());

        double[] threshold4 = {2, 2, 2};
        result = SensorVerificationHelper.verifyStandardDeviation(events, threshold4);
        assertTrue(result.isFailed());
    }

    private TestSensorEvent[] getSensorEvents(long[] timestamps, float[] ... values) {
        TestSensorEvent[] events = new TestSensorEvent[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            float[] eventValues = new float[values.length];
            for (int j = 0; j < values.length; j++) {
                eventValues[j] = values[j][i];
            }
            events[i] = new TestSensorEvent(null, timestamps[i], 0, eventValues);
        }
        return events;
    }
}
