#include "input_hook.h"
#include <iostream>

#ifdef _WIN32
#include <windows.h>
#endif

namespace anticheat {

InputHook* InputHook::instance = nullptr;

InputHook::InputHook() {
    instance = this;
}

InputHook::~InputHook() {
    uninstall();
    if (instance == this) instance = nullptr;
}

InputHook& InputHook::getInstance() {
    static InputHook singleton;
    return singleton;
}

void InputHook::onMouseClick(bool injected) {
    lastMouseClick = currentTimeMs();
    lastInjected = injected;
}

void InputHook::onKeyPress(bool injected) {
    lastKeyPress = currentTimeMs();
    lastInjected = injected;
}

void InputHook::onMouseMove(bool injected) {
    lastMouseMove = currentTimeMs();
    if (injected) lastInjected = true;
}

#ifdef _WIN32

int64_t InputHook::currentTimeMs() {
    // GetTickCount64 returns ms since boot
    return (int64_t)GetTickCount64();
}

static LRESULT CALLBACK LowLevelMouseProc(int nCode, WPARAM wParam, LPARAM lParam) {
    if (nCode == HC_ACTION) {
        MSLLHOOKSTRUCT* pMouse = (MSLLHOOKSTRUCT*)lParam;
        bool injected = (pMouse->flags & LLMHF_INJECTED) != 0 || (pMouse->flags & LLMHF_LOWER_IL_INJECTED) != 0;
        if (wParam == WM_LBUTTONDOWN || wParam == WM_RBUTTONDOWN || wParam == WM_MBUTTONDOWN) {
            InputHook::getInstance().onMouseClick(injected);
            if (injected) {
                std::cout << "[Anticheat Native] Injected mouse click detected!" << std::endl;
            }
        } else if (wParam == WM_MOUSEMOVE) {
            InputHook::getInstance().onMouseMove(injected);
        }
    }
    return CallNextHookEx(NULL, nCode, wParam, lParam);
}

static LRESULT CALLBACK LowLevelKeyboardProc(int nCode, WPARAM wParam, LPARAM lParam) {
    if (nCode == HC_ACTION) {
        KBDLLHOOKSTRUCT* pKb = (KBDLLHOOKSTRUCT*)lParam;
        bool injected = (pKb->flags & LLKHF_INJECTED) != 0 || (pKb->flags & LLKHF_LOWER_IL_INJECTED) != 0;
        if (wParam == WM_KEYDOWN || wParam == WM_SYSKEYDOWN) {
            InputHook::getInstance().onKeyPress(injected);
            if (injected) {
                std::cout << "[Anticheat Native] Injected key press detected!" << std::endl;
            }
        }
    }
    return CallNextHookEx(NULL, nCode, wParam, lParam);
}

bool InputHook::install() {
    if (mouseHook && keyboardHook) return true;

    // Note: SetWindowsHookEx for low-level hooks requires thread ID 0 for global, but will be limited to current desktop
    // It also requires message loop? It will work but we need to pump messages in some thread.
    // For simplicity, we install hooks without dedicated message loop; in some environments it may still receive events.

    HINSTANCE hInst = GetModuleHandle(NULL);
    if (!hInst) hInst = GetModuleHandleA("anticheat-native.dll");
    if (!hInst) hInst = GetModuleHandle(NULL); // fallback

    mouseHook = SetWindowsHookExA(WH_MOUSE_LL, LowLevelMouseProc, hInst, 0);
    if (!mouseHook) {
        std::cerr << "[Anticheat Native] Failed to install mouse hook err=" << GetLastError() << std::endl;
    } else {
        std::cout << "[Anticheat Native] Mouse low-level hook installed" << std::endl;
    }

    keyboardHook = SetWindowsHookExA(WH_KEYBOARD_LL, LowLevelKeyboardProc, hInst, 0);
    if (!keyboardHook) {
        std::cerr << "[Anticheat Native] Failed to install keyboard hook err=" << GetLastError() << std::endl;
    } else {
        std::cout << "[Anticheat Native] Keyboard low-level hook installed" << std::endl;
    }

    return mouseHook != nullptr || keyboardHook != nullptr;
}

void InputHook::uninstall() {
    if (mouseHook) {
        UnhookWindowsHookEx((HHOOK)mouseHook);
        mouseHook = nullptr;
    }
    if (keyboardHook) {
        UnhookWindowsHookEx((HHOOK)keyboardHook);
        keyboardHook = nullptr;
    }
}

#else // non-Windows

int64_t InputHook::currentTimeMs() {
    // fallback using chrono
    auto now = std::chrono::system_clock::now();
    return std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
}

bool InputHook::install() {
    // On Linux, we cannot easily install global low-level hooks without X11/Wayland specifics.
    // For demonstration, we just log and return false, fallback to Java-only timing.
    std::cout << "[Anticheat Native] Input hooks not implemented on Linux, using Java fallback" << std::endl;
    return false;
}

void InputHook::uninstall() {
}

#endif

}
