# Copyright 2013 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import its.image
import its.device
import its.objects
import its.target
import pylab
import os.path
import matplotlib
import matplotlib.pyplot

def main():
    """Test that the android.sensor.exposureTime parameter is applied.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    # Pass/fail threshold.
    THRESHOLD_MIN_DIFF = 0.02

    NUM_STEPS = 5

    exp_times = None
    r_means = []
    g_means = []
    b_means = []

    with its.device.ItsSession() as cam:
        _,sens = its.target.get_target_exposure_combos(cam)["midExposureTime"]
        props = cam.get_camera_properties()
        expt_range = props['android.sensor.info.exposureTimeRange']
        expt_step = (expt_range[1] - expt_range[0]) / float(NUM_STEPS-1)
        exp_times = [expt_range[0] + i * expt_step for i in range(NUM_STEPS)]

        for e in exp_times:
            req = its.objects.manual_capture_request(sens, e)
            cap = cam.do_capture(req)
            img = its.image.convert_capture_to_rgb_image(cap)
            its.image.write_image(
                    img, "%s_time=%03dms.jpg" % (NAME, e))
            tile = its.image.get_image_patch(img, 0.45, 0.45, 0.1, 0.1)
            rgb_means = its.image.compute_image_means(tile)
            r_means.append(rgb_means[0])
            g_means.append(rgb_means[1])
            b_means.append(rgb_means[2])

    # Draw a plot.
    pylab.plot(exp_times, r_means, 'r')
    pylab.plot(exp_times, g_means, 'g')
    pylab.plot(exp_times, b_means, 'b')
    pylab.ylim([0,1])
    matplotlib.pyplot.savefig("%s_plot_means.png" % (NAME))

    # Test for pass/fail: check that each shot is brighter than the previous.
    for means in [r_means, g_means, b_means]:
        for i in range(len(means)-1):
            assert(means[i+1] - means[i] > THRESHOLD_MIN_DIFF)

if __name__ == '__main__':
    main()

