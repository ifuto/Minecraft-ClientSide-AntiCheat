package com.anticheat.client.nativebridge;

/**
 * Evolution: Enhanced native bridge exposing advanced guards (syscall, anti-debug, HWID, integrity).
 * Falls back gracefully if native not loaded.
 */
public final class EnhancedNativeBridge {

    private EnhancedNativeBridge() {}

    public static boolean isAvailable() {
        return NativeBridge.isLoaded();
    }

    // HWID
    public static native String getHWID();
    public static native String getOSInfoNative();
    public static native String getHashedMacNative();

    // Integrity
    public static native String sha256File(String path);
    public static native boolean verifySelf(String expectedHash);

    // Syscall guard
    public static native String[] scanManualMappedDlls();
    public static native String[] scanHookedFunctions();

    // Anti-debug
    public static native boolean isDebuggerPresent();
    public static native String[] scanAntiDebug();

    // Safe wrappers
    public static String safeGetHWID() {
        if (!isAvailable()) return "native-not-loaded";
        try { return getHWID(); } catch (Throwable t) { return "error:" + t.getMessage(); }
    }

    public static String[] safeScanManualMapped() {
        if (!isAvailable()) return new String[0];
        try { return scanManualMappedDlls(); } catch (Throwable t) { return new String[0]; }
    }

    public static String[] safeScanHooks() {
        if (!isAvailable()) return new String[0];
        try { return scanHookedFunctions(); } catch (Throwable t) { return new String[0]; }
    }

    public static String[] safeScanAntiDebug() {
        if (!isAvailable()) return new String[0];
        try { return scanAntiDebug(); } catch (Throwable t) { return new String[0]; }
    }
}
