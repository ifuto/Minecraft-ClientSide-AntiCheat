package com.anticheat.client.detection;

import com.anticheat.client.util.StackTraceUtil;

/**
 * Wrapper for stack trace guard to keep API consistent.
 */
public final class StackTraceGuard {
    public String check() {
        return StackTraceUtil.checkCurrentStack();
    }
}
