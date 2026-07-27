#include <iostream>
// Entry point for standalone test of native lib (not used when loaded as JNI)
#ifdef _WIN32
#include <windows.h>
BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    switch (ul_reason_for_call) {
    case DLL_PROCESS_ATTACH:
        std::cout << "[Anticheat Native] DLL_PROCESS_ATTACH" << std::endl;
        break;
    case DLL_PROCESS_DETACH:
        std::cout << "[Anticheat Native] DLL_PROCESS_DETACH" << std::endl;
        break;
    }
    return TRUE;
}
#endif
