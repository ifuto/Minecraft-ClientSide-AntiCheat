#include "process_list.h"
#ifdef _WIN32
#include <windows.h>
#include <tlhelp32.h>
#else
#include <dirent.h>
#include <fstream>
#endif
#include <string>
namespace anticheat { namespace advanced {
std::vector<std::string> ProcessList::getRunningApps() {
    std::vector<std::string> r;
#ifdef _WIN32
    HANDLE hSnap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS,0);
    if(hSnap==INVALID_HANDLE_VALUE) return r;
    PROCESSENTRY32 pe; pe.dwSize=sizeof(pe);
    if(Process32First(hSnap,&pe)){ do{ r.push_back(pe.szExeFile); }while(Process32Next(hSnap,&pe)); }
    CloseHandle(hSnap);
#else
    DIR* d=opendir("/proc"); if(!d) return r; struct dirent* e;
    while((e=readdir(d))!=nullptr){ char* end; long pid=strtol(e->d_name,&end,10); if(*end!='\0') continue;
        std::ifstream f(std::string("/proc/")+e->d_name+"/comm"); std::string comm; if(f) std::getline(f,comm); if(!comm.empty()) r.push_back(comm); }
    closedir(d);
#endif
    return r;
}
std::vector<std::string> ProcessList::getRunningAppsDetailed(){
    std::vector<std::string> r;
#ifdef _WIN32
    HANDLE hSnap=CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS,0); if(hSnap==INVALID_HANDLE_VALUE) return r;
    PROCESSENTRY32 pe; pe.dwSize=sizeof(pe);
    if(Process32First(hSnap,&pe)){ do{ r.push_back(std::to_string(pe.th32ProcessID)+":"+pe.szExeFile); }while(Process32Next(hSnap,&pe)); }
    CloseHandle(hSnap);
#else
    DIR* d=opendir("/proc"); if(!d) return r; struct dirent* e;
    while((e=readdir(d))!=nullptr){ char* end; long pid=strtol(e->d_name,&end,10); if(*end!='\0') continue;
        std::ifstream f(std::string("/proc/")+e->d_name+"/comm"); std::string comm; if(f) std::getline(f,comm); if(!comm.empty()) r.push_back(std::to_string(pid)+":"+comm); }
    closedir(d);
#endif
    return r;
}
}}
