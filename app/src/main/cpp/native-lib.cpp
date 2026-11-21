#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_gameinstaller_NativeBridge_version(JNIEnv *env, jobject) {
    return env->NewStringUTF("gameinstaller-native 0.1 (innoextract/unarc ще не підключені)");
}
