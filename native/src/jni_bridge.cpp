#include "jni_bridge.h"
#include "file_monitor.h"
#include "memory_guard.h"
#include "input_hook.h"
#include "sha256.h"
#include <iostream>
#include <memory>
#include <fstream>
#include <vector>

using namespace anticheat;

static std::unique_ptr<FileMonitor> g_fileMonitor;
static std::string g_modsPath;
static bool g_initialized = false;

jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_initNative(JNIEnv* env, jclass clazz, jstring modsPath) {
    try {
        const char* modsPathChars = env->GetStringUTFChars(modsPath, nullptr);
        std::string path(modsPathChars);
        env->ReleaseStringUTFChars(modsPath, modsPathChars);

        g_modsPath = path;
        g_fileMonitor = std::make_unique<FileMonitor>(path);
        g_initialized = true;

        std::cout << "[Anticheat Native] Initialized with modsPath=" << path << std::endl;
        return JNI_TRUE;
    } catch (std::exception& e) {
        std::cerr << "[Anticheat Native] initNative exception: " << e.what() << std::endl;
        return JNI_FALSE;
    }
}

void JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_startFileMonitor(JNIEnv* env, jclass clazz) {
    if (!g_initialized || !g_fileMonitor) {
        std::cerr << "[Anticheat Native] startFileMonitor called before init" << std::endl;
        return;
    }
    g_fileMonitor->start();
}

void JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_stopFileMonitor(JNIEnv* env, jclass clazz) {
    if (g_fileMonitor) g_fileMonitor->stop();
}

jobjectArray JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getFileViolationsAndClear(JNIEnv* env, jclass clazz) {
    std::vector<std::string> violations;
    if (g_fileMonitor) {
        violations = g_fileMonitor->getViolationsAndClear();
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(violations.size(), stringClass, nullptr);
    for (size_t i=0;i<violations.size();i++) {
        jstring jstr = env->NewStringUTF(violations[i].c_str());
        env->SetObjectArrayElement(array, i, jstr);
        env->DeleteLocalRef(jstr);
    }
    return array;
}

jobjectArray JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getMemoryViolations(JNIEnv* env, jclass clazz) {
    MemoryGuard guard;
    std::vector<std::string> violations = guard.scan();

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(violations.size(), stringClass, nullptr);
    for (size_t i=0;i<violations.size();i++) {
        jstring jstr = env->NewStringUTF(violations[i].c_str());
        env->SetObjectArrayElement(array, i, jstr);
        env->DeleteLocalRef(jstr);
    }
    return array;
}

jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_installInputHooks(JNIEnv* env, jclass clazz) {
    bool ok = InputHook::getInstance().install();
    return ok ? JNI_TRUE : JNI_FALSE;
}

void JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_uninstallInputHooks(JNIEnv* env, jclass clazz) {
    InputHook::getInstance().uninstall();
}

jlong JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getLastHardwareMouseClickTime(JNIEnv* env, jclass clazz) {
    return (jlong)InputHook::getInstance().getLastMouseClickTime();
}

jlong JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getLastHardwareKeyPressTime(JNIEnv* env, jclass clazz) {
    return (jlong)InputHook::getInstance().getLastKeyPressTime();
}

jlong JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_getLastMouseMoveTime(JNIEnv* env, jclass clazz) {
    return (jlong)InputHook::getInstance().getLastMouseMoveTime();
}

jboolean JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_wasLastInputInjected(JNIEnv* env, jclass clazz) {
    return InputHook::getInstance().wasLastInjected() ? JNI_TRUE : JNI_FALSE;
}

jstring JNICALL Java_com_anticheat_client_nativebridge_NativeBridge_computeJarHash(JNIEnv* env, jclass clazz, jstring jarPath) {
    try {
        const char* pathChars = env->GetStringUTFChars(jarPath, nullptr);
        std::string path(pathChars);
        env->ReleaseStringUTFChars(jarPath, pathChars);

        std::ifstream file(path, std::ios::binary);
        if (!file) {
            return env->NewStringUTF("FILE_NOT_FOUND");
        }
        std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        auto hash = SHA256::hash(data);
        // hex encode
        std::string hex;
        char buf[3];
        for (uint8_t b : hash) {
            snprintf(buf, sizeof(buf), "%02x", b);
            hex += buf;
        }
        return env->NewStringUTF(hex.c_str());
    } catch (...) {
        return env->NewStringUTF("ERROR");
    }
}
