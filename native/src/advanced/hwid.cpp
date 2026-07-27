#include "hwid.h"
#include "../sha256.h"

#ifdef _WIN32
#include <windows.h>
#include <iphlpapi.h>
#pragma comment(lib, "iphlpapi.lib")
#else
#include <unistd.h>
#include <sys/utsname.h>
#endif

#include <sstream>
#include <iomanip>

namespace anticheat {
namespace advanced {

std::string HWID::getOSInfo() {
#ifdef _WIN32
    OSVERSIONINFOA info;
    info.dwOSVersionInfoSize = sizeof(info);
    GetVersionExA(&info);
    std::ostringstream oss;
    oss << "Windows " << info.dwMajorVersion << "." << info.dwMinorVersion << " build " << info.dwBuildNumber;
    return oss.str();
#else
    struct utsname unameData;
    if (uname(&unameData)==0) {
        return std::string(unameData.sysname) + " " + unameData.release + " " + unameData.machine;
    }
    return "Unknown Unix";
#endif
}

std::string HWID::getHashedMac() {
#ifdef _WIN32
    ULONG outBufLen = 0;
    GetAdaptersInfo(nullptr, &outBufLen);
    BYTE* buffer = new BYTE[outBufLen];
    PIP_ADAPTER_INFO pAdapterInfo = (PIP_ADAPTER_INFO)buffer;
    std::string macConcat;
    if (GetAdaptersInfo(pAdapterInfo, &outBufLen)==NO_ERROR) {
        PIP_ADAPTER_INFO pAdapter = pAdapterInfo;
        while (pAdapter) {
            char macStr[18];
            sprintf(macStr, "%02X-%02X-%02X-%02X-%02X-%02X",
                pAdapter->Address[0], pAdapter->Address[1], pAdapter->Address[2],
                pAdapter->Address[3], pAdapter->Address[4], pAdapter->Address[5]);
            macConcat += macStr;
            macConcat += ";";
            pAdapter = pAdapter->Next;
        }
    }
    delete[] buffer;
    if (macConcat.empty()) macConcat = "no-mac";
    uint64_t h = SHA256::hash64_trunc(macConcat);
    std::ostringstream oss;
    oss << std::hex << std::setw(16) << std::setfill('0') << h;
    return oss.str();
#else
    return "linux-no-mac-hash";
#endif
}

std::string HWID::generate() {
    std::string raw = getOSInfo() + "|" + getHashedMac() + "|";
#ifdef _WIN32
    char compName[MAX_COMPUTERNAME_LENGTH+1];
    DWORD size = sizeof(compName);
    if (GetComputerNameA(compName, &size)) raw += compName;
    else raw += "unknown";
#else
    char host[256];
    if (gethostname(host, sizeof(host))==0) raw += host;
    else raw += "unknown";
#endif
    auto hash = SHA256::hash(raw);
    std::ostringstream oss;
    for (int i=0;i<16;i++) {
        oss << std::hex << std::setw(2) << std::setfill('0') << (int)hash[i];
    }
    return oss.str();
}

}
}
