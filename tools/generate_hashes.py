#!/usr/bin/env python3
"""
Generate hashed blacklists for Anticheat.
We avoid storing plaintext package strings in code by storing SHA-256 truncated to 64-bit.
This script generates Java files containing hash sets.

Hash function: SHA-256 of UTF-8 lowercased string, first 8 bytes as big-endian signed long, represented as hex.
"""
import hashlib
import struct
import os

def hash64(s: str) -> int:
    b = s.lower().encode('utf-8')
    digest = hashlib.sha256(b).digest()
    val = struct.unpack('>Q', digest[:8])[0]
    if val >= 2**63:
        val_signed = val - 2**64
    else:
        val_signed = val
    return val_signed

def hash64_unsigned(s: str) -> int:
    b = s.lower().encode('utf-8')
    digest = hashlib.sha256(b).digest()
    return struct.unpack('>Q', digest[:8])[0]

critical_packages = [
    "meteordevelopment.meteorclient",
    "meteordevelopment.meteorclient.mixin",
    "meteordevelopment.meteorclient.systems.modules",
    "net.wurstclient",
    "net.wurstclient.mixin",
    "net.wurstclient.forge",
    "net.wurstclient.altmanager",
    "baritone",
    "baritone.api",
    "baritone.api.process",
    "baritone.mixin",
    "com.viaversion.aristois",
    "net.aristois",
    "me.deftware.aristois",
    "dev.tigr.ares",
    "dev.tigr.ares.forge",
    "me.kaimmy",
    "net.minecraft.client.gui.hud.impact",
    "com.impactclient",
    "clientapi",
    "com.mentalfrostburn.rusherhack",
    "org.rusherhack.client",
    "org.rusherhack.client.api.feature.module",
    "com.matt.forgehax",
    "me.rigamortis.seppuku",
    "me.rigamortis.seppuku.impl.module",
    "com.krazzzzymonkey.cataclysm",
    "dev.boze.havoc",
    "com.wildermods.wilderforge",
    "me.zeroeightsix.elytra",
    "dev.realms.future",
    "me.futureclient",
    "me.future.client",
    "net.novaclient",
    "net.novac",
    "wtf.expensive",
    "wtf.expensive.client",
    "expensiverp",
    "ru.krosik",
    "ru.dannzed",
    "net.zeus",
    "ru.nuxclient",
    "ru.nursultan",
    "ru.celestial",
    "ru.nurik",
    "pw.prok.virtualhax",
    "dev.rise.client",
    "com.riseclient",
    "net.moonclient",
    "moonclient",
    "me.liquidbounce",
    "net.ccbluex.liquidbounce",
    "net.ccbluex.liquidbounce.injection",
    "net.ccbluex.liquidbounce.features.module",
    "net.ccbluex.liquidbounce.event",
    "net.ccbluex.liquidbounce.utils",
    "net.ccbluex.liquidbounce.ui.client.hud",
    "net.ccbluex.liquidbounce.injection.mixins",
    "me.zero.alpine",
    "me.zero.alpine.listener",
    "com.lukflug.panelstudio",
    "net.freecam",
    "dev.hashalite.freecam",
    "net.zergatul.freecam",
    "net.zergatul.cheatutils",
    "dev.xracha.freecam",
    "mangoplex.spectatorfreecam",
    "com.lambda.client",
    "com.konasclient",
    "com.inertia.client",
    "net.sigma",
    "me.jellysquid.mods",
]

