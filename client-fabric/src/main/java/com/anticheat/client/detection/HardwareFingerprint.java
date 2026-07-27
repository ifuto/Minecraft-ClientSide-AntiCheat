package com.anticheat.client.detection;

import com.anticheat.client.util.SystemInfo;

/**
 * Evolution: Hardware fingerprint for HWID ban.
 * Generates stable HWID from OS, CPU, MAC hash (privacy preserving).
 * Server can ban by HWID in addition to username, preventing alt accounts.
 *
 * Privacy: Only hashed MAC, not raw, and truncated hash.
 */
public final class HardwareFingerprint {

    private final String hwid;
    private final String osInfo;
    private final String hashedMac;

    public HardwareFingerprint() {
        this.hwid = SystemInfo.generateHWID();
        this.osInfo = SystemInfo.getOSInfo();
        this.hashedMac = SystemInfo.getHashedMacAddresses();
    }

    public String getHwid() {
        return hwid;
    }

    public String getOsInfo() {
        return osInfo;
    }

    public String getHashedMac() {
        return hashedMac;
    }

    public String toJson() {
        // For sending to server, include only non-PII hashed data
        return "{\"hwid\":\"" + hwid + "\",\"os\":\"" + osInfo.replace("\"", "") + "\",\"macHash\":\"" + hashedMac + "\"}";
    }
}
