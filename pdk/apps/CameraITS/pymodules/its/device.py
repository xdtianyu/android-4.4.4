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

import its.error
import os
import os.path
import sys
import re
import json
import time
import unittest
import socket
import subprocess
import hashlib
import numpy

class ItsSession(object):
    """Controls a device over adb to run ITS scripts.

    The script importing this module (on the host machine) prepares JSON
    objects encoding CaptureRequests, specifying sets of parameters to use
    when capturing an image using the Camera2 APIs. This class encapsualtes
    sending the requests to the device, monitoring the device's progress, and
    copying the resultant captures back to the host machine when done. TCP
    forwarded over adb is the transport mechanism used.

    The device must have ItsService.apk installed.

    Attributes:
        sock: The open socket.
    """

    # TODO: Handle multiple connected devices.
    # The adb program is used for communication with the device. Need to handle
    # the case of multiple devices connected. Currently, uses the "-d" param
    # to adb, which causes it to fail if there is more than one device.
    ADB = "adb -d"

    # Open a connection to localhost:6000, forwarded to port 6000 on the device.
    # TODO: Support multiple devices running over different TCP ports.
    IPADDR = '127.0.0.1'
    PORT = 6000
    BUFFER_SIZE = 4096

    # Seconds timeout on each socket operation.
    SOCK_TIMEOUT = 10.0

    PACKAGE = 'com.android.camera2.its'
    INTENT_START = 'com.android.camera2.its.START'

    def __init__(self):
        reboot_device_on_argv()
        # TODO: Figure out why "--user 0" is needed, and fix the problem
        _run('%s shell am force-stop --user 0 %s' % (self.ADB, self.PACKAGE))
        _run(('%s shell am startservice --user 0 -t text/plain '
              '-a %s') % (self.ADB, self.INTENT_START))
        _run('%s forward tcp:%d tcp:%d' % (self.ADB,self.PORT,self.PORT))
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.IPADDR, self.PORT))
        self.sock.settimeout(self.SOCK_TIMEOUT)

    def __del__(self):
        if self.sock:
            self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, type, value, traceback):
        return False

    def __read_response_from_socket(self):
        # Read a line (newline-terminated) string serialization of JSON object.
        chars = []
        while len(chars) == 0 or chars[-1] != '\n':
            chars.append(self.sock.recv(1))
        line = ''.join(chars)
        jobj = json.loads(line)
        # Optionally read a binary buffer of a fixed size.
        buf = None
        if jobj.has_key("bufValueSize"):
            n = jobj["bufValueSize"]
            buf = bytearray(n)
            view = memoryview(buf)
            while n > 0:
                nbytes = self.sock.recv_into(view, n)
                view = view[nbytes:]
                n -= nbytes
            buf = numpy.frombuffer(buf, dtype=numpy.uint8)
        return jobj, buf

    def get_camera_properties(self):
        """Get the camera properties object for the device.

        Returns:
            The Python dictionary object for the CameraProperties object.
        """
        cmd = {}
        cmd["cmdName"] = "getCameraProperties"
        self.sock.send(json.dumps(cmd) + "\n")
        data,_ = self.__read_response_from_socket()
        if data['tag'] != 'cameraProperties':
            raise its.error.Error('Invalid command response')
        return data['objValue']['cameraProperties']

    def do_3a(self, region_ae, region_awb, region_af,
              do_ae=True, do_awb=True, do_af=True):
        """Perform a 3A operation on the device.

        Triggers some or all of AE, AWB, and AF, and returns once they have
        converged. Uses the vendor 3A that is implemented inside the HAL.

        Throws an assertion if 3A fails to converge.

        Args:
            region_ae: Normalized rect. (x,y,w,h) specifying the AE region.
            region_awb: Normalized rect. (x,y,w,h) specifying the AWB region.
            region_af: Normalized rect. (x,y,w,h) specifying the AF region.

        Returns:
            Five values:
            * AE sensitivity; None if do_ae is False
            * AE exposure time; None if do_ae is False
            * AWB gains (list); None if do_awb is False
            * AWB transform (list); None if do_awb is false
            * AF focus position; None if do_af is false
        """
        print "Running vendor 3A on device"
        cmd = {}
        cmd["cmdName"] = "do3A"
        cmd["regions"] = {"ae": region_ae, "awb": region_awb, "af": region_af}
        cmd["triggers"] = {"ae": do_ae, "af": do_af}
        self.sock.send(json.dumps(cmd) + "\n")

        # Wait for each specified 3A to converge.
        ae_sens = None
        ae_exp = None
        awb_gains = None
        awb_transform = None
        af_dist = None
        while True:
            data,_ = self.__read_response_from_socket()
            vals = data['strValue'].split()
            if data['tag'] == 'aeResult':
                ae_sens, ae_exp = [int(i) for i in vals]
            elif data['tag'] == 'afResult':
                af_dist = float(vals[0])
            elif data['tag'] == 'awbResult':
                awb_gains = [float(f) for f in vals[:4]]
                awb_transform = [float(f) for f in vals[4:]]
            elif data['tag'] == '3aDone':
                break
            else:
                raise its.error.Error('Invalid command response')
        if (do_ae and ae_sens == None or do_awb and awb_gains == None
                                      or do_af and af_dist == None):
            raise its.error.Error('3A failed to converge')
        return ae_sens, ae_exp, awb_gains, awb_transform, af_dist

    def do_capture(self, cap_request, out_surface=None):
        """Issue capture request(s), and read back the image(s) and metadata.

        The main top-level function for capturing one or more images using the
        device. Captures a single image if cap_request is a single object, and
        captures a burst if it is a list of objects.

        The out_surface field can specify the width, height, and format of
        the captured image. The format may be "yuv" or "jpeg". The default is
        a YUV420 frame ("yuv") corresponding to a full sensor frame.

        Example of a single capture request:

            {
                "android.sensor.exposureTime": 100*1000*1000,
                "android.sensor.sensitivity": 100
            }

        Example of a list of capture requests:

            [
                {
                    "android.sensor.exposureTime": 100*1000*1000,
                    "android.sensor.sensitivity": 100
                },
                {
                    "android.sensor.exposureTime": 100*1000*1000,
                    "android.sensor.sensitivity": 200
                }
            ]

        Example of an output surface specification:

            {
                "width": 640,
                "height": 480,
                "format": "yuv"
            }

        Args:
            cap_request: The Python dict/list specifying the capture(s), which
                will be converted to JSON and sent to the device.
            out_surface: (Optional) the width,height,format to use for all
                captured images.

        Returns:
            An object or list of objects (depending on whether the request was
            for a single or burst capture), where each object contains the
            following fields:
            * data: the image data as a numpy array of bytes.
            * width: the width of the captured image.
            * height: the height of the captured image.
            * format: the format of the image, in ["yuv", "jpeg"].
            * metadata: the capture result object (Python dictionaty).
        """
        cmd = {}
        cmd["cmdName"] = "doCapture"
        if not isinstance(cap_request, list):
            cmd["captureRequests"] = [cap_request]
        else:
            cmd["captureRequests"] = cap_request
        if out_surface is not None:
            cmd["outputSurface"] = out_surface
        n = len(cmd["captureRequests"])
        print "Capturing %d image%s" % (n, "s" if n>1 else "")
        self.sock.send(json.dumps(cmd) + "\n")

        # Wait for n images and n metadata responses from the device.
        bufs = []
        mds = []
        fmts = []
        width = None
        height = None
        while len(bufs) < n or len(mds) < n:
            jsonObj,buf = self.__read_response_from_socket()
            if jsonObj['tag'] in ['jpegImage','yuvImage'] and buf is not None:
                bufs.append(buf)
                fmts.append(jsonObj['tag'][:-5])
            elif jsonObj['tag'] == 'captureResults':
                mds.append(jsonObj['objValue']['captureResult'])
                width = jsonObj['objValue']['width']
                height = jsonObj['objValue']['height']

        objs = []
        for i in range(n):
            obj = {}
            obj["data"] = bufs[i]
            obj["width"] = width
            obj["height"] = height
            obj["format"] = fmts[i]
            obj["metadata"] = mds[i]
            objs.append(obj)
        return objs if n>1 else objs[0]

