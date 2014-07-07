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
import pylab
import os.path
import matplotlib
import matplotlib.pyplot

def main():
    """Test that the android.sensor.exposureTime parameter is applied properly
    within a burst. Inspects the output metadata.
    """
    NAME = os.path.basename(__file__).split(".")[0]

    exp_times = range(1*1000*1000, 100*1000*1000, 9*1000*1000)
    reqs = [its.objects.manual_capture_request(100,e) for e in exp_times]

    with its.device.ItsSession() as cam:
        caps = cam.do_capture(reqs)
        for i,cap in enumerate(caps):
            e_req = exp_times[i]*1000*1000
            e_res = cap["metadata"]["android.sensor.exposureTime"]
            print e_req, e_res

if __name__ == '__main__':
    main()

