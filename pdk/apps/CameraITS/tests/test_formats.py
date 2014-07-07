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
import copy

def main():
    """Test that the reported sizes and formats for image capture work.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    with its.device.ItsSession() as cam:
        props = cam.get_camera_properties()
        for size in props['android.scaler.availableProcessedSizes']:
            req = its.objects.manual_capture_request(100,10*1000*1000)
            out_surface = copy.deepcopy(size)
            out_surface["format"] = "yuv"
            cap = cam.do_capture(req, out_surface)
            assert(cap["format"] == "yuv")
            assert(cap["width"] == size["width"])
            assert(cap["height"] == size["height"])
            print "Captured YUV %dx%d" % (cap["width"], cap["height"])
        for size in props['android.scaler.availableJpegSizes']:
            req = its.objects.manual_capture_request(100,10*1000*1000)
            out_surface = copy.deepcopy(size)
            out_surface["format"] = "jpg"
            cap = cam.do_capture(req, out_surface)
            assert(cap["format"] == "jpeg")
            assert(cap["width"] == size["width"])
            assert(cap["height"] == size["height"])
            img = its.image.decompress_jpeg_to_rgb_image(cap["data"])
            assert(img.shape[0] == size["height"])
            assert(img.shape[1] == size["width"])
            assert(img.shape[2] == 3)
            print "Captured JPEG %dx%d" % (cap["width"], cap["height"])

if __name__ == '__main__':
    main()

