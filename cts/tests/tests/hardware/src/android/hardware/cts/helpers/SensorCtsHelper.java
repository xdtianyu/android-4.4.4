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

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Environment;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Set of static helper methods for CTS tests.
 */
public class SensorCtsHelper {

    /**
     * Private constructor for static class.
     */
    private SensorCtsHelper() {}

    /**
     * Get the value of the 95th percentile using nearest rank algorithm.
     *
     * @throws IllegalArgumentException if the collection is null or empty
     */
    public static <TValue extends Comparable<? super TValue>> TValue get95PercentileValue(
            Collection<TValue> collection) {
        validateCollection(collection);

        List<TValue> arrayCopy = new ArrayList<TValue>(collection);
        Collections.sort(arrayCopy);

        // zero-based array index
        int arrayIndex = (int) Math.round(arrayCopy.size() * 0.95 + .5) - 1;

        return arrayCopy.get(arrayIndex);
    }

    /**
     * Calculates the mean for each of the values in the set of TestSensorEvents.
     *
     * @throws IllegalArgumentException if there are no events
     */
    public static double[] getMeans(TestSensorEvent[] events) {
        if (events.length == 0) {
            throw new IllegalArgumentException("Events cannot be empty");
        }

        double[] means = new double[events[0].values.length];
        for (TestSensorEvent event : events) {
            for (int i = 0; i < means.length; i++) {
                means[i] += event.values[i];
            }
        }
        for (int i = 0; i < means.length; i++) {
            means[i] /= events.length;
        }
        return means;
    }

    /**
     * Calculates the variance for each of the values in the set of TestSensorEvents.
     *
     * @throws IllegalArgumentException if there are no events
     */
    public static double[] getVariances(TestSensorEvent[] events) {
        double[] means = getMeans(events);
        double[] variances = new double[means.length];
        for (int i = 0; i < means.length; i++) {
            Collection<Double> squaredDiffs = new ArrayList<Double>(events.length);
            for (TestSensorEvent event : events) {
                double diff = event.values[i] - means[i];
                squaredDiffs.add(diff * diff);
            }
            variances[i] = getMean(squaredDiffs);
        }
        return variances;
    }

    /**
     * Calculates the standard deviation for each of the values in the set of TestSensorEvents.
     *
     * @throws IllegalArgumentException if there are no events
     */
    public static double[] getStandardDeviations(TestSensorEvent[] events) {
        double[] variances = getVariances(events);
        double[] stdDevs = new double[variances.length];
        for (int i = 0; i < variances.length; i++) {
            stdDevs[i] = Math.sqrt(variances[i]);
        }
        return stdDevs;
    }

    /**
     * Calculate the mean of a collection.
     *
     * @throws IllegalArgumentException if the collection is null or empty
     */
    public static <TValue extends Number> double getMean(Collection<TValue> collection) {
        validateCollection(collection);

        double sum = 0.0;
        for(TValue value : collection) {
            sum += value.doubleValue();
        }
        return sum / collection.size();
    }

    /**
     * Calculate the variance of a collection.
     *
     * @throws IllegalArgumentException if the collection is null or empty
     */
    public static <TValue extends Number> double getVariance(Collection<TValue> collection) {
        validateCollection(collection);

        double mean = getMean(collection);
        ArrayList<Double> squaredDifferences = new ArrayList<Double>();
        for(TValue value : collection) {
            double difference = mean - value.doubleValue();
            squaredDifferences.add(Math.pow(difference, 2));
        }

        return getMean(squaredDifferences);
    }

    /**
     * Calculate the standard deviation of a collection.
     *
     * @throws IllegalArgumentException if the collection is null or empty
     */
    public static <TValue extends Number> double getStandardDeviation(
            Collection<TValue> collection) {
        return Math.sqrt(getVariance(collection));
    }

    /**
     * Get a list containing the delay between sensor events.
     *
     * @param events The array of {@link TestSensorEvent}.
     * @return A list containing the delay between sensor events in nanoseconds.
     */
    public static List<Long> getTimestampDelayValues(TestSensorEvent[] events) {
        if (events.length < 2) {
            return new ArrayList<Long>();
        }
        List<Long> timestampDelayValues = new ArrayList<Long>(events.length - 1);
        for (int i = 1; i < events.length; i++) {
            timestampDelayValues.add(events[i].timestamp - events[i - 1].timestamp);
        }
        return timestampDelayValues;
    }

    /**
     * Get a list containing the jitter values for a collection of sensor events.
     *
     * @param events The array of {@link TestSensorEvent}.
     * @return A list containing the jitter values between each event.
     * @throws IllegalArgumentException if the number of events is less that 2.
     */
    public static List<Double> getJitterValues(TestSensorEvent[] events) {
        List<Long> timestampDelayValues = getTimestampDelayValues(events);
        double averageTimestampDelay = getMean(timestampDelayValues);

        List<Double> jitterValues = new ArrayList<Double>(timestampDelayValues.size());
        for (long timestampDelay : timestampDelayValues) {
            jitterValues.add(Math.abs(timestampDelay - averageTimestampDelay));
        }
        return jitterValues;
    }

