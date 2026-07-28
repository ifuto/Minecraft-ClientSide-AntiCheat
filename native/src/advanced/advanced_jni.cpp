#include "syscall_guard.h"
#include "anti_debug.h"
#include "hwid.h"
#include "integrity.h"
#include "process_list.h"
#include "../jni_bridge.h"
#include <jni.h>
using namespace anticheat::advanced;
extern "C" {
JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getHWID(JNIEnv* env,jclass){ return env->NewStringUTF(HWID::generate().c_str()); }
JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getOSInfoNative(JNIEnv* env,jclass){ return env->NewStringUTF(HWID::getOSInfo().c_str()); }
JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getHashedMacNative(JNIEnv* env,jclass){ return env->NewStringUTF(HWID::getHashedMac().c_str()); }
JNIEXPORT jstring JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_sha256File(JNIEnv* env,jclass,jstring p){ const char* s=env->GetStringUTFChars(p,nullptr); auto r=Integrity::sha256File(s); env->ReleaseStringUTFChars(p,s); return env->NewStringUTF(r.c_str()); }
JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_verifySelf(JNIEnv* env,jclass,jstring e){ const char* s=env->GetStringUTFChars(e,nullptr); Integrity i; bool ok=i.verifySelf(s); env->ReleaseStringUTFChars(e,s); return ok?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_scanManualMappedDlls(JNIEnv* env,jclass){ SyscallGuard g; auto v=g.scanForManualMappedDlls(); jclass c=env->FindClass("java/lang/String"); jobjectArray a=env->NewObjectArray(v.size(),c,nullptr); for(size_t i=0;i<v.size();i++){ jstring s=env->NewStringUTF(v[i].c_str()); env->SetObjectArrayElement(a,i,s); env->DeleteLocalRef(s);} return a; }
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_scanHookedFunctions(JNIEnv* env,jclass){ SyscallGuard g; auto v=g.scanForHookedFunctions(); jclass c=env->FindClass("java/lang/String"); jobjectArray a=env->NewObjectArray(v.size(),c,nullptr); for(size_t i=0;i<v.size();i++){ jstring s=env->NewStringUTF(v[i].c_str()); env->SetObjectArrayElement(a,i,s); env->DeleteLocalRef(s);} return a; }
JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_isDebuggerPresent(JNIEnv* env,jclass){ AntiDebug ad; return ad.isDebuggerPresent()?JNI_TRUE:JNI_FALSE; }
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_scanAntiDebug(JNIEnv* env,jclass){ AntiDebug ad; auto v=ad.scanAll(); jclass c=env->FindClass("java/lang/String"); jobjectArray a=env->NewObjectArray(v.size(),c,nullptr); for(size_t i=0;i<v.size();i++){ jstring s=env->NewStringUTF(v[i].c_str()); env->SetObjectArrayElement(a,i,s); env->DeleteLocalRef(s);} return a; }
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getRunningApps(JNIEnv* env,jclass){ ProcessList pl; auto v=pl.getRunningApps(); jclass c=env->FindClass("java/lang/String"); jobjectArray a=env->NewObjectArray(v.size(),c,nullptr); for(size_t i=0;i<v.size();i++){ jstring s=env->NewStringUTF(v[i].c_str()); env->SetObjectArrayElement(a,i,s); env->DeleteLocalRef(s);} return a; }
JNIEXPORT jobjectArray JNICALL Java_com_anticheat_client_nativebridge_EnhancedNativeBridge_getRunningAppsDetailed(JNIEnv* env,jclass){ ProcessList pl; auto v=pl.getRunningAppsDetailed(); jclass c=env->FindClass("java/lang/String"); jobjectArray a=env->NewObjectArray(v.size(),c,nullptr); for(size_t i=0;i<v.size();i++){ jstring s=env->NewStringUTF(v[i].c_str()); env->SetObjectArrayElement(a,i,s); env->DeleteLocalRef(s);} return a; }
JNIEXPORT jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_isDebuggerPresent(JNIEnv* env,jclass){ AntiDebug ad; return ad.isDebuggerPresent()?JNI_TRUE:JNI_FALSE; }
}
