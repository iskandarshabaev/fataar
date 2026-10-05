#include <jni.h>
#include "fataar_bridge.h"
#include "fataar_math.h"
int fataar_bridge_value(int input) { return fataar_math_value(input) * 2; }
JNIEXPORT jint JNICALL Java_com_fataar_nativeplayground_bridge_NativeBridge_value(JNIEnv* env, jclass clazz, jint input) {
    return fataar_bridge_value(input);
}