    /**
     * NOTE:
     * - The bug report is usually written to /sdcard/Downloads
     * - In order for the test Instrumentation to gather useful data the following permissions are
     *   required:
     *      . android.permission.READ_LOGS
     *      . android.permission.DUMP
     */
    public static String collectBugreport(String collectorId)
            throws IOException, InterruptedException {
        String commands[] = new String[] {
                "dumpstate",
                "dumpsys",
                "logcat -d -v threadtime",
                "exit"
        };

        SimpleDateFormat dateFormat = new SimpleDateFormat("M-d-y_H:m:s.S");
        String outputFile = String.format(
                "%s/%s_%s",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                collectorId,
                dateFormat.format(new Date()));

        DataOutputStream processOutput = null;
        try {
            Process process = Runtime.getRuntime().exec("/system/bin/sh -");
            processOutput = new DataOutputStream(process.getOutputStream());

            for(String command : commands) {
                processOutput.writeBytes(String.format("%s >> %s\n", command, outputFile));
            }

            processOutput.flush();
            process.waitFor();

            Log.d(collectorId, String.format("Bug-Report collected at: %s", outputFile));
        } finally {
            if(processOutput != null) {
                try {
                    processOutput.close();
                } catch(IOException e) {}
            }
        }

        return outputFile;
    }

    /**
     * Get the default sensor for a given type.
     */
    public static Sensor getSensor(Context context, int sensorType) {
        SensorManager sensorManager = getSensorManager(context);
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if(sensor == null) {
            throw new SensorNotSupportedException(sensorType);
        }
        return sensor;
    }

    /**
     * Get all the sensors for a given type.
     */
    public static List<Sensor> getSensors(Context context, int sensorType) {
        SensorManager sensorManager = getSensorManager(context);
        List<Sensor> sensors = sensorManager.getSensorList(sensorType);
        if (sensors.size() == 0) {
            throw new SensorNotSupportedException(sensorType);
        }
        return sensors;
    }

    /**
     * Convert a period to frequency in Hz.
     */
    public static <TValue extends Number> double getFrequency(TValue period, TimeUnit unit) {
        return 1000000000 / (TimeUnit.NANOSECONDS.convert(1, unit) * period.doubleValue());
    }

    /**
     * Convert a frequency in Hz into a period.
     */
    public static <TValue extends Number> double getPeriod(TValue frequency, TimeUnit unit) {
        return 1000000000 / (TimeUnit.NANOSECONDS.convert(1, unit) * frequency.doubleValue());
    }

    /**
     * Convert number of seconds to number of microseconds.
     */
    public static int getSecondsAsMicroSeconds(int seconds) {
        return (int) TimeUnit.MICROSECONDS.convert(seconds, TimeUnit.SECONDS);
    }

    /**
     * Format an assertion message.
     *
     * @param verificationName The verification name
     * @param sensor The sensor under test
     * @param format The additional format string, use "" if blank
     * @param params The additional format params
     * @return The formatted string.
     */
    public static String formatAssertionMessage(
            String verificationName,
            Sensor sensor,
            String format,
            Object ... params) {
        return formatAssertionMessage(verificationName, null, sensor, format, params);
    }

    /**
     * Format an assertion message.
     *
     * @param verificationName The verification name
     * @param test The test, optional
     * @param sensor The sensor under test
     * @param format The additional format string, use "" if blank
     * @param params The additional format params
     * @return The formatted string.
     */
    public static String formatAssertionMessage(
            String verificationName,
            SensorTestOperation test,
            Sensor sensor,
            String format,
            Object ... params) {
        StringBuilder builder = new StringBuilder();

        // identify the verification
        builder.append(verificationName);
        builder.append("| ");
        // add test context information
        if(test != null) {
            builder.append(test.toString());
            builder.append("| ");
        }
        // add context information
        builder.append(SensorTestInformation.getSensorName(sensor.getType()));
        builder.append(", handle:");
        builder.append(sensor.getHandle());
        builder.append("| ");
        // add the custom formatting
        builder.append(String.format(format, params));

        return builder.toString();
    }

    /**
     * Validate that a collection is not null or empty.
     *
     * @throws IllegalStateException if collection is null or empty.
     */
    private static <T> void validateCollection(Collection<T> collection) {
        if(collection == null || collection.size() == 0) {
            throw new IllegalStateException("Collection cannot be null or empty");
        }
    }

    /**
     * Get the SensorManager.
     *
     * @throws IllegalStateException if the SensorManager is not present in the system.
     */
    private static SensorManager getSensorManager(Context context) {
        SensorManager sensorManager = (SensorManager) context.getSystemService(
                Context.SENSOR_SERVICE);
        if(sensorManager == null) {
            throw new IllegalStateException("SensorService is not present in the system.");
        }
        return sensorManager;
    }
}
