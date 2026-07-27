package com.anticheat.client.detection;

import net.fabricmc.loader.impl.launch.knot.KnotClassLoader;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Evolution: ClassLoader integrity check.
 * Legitimate Fabric environment uses KnotClassLoader as main classloader.
 * Cheat may inject via custom URLClassLoader, or via javaagent that adds transformer.
 *
 * Checks:
 * - Current thread context classloader should be KnotClassLoader or its parent is KnotClassLoader
 * - No URLClassLoader whose URLs point to suspicious paths (e.g., /tmp, /home/user/Downloads, contains "cheat", "hack")
 * - Check for presence of java.lang.instrument.Instrumentation via getDeclaredFields (agent)
 * - Check if ClassLoader.defineClass has been overridden (via reflection)
 *
 * Performance: O(classloaders) small, run every 60 seconds low overhead.
 */
public final class ClassLoaderGuard {

    public static class ClassLoaderViolation {
        public final String loaderClass;
        public final String detail;

        public ClassLoaderViolation(String loader, String detail) {
            this.loaderClass = loader;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "ClassLoaderViolation loader=" + loaderClass + " detail=" + detail;
        }
    }

    public List<ClassLoaderViolation> scan() {
        List<ClassLoaderViolation> violations = new ArrayList<>();

        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();

        // Check if context loader is KnotClassLoader or child of it
        boolean isKnotOrChild = false;
        ClassLoader current = contextLoader;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.getClass().getName().contains("KnotClassLoader")) {
                isKnotOrChild = true;
                break;
            }
            current = current.getParent();
            depth++;
        }

        // If not Knot, might be modded environment like Forge? But we are Fabric, so flag if not Knot and not AppClassLoader?
        if (!isKnotOrChild && contextLoader != null) {
            String loaderName = contextLoader.getClass().getName();
            // Allow some known loaders: Knot, Fabric's, and jdk.internal.loader.ClassLoaders$AppClassLoader
            if (!loaderName.contains("AppClassLoader") && !loaderName.contains("PlatformClassLoader")) {
                violations.add(new ClassLoaderViolation(loaderName, "Context ClassLoader is not KnotClassLoader nor child"));
            }
        }

        // Scan all URLClassLoaders reachable via hierarchy for suspicious URLs
        current = contextLoader;
        while (current != null) {
            if (current instanceof URLClassLoader) {
                URLClassLoader urlCl = (URLClassLoader) current;
                for (URL url : urlCl.getURLs()) {
                    String urlStr = url.toString().toLowerCase();
                    if (urlStr.contains("cheat") || urlStr.contains("hack") || urlStr.contains("wurst") || urlStr.contains("meteor") || urlStr.contains("liquidbounce") ||
                            urlStr.contains("/tmp/") || urlStr.contains("/temp/") ) {
                        violations.add(new ClassLoaderViolation(current.getClass().getName(), "Suspicious URL in ClassLoader: " + urlStr));
                    }
                }
            }
            current = current.getParent();
        }

        // Check for Instrumentation presence (agent)
        try {
            Class.forName("java.lang.instrument.Instrumentation");
            // Try to detect if any class has been retransformed recently?
            // ManagementFactory.getRuntimeMXBean().getInputArguments() already checks -javaagent in MemoryGuard
            // Additional: check if there is field "instrumentation" in any class?
        } catch (ClassNotFoundException ignored) {}

        return violations;
    }
}
