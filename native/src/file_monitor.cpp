#include "file_monitor.h"
#include "hashes.h"
#include "sha256.h"
#include <algorithm>
#include <cctype>
#include <iostream>

#ifdef _WIN32
#include <windows.h>
#else
#include <sys/inotify.h>
#include <unistd.h>
#include <dirent.h>
#include <cstring>
#include <errno.h>
#endif

namespace anticheat {

FileMonitor::FileMonitor(const std::string& modsPath) : modsPath(modsPath) {}

FileMonitor::~FileMonitor() {
    stop();
}

void FileMonitor::start() {
    if (running) return;
    running = true;
    worker = std::thread(&FileMonitor::workerLoop, this);
}

void FileMonitor::stop() {
    if (!running) return;
    running = false;
    if (worker.joinable()) {
        // On Windows, we need to interrupt ReadDirectoryChanges? For simplicity, we just wait with timeout
        // We'll try to join after a short delay
        worker.join();
    }
}

std::vector<std::string> FileMonitor::getViolationsAndClear() {
    std::lock_guard<std::mutex> lock(violMutex);
    std::vector<std::string> copy = violations;
    violations.clear();
    return copy;
}

void FileMonitor::setCallback(ViolationCallback cb) {
    callback = cb;
}

uint64_t FileMonitor::hash64(const std::string& s) {
    return SHA256::hash64_trunc(s);
}

bool FileMonitor::containsBlacklistedSubstring(const std::string& fileName) {
    std::string lower = fileName;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);

    // Check direct hash containment using substrings
    // Complexity O(n^2) but fileName length small
    size_t len = lower.length();
    // Also normalized without special chars
    std::string normalized;
    for (char c : lower) if (isalnum((unsigned char)c) || c=='-' || c=='.') normalized+=c;

    auto check = [&](const std::string& str) -> bool {
        size_t slen = str.length();
        for (size_t i=0;i<slen;i++) {
            for (size_t j=i+3; j<=slen && j-i<=30; ++j) {
                std::string sub = str.substr(i, j-i);
                uint64_t h = hash64(sub);
                if (critical_file_hashes.find(h) != critical_file_hashes.end()) {
                    return true;
                }
            }
        }
        // also full string
        if (critical_file_hashes.find(hash64(str)) != critical_file_hashes.end()) return true;
        return false;
    };

    if (check(lower)) return true;
    if (normalized != lower && check(normalized)) return true;

    // Additional direct substring search for obvious bypass? Use precomputed list in hashes.h comment for fallback fast path
    // Since we store hash only, we need to brute force substrings as above. For efficiency we could also have plaintext fallback in debug.
    return false;
}

bool FileMonitor::checkFileName(const std::string& fileName) {
    if (containsBlacklistedSubstring(fileName)) {
        {
            std::lock_guard<std::mutex> lock(violMutex);
            violations.push_back(fileName);
        }
        if (callback) callback(fileName);
        std::cout << "[Anticheat Native] File violation detected: " << fileName << std::endl;
        return true;
    }
    return false;
}

void FileMonitor::workerLoop() {
#ifdef _WIN32
    workerLoopWindows();
#else
    workerLoopLinux();
#endif
}

#ifdef _WIN32
void FileMonitor::workerLoopWindows() {
    std::wstring wModsPath;
    wModsPath.assign(modsPath.begin(), modsPath.end());

    HANDLE hDir = CreateFileW(
        wModsPath.c_str(),
        FILE_LIST_DIRECTORY,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL,
        OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OVERLAPPED,
        NULL
    );

    if (hDir == INVALID_HANDLE_VALUE) {
        std::cerr << "[Anticheat Native] Failed to open mods dir for monitoring: " << modsPath << " err=" << GetLastError() << std::endl;
        return;
    }

    const DWORD bufferSize = 8192;
    BYTE buffer[8192];
    DWORD bytesReturned;
    OVERLAPPED overlapped = {0};
    overlapped.hEvent = CreateEvent(NULL, TRUE, FALSE, NULL);

    std::cout << "[Anticheat Native] File monitor started for " << modsPath << std::endl;

    while (running) {
        ResetEvent(overlapped.hEvent);
        BOOL success = ReadDirectoryChangesW(
            hDir,
            buffer,
            bufferSize,
            FALSE, // don't watch subtree
            FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME | FILE_NOTIFY_CHANGE_CREATION,
            &bytesReturned,
            &overlapped,
            NULL
        );

        if (!success) {
            std::cerr << "[Anticheat Native] ReadDirectoryChangesW failed" << std::endl;
            break;
        }

        DWORD wait = WaitForSingleObject(overlapped.hEvent, 1000); // 1 sec timeout to check running flag
        if (wait == WAIT_TIMEOUT) {
            // timeout, loop again to check running
            CancelIo(hDir);
            continue;
        }

        if (!GetOverlappedResult(hDir, &overlapped, &bytesReturned, FALSE)) {
            continue;
        }

        // Parse notifications
        BYTE* p = buffer;
        while (p < buffer + bytesReturned) {
            FILE_NOTIFY_INFORMATION* fni = (FILE_NOTIFY_INFORMATION*)p;
            std::wstring wFileName(fni->FileName, fni->FileNameLength / sizeof(WCHAR));
            std::string fileName(wFileName.begin(), wFileName.end());

            if (fni->Action == FILE_ACTION_ADDED || fni->Action == FILE_ACTION_RENAMED_NEW_NAME || fni->Action == FILE_ACTION_MODIFIED) {
                checkFileName(fileName);
            }

            if (fni->NextEntryOffset == 0) break;
            p += fni->NextEntryOffset;
        }
    }

    CloseHandle(overlapped.hEvent);
    CloseHandle(hDir);
    std::cout << "[Anticheat Native] File monitor stopped" << std::endl;
}
#else
void FileMonitor::workerLoopLinux() {
    int fd = inotify_init1(IN_NONBLOCK);
    if (fd < 0) {
        std::cerr << "[Anticheat Native] inotify_init failed: " << strerror(errno) << std::endl;
        return;
    }
    int wd = inotify_add_watch(fd, modsPath.c_str(), IN_CREATE | IN_MOVED_TO | IN_MODIFY);
    if (wd < 0) {
        std::cerr << "[Anticheat Native] inotify_add_watch failed for " << modsPath << ": " << strerror(errno) << std::endl;
        close(fd);
        return;
    }

    std::cout << "[Anticheat Native] File monitor (inotify) started for " << modsPath << std::endl;

    const size_t bufLen = 8192;
    char buf[8192];

    while (running) {
        ssize_t len = read(fd, buf, bufLen);
        if (len < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(500 * 1000); // 500ms
                continue;
            } else {
                break;
            }
        }
        size_t i = 0;
        while (i < (size_t)len) {
            struct inotify_event* ev = (struct inotify_event*)&buf[i];
            if (ev->len > 0) {
                std::string fileName(ev->name);
                checkFileName(fileName);
            }
            i += sizeof(struct inotify_event) + ev->len;
        }
    }

    inotify_rm_watch(fd, wd);
    close(fd);
    std::cout << "[Anticheat Native] File monitor stopped" << std::endl;
}
#endif

}