def _run(cmd):
    """Replacement for os.system, with hiding of stdout+stderr messages.
    """
    with open(os.devnull, 'wb') as devnull:
        subprocess.check_call(
                cmd.split(), stdout=devnull, stderr=subprocess.STDOUT)

def reboot_device(sleep_duration=30):
    """Function to reboot a device and block until it is ready.

    Can be used at the start of a test to get the device into a known good
    state. Will disconnect any other adb sessions, so this function is not
    a part of the ItsSession class (which encapsulates a session with a
    device.)

    Args:
        sleep_duration: (Optional) the length of time to sleep (seconds) after
            the device comes online before returning; this gives the device
            time to finish booting.
    """
    print "Rebooting device"
    _run("%s reboot" % (ItsSession.ADB));
    _run("%s wait-for-device" % (ItsSession.ADB))
    time.sleep(sleep_duration)
    print "Reboot complete"

def reboot_device_on_argv():
    """Examine sys.argv, and reboot if the "reboot" arg is present.

    If the script command line contains either:

        reboot
        reboot=30

    then the device will be rebooted, and if the optional numeric arg is
    present, then that will be the sleep duration passed to the reboot
    call.

    Returns:
        Boolean, indicating whether the device was rebooted.
    """
    for s in sys.argv[1:]:
        if s[:6] == "reboot":
            if len(s) > 7 and s[6] == "=":
                duration = int(s[7:])
                reboot_device(duration)
            elif len(s) == 6:
                reboot_device()
            return True
    return False

class __UnitTest(unittest.TestCase):
    """Run a suite of unit tests on this module.
    """

    # TODO: Add some unit tests.
    None

if __name__ == '__main__':
    unittest.main()

