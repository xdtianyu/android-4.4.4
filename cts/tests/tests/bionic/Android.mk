LOCAL_PATH := $(call my-dir)

test_executable := bionic-unit-tests-cts

include $(CLEAR_VARS)

LOCAL_MODULE := $(test_executable)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/nativetest

LOCAL_ADDITION_DEPENDENCIES := \
    $(LOCAL_PATH)/Android.mk \

LOCAL_SHARED_LIBRARIES += \
    libstlport \
    libdl \

LOCAL_WHOLE_STATIC_LIBRARIES += \
    libBionicTests \

LOCAL_STATIC_LIBRARIES += \
    libgtest \
    libgtest_main \

LOCAL_CTS_TEST_PACKAGE := android.bionic
include $(BUILD_CTS_EXECUTABLE)
