package com.hyfixes.early;

import com.hyfixes.early.config.EarlyConfigManager;

/**
 * Centralized logging for the HyFixes Early Plugin with verbose gating.
 * 
 * Usage:
 * - EarlyLogger.info()    - Always print (startup banners, completion messages, config status)
 * - EarlyLogger.verbose() - Only print when verbose=true (method discovery, injection details, byte sizes)
 * - EarlyLogger.error()   - Always print (errors, exceptions)
 * 
 * Verbose logging can be enabled via:
 * - Config: config.early.logging.verbose = true
 * - ENV: HYFIXES_VERBOSE=true
 * - JVM: -Dhyfixes.verbose=true
 */
public class EarlyLogger {
    
    private static final String PREFIX = "[HyFixes-Early] ";
    
    /**
     * Always print - used for:
     * - Startup banners
     * - "Transformation COMPLETE!" messages
     * - "DISABLED by config" messages
     * - Critical success/failure summaries
     */
    public static void info(String message) {
        System.out.println(PREFIX + message);
    }
    
    /**
     * Only print when verbose=true - used for:
     * - "Found method: X" messages
     * - "Applying X fix..." messages
     * - "Injecting X into Y" messages
     * - Byte size comparisons
     * - Method discovery details
     */
    public static void verbose(String message) {
        if (EarlyConfigManager.getInstance().isVerbose()) {
            System.out.println(PREFIX + message);
        }
    }
    
    /**
     * Always print errors to stderr.
     */
    public static void error(String message) {
        System.err.println(PREFIX + message);
    }
    
    /**
     * Always print errors with exception stack trace.
     */
    public static void error(String message, Throwable t) {
        System.err.println(PREFIX + message);
        t.printStackTrace();
    }
    
    /**
     * Print a separator line (always visible).
     */
    public static void separator() {
        System.out.println(PREFIX + "================================================");
    }
    
    /**
     * Print a separator line (verbose only).
     */
    public static void verboseSeparator() {
        if (EarlyConfigManager.getInstance().isVerbose()) {
            System.out.println(PREFIX + "------------------------------------------------");
        }
    }
}
