#include <jni.h>
#include <fataar_math.h>
#include <detail/offset.h>
#include <fataar_bridge.h>
#include <fataar_registered.h>
#include <fataar_host.h>
#include <fataar_static.h>
#include <fataar_headers.h>
JNIEXPORT jint JNICALL Java_com_fataar_nativeplayground_consumer_ConsumerProbe_nativeValue(JNIEnv* env, jclass clazz, jint input) {
    return fataar_math_value(input) + fataar_bridge_value(input) + fataar_registered_value(input)
        + fataar_host_value(input) + fataar_static_value(input) + fataar_header_value(input) + FATAAR_OFFSET;
}
