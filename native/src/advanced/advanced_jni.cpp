#include "syscall_guard.h"
#include "anti_debug.h"
#include "hwid.h"
#include "integrity.h"
#include "../jni_bridge.h"
#include <jni.h>

// Additional JNI methods for EnhancedNativeBridge

using namespace anticheat::advanced;

extern "C" {

JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getHWID(JNIEnv* env, jclass) {
    std::string hwid = HWID::generate();
    return env->NewStringUTF(hwid.c_str());
}

JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getOSInfoNative(JNIEnv* env, jclass) {
    std::string os = HWID::getOSInfo();
    return env->NewStringUTF(os.c_str());
}

JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getHashedMacNative(JNIEnv* env, jclass) {
    std::string mac = HWID::getHashedMac();
    return env->NewStringUTF(mac.c_str());
}

JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_sha256File(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string result = Integrity::sha256File(p);
    env->ReleaseStringUTFChars(path, p);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_verifySelf(JNIEnv* env, jclass, jstring expected) {
    const char* e = env->GetStringUTFChars(expected, nullptr);
    Integrity integ;
    bool ok = integ.verifySelf(std::string(e));
    env->ReleaseStringUTFChars(expected, e);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_scanManualMappedDlls(JNIEnv* env, jclass) {
    SyscallGuard guard;
    auto vios = guard.scanForManualMappedDlls();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(vios.size(), strCls, nullptr);
    for (size_t i=0;i<vios.size();i++) {
        jstring s = env->NewStringUTF(vios[i].c_str());
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_scanHookedFunctions(JNIEnv* env, jclass) {
    SyscallGuard guard;
    auto vios = guard.scanForHookedFunctions();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(vios.size(), strCls, nullptr);
    for (size_t i=0;i<vios.size();i++) {
        jstring s = env->NewStringUTF(vios[i].c_str());
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_isDebuggerPresent(JNIEnv* env, jclass) {
    AntiDebug ad;
    return ad.isDebuggerPresent() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_scanAntiDebug(JNIEnv* env, jclass) {
    AntiDebug ad;
    auto vios = ad.scanAll();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(vios.size(), strCls, nullptr);
    for (size_t i=0;i<vios.size();i++) {
        jstring s = env->NewStringUTF(vios[i].c_str());
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_isDebuggerPresent(JNIEnv* env, jclass) {
    AntiDebug ad;
    return ad.isDebuggerPresent() ? JNI_TRUE : JNI_FALSE;
}

}
