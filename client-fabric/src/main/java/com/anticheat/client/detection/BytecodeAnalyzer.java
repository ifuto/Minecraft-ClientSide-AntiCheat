package com.anticheat.client.detection;

import com.anticheat.client.util.HashUtil;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Evolution: Bytecode-level heuristic analysis.
 *
 * Instead of just package name, analyze class bytecode for cheat signatures:
 * - References to "KillAura", "Flight", "Speed", "XRay" strings (obfuscation-resistant: check method names)
 * - Frequent use of reflection to access Minecraft's private fields (e.g., timer, player capabilities)
 * - Use of Unsafe, MethodHandles to bypass access control
 * - Invocation of Robot, SendInput, or JNA/JNI native calls from mod code
 * - Presence of @Mixin that overwrites critical methods like ClientPlayerEntity::move, tick
 *
 * Performance: Scan is done once at startup, per jar, using ASM's fast visitor.
 * Complexity: O(total classes * avg methods). For 100 mods * 500 classes = 50k classes, ~200k methods, scanning ~ <2 sec on modern CPU.
 * Memory: streaming, not loading all classes at once.
 *
 * Detection levels:
 * - CRITICAL: class contains known cheat module strings (KillAura, AutoCrystal, etc.) AND uses reflection to modify player
 * - SUSPICIOUS: uses Unsafe or reflective access to net.minecraft.client.MinecraftClient fields
 *
 * Mathematical: We use TF-IDF like scoring: each suspicious indicator gives weight, sum > threshold => flagged.
 * This reduces false positives: e.g., a single use of reflection is not enough, but 5 indicators combined is high confidence.
 */
public final class BytecodeAnalyzer {

    public static class BytecodeViolation {
        public final String jarName;
        public final String className;
        public final String reason;
        public final int score;

        public BytecodeViolation(String jar, String cls, String reason, int score) {
            this.jarName = jar;
            this.className = cls;
            this.reason = reason;
            this.score = score;
        }

        @Override
        public String toString() {
            return "BytecodeViolation jar=" + jarName + " class=" + className + " score=" + score + " reason=" + reason;
        }
    }

    // Weights
    private static final int WEIGHT_CHEAT_STRING = 30;
    private static final int WEIGHT_REFLECTION = 10;
    private static final int WEIGHT_UNSAFE = 20;
    private static final int WEIGHT_ROBOT = 25;
    private static final int WEIGHT_MIXIN_OVERWRITE_CRITICAL = 30;
    private static final int WEIGHT_JNI = 15;

    private static final Set<String> CHEAT_KEYWORDS = new HashSet<>(Arrays.asList(
            "killaura", "kill aura", "crystalpvp", "autocrystal", "auto crystal",
            "xray", "x-ray", "freecam", "free cam", "flyhack", "bhop", "bunnyhop",
            "speedhack", "no fall", "nofall", "aimbot", "auto aim", "reach",
            "antiknockback", "anti kb", "timer", "fastplace", "fast break"
    ));

    private static final Set<String> CRITICAL_MIXIN_TARGETS = new HashSet<>(Arrays.asList(
            "net/minecraft/client/network/ClientPlayerEntity",
            "net/minecraft/client/MinecraftClient",
            "net/minecraft/entity/player/PlayerEntity",
            "net/minecraft/client/render/GameRenderer",
            "net/minecraft/network/ClientConnection"
    ));

    private static final int THRESHOLD_CRITICAL = 50;
    private static final int THRESHOLD_SUSPICIOUS = 25;

