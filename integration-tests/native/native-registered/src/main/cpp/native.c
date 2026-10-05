#include <jni.h>
#include "fataar_registered.h"
int fataar_registered_value(int input) { return input * 3; }
static jint value(JNIEnv* env, jclass clazz, jint input) { return fataar_registered_value(input); }
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = 0;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass type = (*env)->FindClass(env, "com/fataar/nativeplayground/registered/RegisteredBridge");
    if (!type) return JNI_ERR;
    JNINativeMethod methods[] = {{"value", "(I)I", (void*)value}};
    if ((*env)->RegisterNatives(env, type, methods, 1) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
