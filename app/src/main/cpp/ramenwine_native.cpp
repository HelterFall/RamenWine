#include <jni.h>

namespace {
constexpr const char* kBuildInfo =
    "RamenWine native bridge 0.1.0 (ARM64); Wine runtime not bundled yet";
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_ramenwine_runtime_NativeRuntime_nativeBuildInfo(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(kBuildInfo);
}
