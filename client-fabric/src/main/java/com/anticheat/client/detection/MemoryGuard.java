package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.util.CriticalModuleHashes;
import com.anticheat.client.util.HashUtil;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Memory guard - detects suspicious modules and JVM tampering.
 *
 * Layer 3 requirement: "dllによるメモリ監視"
 *
 * Checks performed:
 * - Native module enumeration (via EnumProcessModules): compare module names hash against blacklist (cheat engine, x64dbg etc)
 * - JVM args check: suspicious -javaagent, -noverify, -Xverify:none, -javaagent:cheat
 * - Check for java.lang.instrument presence (agent attached)
 * - Scan for RWX memory regions via native (VirtualQuery)
 * - Check for unexpected native libraries loaded via ClassLoader?
 *
 * Limitations:
 * - Manual mapped DLLs not in PEB module list will evade EnumProcessModules. Mitigation: scan memory for PE headers.
 * - JVM TI agents can hide.
 * - Obfuscated module names can evade hash blacklist (e.g., rename cheatengine.dll to notepad.dll).
 *
 * Mathematical:
 * - Module hash collision probability same as before.
 * - RWX detection heuristic: legitimate JVM has some RWX regions for JIT. So we count number and size thresholds.
 */
public final class MemoryGuard {

    public static class MemoryViolation {
        public final String moduleOrReason;
        public final String detail;

        public MemoryViolation(String moduleOrReason, String detail) {
            this.moduleOrReason = moduleOrReason;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "MemoryViolation: " + moduleOrReason + " detail=" + detail;
        }
    }

    private final Set<Long> criticalModuleHashes;

    public MemoryGuard() {
        this.criticalModuleHashes = CriticalModuleHashes.getHashes();
    }

    public List<MemoryViolation> scan() {
        List<MemoryViolation> violations = new ArrayList<>();

        // 1. JVM args analysis
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : jvmArgs) {
            String lower = arg.toLowerCase();
            if (lower.contains("javaagent") || lower.contains("noverify") || lower.contains("xverify:none") || lower.contains("allowattachself")) {
                // Some legitimate mods may use javaagent? But rare.
                // Exclude some known legit agents? e.g., fabric uses no specific?
                violations.add(new MemoryViolation("Suspicious JVM arg", arg));
            }
        }

        // 2. Check for Instrumentation availability (possible runtime attachment)
        try {
            Class.forName("java.lang.instrument.Instrumentation");
            // If there is an agent that has transformed classes? Hard to detect, but we can check if any class's ProtectionDomain changed?
            // For now, just note presence of agent via management.
            String vmName = System.getProperty("java.vm.name", "");
            // TODO: more precise.
        } catch (ClassNotFoundException ignored) {}

        // 3. Native module scan via DLL
        if (NativeBridge.isLoaded()) {
            String[] nativeViolations = NativeBridge.safeGetMemoryViolations();
            for (String mod : nativeViolations) {
                violations.add(new MemoryViolation("Native module violation", mod));
            }
        } else {
            // Fallback Java check: try to list loaded libraries via reflection? Not trivial.
            // Could attempt to parse process modules via /proc/self/maps on Linux? Not implemented here.
        }

        // 4. Additional: check for suspicious modules via hash in Java (if we have process handle parsing)
        // For demonstration, we check system properties that might indicate debugger
        if (System.getProperty("cheatengine") != null) {
            violations.add(new MemoryViolation("CheatEngine property", "System property cheatengine present"));
        }

        return violations;
    }

    /**
     * Returns true if critical memory violation found.
     */
    public boolean hasCriticalViolation() {
        List<MemoryViolation> vios = scan();
        return !vios.isEmpty();
    }
}