false_positive_packages = [
    "me.jellysquid.mods.sodium",
    "net.caffeinemc.mods.sodium",
    "net.optifine",
    "net.anoptik",
    "com.moonsworth.lunar",
    "net.badlion",
    "xaero.map",
    "com.mamiyaotaru.voxelmap",
    "fi.dy.masa.tweakeroo",
    "fi.dy.masa.malilib",
    "fi.dy.masa.litematica",
    "fi.dy.masa.minihud",
    "de.jcm.discordgamesdk",
    "net.earthcomputer.clientcommands",
    "dev.isxander.zoomify",
    "mixinextras",
    "com.google.common.eventbus",
    "net.minecraftforge.eventbus",
    "net.bytebuddy",
    "javassist",
    "com.github.retrooper.packetevents",
    "io.github.retrooper.packetevents",
    "com.viaversion.viaversion",
    "de.florianmichael.viafabricplus",
    "com.aayushatharva.brotli",
    "me.zeroeightsix.antichatreport",
    "dev.isxander.nochatreports",
    "com.aizistral.nochatreports",
    "org.lwjgl.nanovg",
    "me.shedaniel.rei",
    "me.shedaniel.clothconfig",
    "net.fabricmc.fabric",
    "me.jellysquid.mods",
]

critical_packages = [p for p in critical_packages if p not in false_positive_packages]

suspicious_packages = [
    "fi.dy.masa.tweakeroo",
    "fi.dy.masa.litematica",
    "xaero.map",
    "xaero.common.minimap",
    "journeymap",
    "com.mamiyaotaru.voxelmap",
]

critical_file_substrings = [
    "meteor-client",
    "meteorclient",
    "wurst",
    "baritone",
    "aristois",
    "futureclient",
    "future-client",
    "rusherhack",
    "impactclient",
    "impact-client",
    "seppuku",
    "forgehax",
    "cataclysm",
    "havoc-client",
    "wilderforge",
    "elytra-client",
    "novaclient",
    "nova-client",
    "expensive",
    "nuxhack",
    "nursultan",
    "celestial",
    "nurik",
    "virtualhax",
    "rise-client",
    "riseclient",
    "liquidbounce",
    "inertia",
    "sigma-client",
    "sigma5",
    "vape",
    "novoline",
    "tenacity",
    "tenacity-client",
    "astolfo",
    "flux-client",
    "raven-b",
    "ravenclient",
    "thunderhack",
    "thunder-hack",
    "akrien",
    "matix",
    "huzuni",
    "kami-blue",
    "lambda-client",
    "konas",
    "salhack",
    "phobos",
    "jigsaw-client",
    "wolfram",
    "ares-client",
    "fdpclient",
    "fdp-client",
    "nova-line",
    "killaura",
    "kill-aura",
    "autocrystal",
    "auto-crystal",
    "crystalpvp",
    "xray-mod",
    "x-ray-fabric",
    "freecam-mod",
    "entityradar",
    "xraybypass",
    "autoclicker-mod",
    "reach-mod",
    "flyhack",
    "bhop",
    "speedhack",
    "nursultan-client",
    "celestial-client",
    "expensive-client",
    "akrien-client",
]

suspicious_file_substrings = [
    "tweakeroo",
    "litematica",
    "xaerominimap",
    "xaeroworldmap",
    "voxelmap",
    "free-cam",
    "freecam",
    "cheatutils",
    "zergatul",
]

critical_modules = [
    "cheatengine",
    "cheatengine-x86_64",
    "speedhack",
    "artmoney",
    "processhacker",
    "x64dbg",
    "ollydbg",
    "scylla",
    "scylla_hide",
    "titanengine",
    "gameguard",
    "memoryscanner",
]

