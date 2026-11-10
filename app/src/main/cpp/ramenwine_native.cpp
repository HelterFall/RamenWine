#include <jni.h>

namespace {
constexpr const char* kBuildInfo =
    "RamenWine native bridge 0.1.0 (ARM64); offline Wine runtime package bundled in APK";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_ramenwine_runtime_NativeRuntime_nativeBuildInfo(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(kBuildInfo);
}