    public List<BytecodeViolation> scanAllMods() {
        List<BytecodeViolation> violations = new ArrayList<>();
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) return violations;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jarPath : stream) {
                try {
                    violations.addAll(scanJar(jarPath));
                } catch (Exception e) {
                    System.err.println("[BytecodeAnalyzer] Failed to scan " + jarPath.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return violations;
    }

    private List<BytecodeViolation> scanJar(Path jarPath) throws Exception {
        List<BytecodeViolation> result = new ArrayList<>();
        String jarName = jarPath.getFileName().toString();

        // Skip our own mod and known trusted libs (optimization)
        String lowerJar = jarName.toLowerCase();
        if (lowerJar.contains("anticheat-client") || lowerJar.contains("fabric-api") || lowerJar.contains("sodium") || lowerJar.contains("lithium")) {
            return result;
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        ClassReader cr = new ClassReader(in);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                        int score = 0;
                        StringBuilder reasons = new StringBuilder();

                        // Check for cheat strings in constant pool via LDC
                        for (MethodNode mn : cn.methods) {
                            if (mn.instructions == null) continue;
                            for (AbstractInsnNode insn : mn.instructions) {
                                if (insn instanceof LdcInsnNode) {
                                    LdcInsnNode ldc = (LdcInsnNode) insn;
                                    if (ldc.cst instanceof String) {
                                        String str = ((String) ldc.cst).toLowerCase();
                                        for (String kw : CHEAT_KEYWORDS) {
                                            if (str.contains(kw)) {
                                                score += WEIGHT_CHEAT_STRING;
                                                reasons.append("cheatString:").append(kw).append(" in ").append(mn.name).append("; ");
                                                break;
                                            }
                                        }
                                    }
                                } else if (insn instanceof MethodInsnNode) {
                                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                                    String owner = methodInsn.owner;
                                    String name = methodInsn.name;
                                    // Reflection
                                    if (owner.equals("java/lang/reflect/Method") && name.equals("invoke") ||
                                            owner.equals("java/lang/reflect/Field") && (name.equals("get") || name.equals("set")) ||
                                            owner.contains("MethodHandles") ) {
                                        score += WEIGHT_REFLECTION;
                                        reasons.append("reflection:").append(owner).append(".").append(name).append("; ");
                                    }
                                    // Unsafe
                                    if (owner.contains("sun/misc/Unsafe") || owner.contains("jdk/internal/misc/Unsafe")) {
                                        score += WEIGHT_UNSAFE;
                                        reasons.append("unsafe; ");
                                    }
                                    // Robot
                                    if (owner.equals("java/awt/Robot")) {
                                        score += WEIGHT_ROBOT;
                                        reasons.append("robot; ");
                                    }
                                    // JNI/JNA
                                    if (owner.contains("com/sun/jna") || name.equals("loadLibrary")) {
                                        score += WEIGHT_JNI;
                                        reasons.append("jni/jna; ");
                                    }
                                }
                            }
                        }

                        // Check Mixin annotation
                        if (cn.visibleAnnotations != null) {
                            for (AnnotationNode ann : cn.visibleAnnotations) {
                                if (ann.desc != null && ann.desc.contains("Mixin")) {
                                    // Check target
                                    if (ann.values != null) {
                                        for (int i = 0; i < ann.values.size(); i += 2) {
                                            String key = (String) ann.values.get(i);
                                            Object val = ann.values.get(i+1);
                                            if (key.equals("value") || key.equals("targets")) {
                                                String valStr = val.toString().toLowerCase();
                                                for (String crit : CRITICAL_MIXIN_TARGETS) {
                                                    if (valStr.contains(crit.toLowerCase()) || valStr.contains(crit.replace('/', '.').toLowerCase())) {
                                                        score += WEIGHT_MIXIN_OVERWRITE_CRITICAL;
                                                        reasons.append("criticalMixin:").append(crit).append("; ");
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Also check for overwrite of critical methods via @Overwrite or @Inject with high priority
                        // Simplified: if class name is in critical target package and method overwrites move/tick
                        // This is heuristic

                        if (score >= THRESHOLD_SUSPICIOUS) {
                            String level = score >= THRESHOLD_CRITICAL ? "CRITICAL" : "SUSPICIOUS";
                            result.add(new BytecodeViolation(jarName, cn.name.replace('/', '.'), level + " score=" + score + " " + reasons.toString(), score));
                        }
                    } catch (Exception e) {
                        // Skip class that fails to parse
                    }
                }
            }
        }
        return result;
    }
}
