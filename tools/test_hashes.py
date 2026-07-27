#!/usr/bin/env python3
"""
Quick test to verify hash generation matches Java's HashUtil.
Also tests false positive exclusion and collision probability.
"""
import hashlib, struct, math

def hash64(s):
    b = s.lower().encode('utf-8')
    d = hashlib.sha256(b).digest()
    v = struct.unpack('>Q', d[:8])[0]
    if v >= 2**63:
        v = v - 2**64
    return v

def collision_prob(n):
    # approx n^2 / 2^(65)
    return (n*n) / (2**65)

# Test known packages
tests = [
    "meteordevelopment.meteorclient",
    "net.wurstclient",
    "baritone",
    "net.ccbluex.liquidbounce",
    "me.jellysquid.mods.sodium",  # should be excluded
    "net.optifine",
]

for t in tests:
    h = hash64(t)
    unsigned = h & 0xFFFFFFFFFFFFFFFF
    print(f"{t} -> {h} (0x{unsigned:016x})")

print("\nCollision probability checks:")
for n in [10, 50, 71, 100, 1000]:
    p = collision_prob(n)
    print(f"n={n} P_collision≈{p:.2e}")

# Test file substring detection logic
critical_substrings = ["meteor-client", "wurst", "liquidbounce", "vape"]
# Simulate filename "Meteor-Client-1.20.jar"
filename = "Meteor-Client-1.20.jar"
lower = filename.lower()
print(f"\nTesting file '{filename}' contains blacklist?")
# Precompute hashes of critical substrings
blacklist_hashes = {hash64(s) for s in critical_substrings}
# Check substrings
found = False
for i in range(len(lower)):
    for j in range(i+3, min(len(lower), i+30)+1):
        sub = lower[i:j]
        if hash64(sub) in blacklist_hashes:
            print(f"  Found blacklisted substring '{sub}'")
            found = True
            break
    if found:
        break
if not found:
    print("  No match (unexpected)")
