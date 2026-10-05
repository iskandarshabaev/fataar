#include <jni.h>
#include "fataar_host.h"
#include "fataar_math.h"
int fataar_host_value(int input) { return fataar_math_value(input) + 100; }
JNIEXPORT jint JNICALL Java_com_fataar_nativeplayground_sdk_NativeSdk_hostValue(JNIEnv* env, jclass clazz, jint input) {
    return fataar_host_value(input);
}
