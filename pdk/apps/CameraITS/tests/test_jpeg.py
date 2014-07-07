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
import os.path
import Image
import shutil
import numpy
import math
import copy

def main():
    """Test that converted YUV images and device JPEG images look the same.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    THRESHOLD_MAX_RMS_DIFF = 0.1

    with its.device.ItsSession() as cam:
        props = cam.get_camera_properties()

        # YUV
        req = its.objects.manual_capture_request(100,100*1000*1000)
        size = props['android.scaler.availableProcessedSizes'][0]
        out_surface = copy.deepcopy(size)
        out_surface["format"] = "yuv"
        cap = cam.do_capture(req, out_surface)
        img = its.image.convert_capture_to_rgb_image(cap)
        its.image.write_image(img, "%s_fmt=yuv.jpg" % (NAME))
        tile = its.image.get_image_patch(img, 0.45, 0.45, 0.1, 0.1)
        rgb0 = its.image.compute_image_means(tile)

        # JPEG
        req = its.objects.manual_capture_request(100,100*1000*1000)
        size = props['android.scaler.availableJpegSizes'][0]
        out_surface = copy.deepcopy(size)
        out_surface["format"] = "jpg"
        cap = cam.do_capture(req, out_surface)
        img = its.image.decompress_jpeg_to_rgb_image(cap["data"])
        its.image.write_image(img, "%s_fmt=jpg.jpg" % (NAME))
        tile = its.image.get_image_patch(img, 0.45, 0.45, 0.1, 0.1)
        rgb1 = its.image.compute_image_means(tile)

        rms_diff = math.sqrt(
                sum([pow(rgb0[i] - rgb1[i], 2.0) for i in range(3)]) / 3.0)
        print "RMS difference:", rms_diff
        assert(rms_diff < THRESHOLD_MAX_RMS_DIFF)

if __name__ == '__main__':
    main()

