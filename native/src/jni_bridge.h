#pragma once
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_initNative(JNIEnv* env, jclass clazz, jstring modsPath);
JNIEXPORT void JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_startFileMonitor(JNIEnv* env, jclass clazz);
JNIEXPORT void JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_stopFileMonitor(JNIEnv* env, jclass clazz);
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getFileViolationsAndClear(JNIEnv* env, jclass clazz);
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getMemoryViolations(JNIEnv* env, jclass clazz);
JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_installInputHooks(JNIEnv* env, jclass clazz);
JNIEXPORT void JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_uninstallInputHooks(JNIEnv* env, jclass clazz);
JNIEXPORT jlong JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getLastHardwareMouseClickTime(JNIEnv* env, jclass clazz);
JNIEXPORT jlong JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getLastHardwareKeyPressTime(JNIEnv* env, jclass clazz);
JNIEXPORT jlong JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getLastMouseMoveTime(JNIEnv* env, jclass clazz);
JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_wasLastInputInjected(JNIEnv* env, jclass clazz);
JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_computeJarHash(JNIEnv* env, jclass clazz, jstring jarPath);

#ifdef __cplusplus
}
#endif
