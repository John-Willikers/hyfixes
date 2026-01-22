package com.hyfixes.util;

import com.hyfixes.config.ConfigManager;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wrapper for runtime plugin logging with verbose gating.
 * 
 * Usage:
 * - logger.info()     - Always log (startup, critical messages)
 * - logger.verbose()  - Only log when verbose=true (debug info, periodic stats)
 * - logger.warning()  - Always log warnings
 * - logger.severe()   - Always log errors
 * 
 * Verbose logging can be enabled via:
 * - Config: config.logging.verbose = true
 * - ENV: HYFIXES_VERBOSE=true
 * - JVM: -Dhyfixes.verbose=true
 */
public class HyFixesLogger {
    
    private final Logger logger;
    
    /**
     * Create a wrapper around an existing logger.
     * 
     * @param logger The underlying Java logger to wrap
     */
    public HyFixesLogger(Logger logger) {
        this.logger = logger;
    }
    
    /**
     * Always log info messages - used for:
     * - Startup/shutdown messages
     * - Critical status updates
     * - User-facing command output
     */
    public void info(String msg) {
        logger.info(msg);
    }
    
    /**
     * Only log when verbose=true - used for:
     * - Method discovery details
     * - Periodic statistics
     * - Debug information
     * - Reflection discovery logs
     */
    public void verbose(String msg) {
        if (ConfigManager.getInstance().isVerbose()) {
            logger.info(msg);
        }
    }
    
    /**
     * Always log warnings.
     */
    public void warning(String msg) {
        logger.warning(msg);
    }
    
    /**
     * Always log severe/error messages.
     */
    public void severe(String msg) {
        logger.severe(msg);
    }
    
    /**
     * Log with specific level (respects verbose for INFO level).
     */
    public void log(Level level, String msg) {
        if (level == Level.INFO) {
            // INFO level respects verbose setting
            verbose(msg);
        } else {
            logger.log(level, msg);
        }
    }
    
    /**
     * Log with level and exception.
     */
    public void log(Level level, String msg, Throwable thrown) {
        logger.log(level, msg, thrown);
    }
    
    /**
     * Get the underlying logger for advanced use cases.
     */
    public Logger getLogger() {
        return logger;
    }
}
