package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.network.ViolationReporter;

import java.lang.management.ManagementFactory;

/**
 * Evolution: Anti-debugging detection.
 * If a debugger is attached, it could be used to bypass anticheat logic.
 * Also detects if JVM is started with debug flags.
 *
 * Checks:
 * - Java: ManagementFactory.getRuntimeMXBean().getInputArguments() contains -agentlib:jdwp, -Xdebug
 * - Native: IsDebuggerPresent, CheckRemoteDebuggerPresent, NtQueryInformationProcess(ProcessDebugPort), timing checks via RDTSC.
 *
 * Performance: run every 60s, low overhead.
 *
 * If debugger detected, flag as TAMPER (not necessarily cheat, but suspicious for anticheat bypass attempt).
 */
public final class AntiDebugGuard {

    public boolean isJvmDebugging() {
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String low = arg.toLowerCase();
                if (low.contains("jdwp") || low.contains("xdebug") || low.contains("xrunjdwp")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void scan() {
        if (isJvmDebugging()) {
            ViolationReporter.reportTamper("DEBUGGER_JVM", "JVM started with debug args: " + String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        }

        if (NativeBridge.isLoaded()) {
            String[] vios = NativeBridge.safeGetMemoryViolations(); // anti-debug info may be reported via memory guard in native
            for (String v : vios) {
                if (v.toLowerCase().contains("debugger") || v.toLowerCase().contains("debug")) {
                    ViolationReporter.reportTamper("DEBUGGER_NATIVE", v);
                }
            }

            // Try extra native anti-debug via new JNI call if exists
            try {
                // Use reflection to avoid UnsatisfiedLinkError if method not yet implemented
                java.lang.reflect.Method m = NativeBridge.class.getMethod("isDebuggerPresent");
                boolean isDbg = (Boolean) m.invoke(null);
                if (isDbg) {
                    ViolationReporter.reportTamper("DEBUGGER_NATIVE_API", "IsDebuggerPresent returned true");
                }
            } catch (NoSuchMethodException ignored) {
                // Method not implemented in current native version, that's okay
            } catch (Throwable t) {
                // ignore
            }
        }
    }
}
