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
import sys
import numpy
import Image
import pprint
import math
import pylab
import os.path
import matplotlib
import matplotlib.pyplot

def main():
    """Test that device processing can be inverted to linear pixels.

    Captures a sequence of shots with the device pointed at a uniform
    target. Attempts to invert all the ISP processing to get back to
    linear R,G,B pixel data.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    NUM_STEPS = 5

    # TODO: Query the allowable tonemap curve sizes; here, it's hardcoded to
    # a length=64 list of tuples. The max allowed length should be inside the
    # camera properties object.
    L = 64
    LM1 = float(L-1)

    gamma_lut = numpy.array(
            sum([[i/LM1, math.pow(i/LM1, 1/2.2)] for i in xrange(L)], []))
    inv_gamma_lut = numpy.array(
            sum([[i/LM1, math.pow(i/LM1, 2.2)] for i in xrange(L)], []))

    with its.device.ItsSession() as cam:
        expt,_ = its.target.get_target_exposure_combos(cam)["midSensitivity"]
        props = cam.get_camera_properties()
        sens_range = props['android.sensor.info.sensitivityRange']
        sens_step = (sens_range[1] - sens_range[0]) / float(NUM_STEPS-1)
        sensitivities = [sens_range[0] + i * sens_step for i in range(NUM_STEPS)]

        req = its.objects.manual_capture_request(0, expt)
        req["android.blackLevel.lock"] = True
        req["android.tonemap.mode"] = 0
        req["android.tonemap.curveRed"] = gamma_lut.tolist()
        req["android.tonemap.curveGreen"] = gamma_lut.tolist()
        req["android.tonemap.curveBlue"] = gamma_lut.tolist()

        r_means = []
        g_means = []
        b_means = []

        for sens in sensitivities:
            req["android.sensor.sensitivity"] = sens
            cap = cam.do_capture(req)
            img = its.image.convert_capture_to_rgb_image(cap)
            its.image.write_image(
                    img, "%s_sens=%04d.jpg" % (NAME, sens))
            img = its.image.apply_lut_to_image(img, inv_gamma_lut[1::2] * LM1)
            tile = its.image.get_image_patch(img, 0.45, 0.45, 0.1, 0.1)
            rgb_means = its.image.compute_image_means(tile)
            r_means.append(rgb_means[0])
            g_means.append(rgb_means[1])
            b_means.append(rgb_means[2])

        pylab.plot(sensitivities, r_means, 'r')
        pylab.plot(sensitivities, g_means, 'g')
        pylab.plot(sensitivities, b_means, 'b')

    pylab.ylim([0,1])
    matplotlib.pyplot.savefig("%s_plot_means.png" % (NAME))

if __name__ == '__main__':
    main()