def generate_java_file(filepath, class_name, package_name, hash_dict, comment):
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(f"package {package_name};\n\n")
        f.write("import java.util.HashSet;\nimport java.util.Set;\n\n")
        f.write("/**\n")
        f.write(f" * {comment}\n")
        f.write(" * Auto-generated by generate_hashes.py\n")
        f.write(" * Contains SHA-256 truncated 64-bit hashes, no plaintext strings to avoid easy string search.\n")
        f.write(" * Collision probability: n / 2^64 ~ negligible.\n")
        f.write(" * False positives excluded: Sodium, OptiFine, Lunar, Badlion, etc.\n")
        f.write(" */\n")
        f.write(f"public final class {class_name} {{\n")
        f.write("    private static final Set<Long> HASHES = new HashSet<>();\n")
        f.write("    static {\n")
        for original, h_signed in hash_dict.items():
            unsigned = h_signed & 0xFFFFFFFFFFFFFFFF
            f.write(f"        HASHES.add({h_signed}L); // 0x{unsigned:016x} : '{original}'\n")
        f.write("    }\n\n")
        f.write("    private " + class_name + "() {}\n")
        f.write("    public static Set<Long> getHashes() { return HASHES; }\n")
        f.write("    public static boolean containsHash(long h) { return HASHES.contains(h); }\n")
        f.write("    public static int size() { return HASHES.size(); }\n")
        f.write("}\n")

base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
client_util_path = os.path.join(base, "client-fabric/src/main/java/com/anticheat/client/util")

critical_pkg_hashes = {p: hash64(p) for p in critical_packages}
suspicious_pkg_hashes = {p: hash64(p) for p in suspicious_packages}
critical_file_hashes = {s: hash64(s) for s in critical_file_substrings}
suspicious_file_hashes = {s: hash64(s) for s in suspicious_file_substrings}
module_hashes = {s: hash64(s) for s in critical_modules}

generate_java_file(os.path.join(client_util_path, "CriticalPackageHashes.java"),
                   "CriticalPackageHashes",
                   "com.anticheat.client.util",
                   critical_pkg_hashes,
                   "Critical (ban) package hashes - curated excluding false positives like Sodium, OptiFine, Lunar, Badlion, etc.")

generate_java_file(os.path.join(client_util_path, "SuspiciousPackageHashes.java"),
                   "SuspiciousPackageHashes",
                   "com.anticheat.client.util",
                   suspicious_pkg_hashes,
                   "Suspicious package hashes (warning level)")

generate_java_file(os.path.join(client_util_path, "CriticalFileHashes.java"),
                   "CriticalFileHashes",
                   "com.anticheat.client.util",
                   critical_file_hashes,
                   "Critical file substring hashes (mods folder monitoring)")

generate_java_file(os.path.join(client_util_path, "SuspiciousFileHashes.java"),
                   "SuspiciousFileHashes",
                   "com.anticheat.client.util",
                   suspicious_file_hashes,
                   "Suspicious file substring hashes")

generate_java_file(os.path.join(client_util_path, "CriticalModuleHashes.java"),
                   "CriticalModuleHashes",
                   "com.anticheat.client.util",
                   module_hashes,
                   "Critical native module hashes (memory guard)")

native_header_path = os.path.join(base, "native/src/hashes.h")
with open(native_header_path, 'w', encoding='utf-8') as f:
    f.write("#pragma once\n")
    f.write("#include <cstdint>\n#include <unordered_set>\n")
    f.write("// Auto-generated by generate_hashes.py\n")
    f.write("namespace anticheat {\n")
    f.write("inline std::unordered_set<uint64_t> critical_file_hashes = {\n")
    for s in critical_file_substrings:
        unsigned = hash64_unsigned(s)
        f.write(f"    0x{unsigned:016x}ULL, // {s}\n")
    f.write("};\n")
    f.write("inline std::unordered_set<uint64_t> critical_package_hashes = {\n")
    for s in critical_packages:
        unsigned = hash64_unsigned(s)
        f.write(f"    0x{unsigned:016x}ULL, // {s}\n")
    f.write("};\n")
    f.write("inline std::unordered_set<uint64_t> critical_module_hashes = {\n")
    for s in critical_modules:
        unsigned = hash64_unsigned(s)
        f.write(f"    0x{unsigned:016x}ULL, // {s}\n")
    f.write("};\n")
    f.write("}\n")

print(f"Generated {len(critical_pkg_hashes)} critical package hashes")
print(f"Generated {len(critical_file_hashes)} critical file hashes")
print(f"Generated {len(module_hashes)} module hashes")
