package com.hyfixes.ui;

import com.hyfixes.HyFixes;
import com.hyfixes.config.ConfigManager;
import com.hyfixes.config.HyFixesConfig;
import com.hyfixes.util.ChatColorUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

/**
 * HyFixes Admin Dashboard - Visual UI for managing and monitoring HyFixes.
 *
 * Uses CommandListPage.ui as the base layout with BasicTextButton.ui for tabs
 * and SubcommandCard.ui for content display.
 */
public class HyFixesDashboardPage extends InteractiveCustomUIPage<DashboardEventData> {

    // Tab state
    private String currentTab = "overview";

    // Tab constants
    private static final String TAB_OVERVIEW = "overview";
    private static final String TAB_SANITIZERS = "sanitizers";
    private static final String TAB_CHUNKS = "chunks";
    private static final String TAB_CONFIG = "config";

    // Colors
    private static final String COLOR_GREEN = "#3d913f";
    private static final String COLOR_YELLOW = "#c9a227";
    private static final String COLOR_RED = "#962f2f";
    private static final String COLOR_WHITE = "#ffffff";
    private static final String COLOR_GRAY = "#aaaaaa";
    private static final String COLOR_GOLD = "#ffaa00";

    public HyFixesDashboardPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, DashboardEventData.CODEC);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        try {
            // Use CommandListPage.ui as base - the working layout
            cmd.append("Pages/CommandListPage.ui");

            // Set the title
            cmd.set("#CommandName.Text", "HyFixes Dashboard");
            cmd.set("#CommandDescription.Text", "Tab: " + currentTab.toUpperCase());

            // Hide elements we don't need
            cmd.set("#BackButton.Visible", false);
            cmd.set("#SendToChatButton.Visible", false);

            // Clear the command list for our tabs
            cmd.clear("#CommandList");

            // Build tab buttons using BasicTextButton.ui
            int btnIndex = 0;
            btnIndex = addTabButton(cmd, events, btnIndex, "Overview", TAB_OVERVIEW);
            btnIndex = addTabButton(cmd, events, btnIndex, "Sanitizers", TAB_SANITIZERS);
            btnIndex = addTabButton(cmd, events, btnIndex, "Chunks", TAB_CHUNKS);
            btnIndex = addTabButton(cmd, events, btnIndex, "Config", TAB_CONFIG);
            btnIndex = addTabButton(cmd, events, btnIndex, "[Refresh]", "refresh");

            // Clear the subcommand cards area for our content
            cmd.clear("#SubcommandCards");

            // Build content based on current tab
            int cardIndex = 0;
            switch (currentTab) {
                case TAB_OVERVIEW -> cardIndex = buildOverviewContent(cmd, events, cardIndex);
                case TAB_SANITIZERS -> cardIndex = buildSanitizersContent(cmd, events, cardIndex);
                case TAB_CHUNKS -> cardIndex = buildChunksContent(cmd, events, cardIndex);
                case TAB_CONFIG -> cardIndex = buildConfigContent(cmd, events, cardIndex);
                default -> cardIndex = buildOverviewContent(cmd, events, cardIndex);
            }

            // Show the subcommand section
            cmd.set("#SubcommandSection.Visible", true);

        } catch (Exception e) {
            HyFixes.getInstance().getLogger().at(Level.WARNING).withCause(e)
                    .log("[Dashboard] Error building dashboard page");
        }
    }

    private int addTabButton(UICommandBuilder cmd, UIEventBuilder events, int index, String label, String action) {
        cmd.append("#CommandList", "Pages/BasicTextButton.ui");

        // Format: show which tab is selected
        String displayLabel = label;
        boolean isTab = !action.equals("refresh");
        if (isTab && currentTab.equals(action)) {
            displayLabel = "> " + label + " <";
        }

        // Use .Text for setting text on BasicTextButton
        cmd.set("#CommandList[" + index + "].Text", displayLabel);

        // Bind click event - use just the index selector for the button
        String selector = "#CommandList[" + index + "]";
        if (action.equals("refresh")) {
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of("Action", "refresh"));
        } else {
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of("Action", "selectTab").put("Value", action));
        }

        return index + 1;
    }

    // PluginList-based methods for full-width layout
    private int addPluginItem(UICommandBuilder cmd, int index, String name, String version, String identifier, String description) {
        cmd.append("#PluginList", "Pages/PluginListButton.ui");
        String selector = "#PluginList[" + index + "]";
        // PluginListButton just has #Button.Text for the display text
        // Format: "NAME | VERSION | TAG | Description"
        String displayText = name;
        if (!version.isEmpty()) {
            displayText += " - " + version;
        }
        if (!identifier.isEmpty()) {
            displayText += " [" + identifier + "]";
        }
        cmd.set(selector + " #Button.Text", displayText);
        return index + 1;
    }

    private int buildOverviewPluginList(UICommandBuilder cmd, UIEventBuilder events, int index) {
        DashboardStats stats = DashboardStats.collect();

        index = addPluginItem(cmd, index, "Crashes Prevented", String.valueOf(stats.getTotalCrashesPrevented()), "STAT", "Total server crashes prevented by HyFixes");
        index = addPluginItem(cmd, index, "Active Sanitizers", stats.getActiveSanitizers() + "/" + stats.getTotalSanitizers(), "STAT", "Currently enabled crash prevention systems");
        index = addPluginItem(cmd, index, "System Health", stats.getHealthStatus(), "STAT", "Overall system health status");
        index = addPluginItem(cmd, index, "Memory Usage", stats.getMemoryUsage(), "STAT", "Current server memory consumption");
        index = addPluginItem(cmd, index, "Server Uptime", stats.getUptime(), "STAT", "Time since server started");
        index = addPluginItem(cmd, index, "Protected Chunks", String.valueOf(stats.getProtectedChunks()), "STAT", "Chunks protected from unloading");
        index = addPluginItem(cmd, index, "Unload Success", stats.getChunkUnloadRate(), "STAT", "Chunk unload success rate");

        return index;
    }

    private int buildSanitizersPluginList(UICommandBuilder cmd, UIEventBuilder events, int index) {
        DashboardStats stats = DashboardStats.collect();

        for (DashboardStats.SanitizerInfo sanitizer : stats.getSanitizerInfos()) {
            String status = sanitizer.isEnabled() ? "ON" : "OFF";
            index = addPluginItem(cmd, index, sanitizer.getName(), status, sanitizer.getFixCount() + " fixes", sanitizer.isEnabled() ? "Actively protecting server" : "Currently disabled");
        }

        return index;
    }

    private int buildChunksPluginList(UICommandBuilder cmd, UIEventBuilder events, int index) {
        DashboardStats stats = DashboardStats.collect();

        index = addPluginItem(cmd, index, "Protected Chunks", String.valueOf(stats.getProtectedChunks()), "COUNT", "Chunks protected from cleanup");
        String protStatus = stats.isChunkProtectionEnabled() ? "ENABLED" : "DISABLED";
        index = addPluginItem(cmd, index, "Chunk Protection", protStatus, "MODE", "Prevents important chunks from unloading");
        String mapStatus = stats.isMapAwareModeEnabled() ? "ENABLED" : "DISABLED";
        index = addPluginItem(cmd, index, "Map-Aware Mode", mapStatus, "MODE", "Pre-renders maps before chunk unload");
        index = addPluginItem(cmd, index, "Unload Stats", stats.getChunkUnloadAttempts() + " / " + stats.getChunkUnloadSuccesses(), "STATS", "Attempts / Successes");
        index = addPluginItem(cmd, index, "Success Rate", stats.getChunkUnloadRate(), "RATE", "Percentage of successful unloads");
        index = addPluginItem(cmd, index, "Last Cleanup", stats.getLastCleanupTime(), "TIME", "When last cleanup occurred");
        index = addPluginItem(cmd, index, "Cleanup Interval", stats.getCleanupInterval() + " ticks", "CFG", "Time between cleanup cycles");

        return index;
    }

    private int buildConfigPluginList(UICommandBuilder cmd, UIEventBuilder events, int index) {
        ConfigManager configManager = ConfigManager.getInstance();
        HyFixesConfig config = configManager.getConfig();

        // Toggleable options
        String verboseStatus = config.isVerbose() ? "ON" : "OFF";
        index = addPluginItem(cmd, index, "Verbose Logging", verboseStatus, "CLICK", "Toggle detailed logging");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "toggleConfig").put("Value", "verbose"));

        String unloadStatus = config.isChunkUnloadEnabled() ? "ON" : "OFF";
        index = addPluginItem(cmd, index, "Chunk Unload", unloadStatus, "CLICK", "Toggle chunk unload management");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "toggleConfig").put("Value", "chunkUnload"));

        String protectStatus = config.isChunkProtectionEnabled() ? "ON" : "OFF";
        index = addPluginItem(cmd, index, "Chunk Protection", protectStatus, "CLICK", "Toggle chunk protection");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "toggleConfig").put("Value", "chunkProtection"));

        String mapAwareStatus = config.isMapAwareModeEnabled() ? "ON" : "OFF";
        index = addPluginItem(cmd, index, "Map-Aware Mode", mapAwareStatus, "CLICK", "Toggle map pre-rendering");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "toggleConfig").put("Value", "mapAwareMode"));

        String logSanStatus = config.logSanitizerActions() ? "ON" : "OFF";
        index = addPluginItem(cmd, index, "Log Sanitizers", logSanStatus, "CLICK", "Toggle sanitizer action logging");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "toggleConfig").put("Value", "logSanitizerActions"));

        String logChunkStatus = config.logChunkProtectionEvents() ? "ON" : "OFF";
        index = addPluginItem(cmd, index, "Log Chunk Events", logChunkStatus, "CLICK", "Toggle chunk protection logging");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "toggleConfig").put("Value", "logChunkProtection"));

        // Reload button
        index = addPluginItem(cmd, index, "RELOAD CONFIG", "", "CLICK", "Reload config.json from disk");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PluginList[" + (index-1) + "]",
                EventData.of("Action", "reloadConfig"));

        // Info
        String loadedStatus = configManager.isLoadedFromFile() ? "YES" : "NO (defaults)";
        index = addPluginItem(cmd, index, "Config File", loadedStatus, "INFO", "plugins/hyfixes/config.json");

        return index;
    }

    private int addContentCard(UICommandBuilder cmd, UIEventBuilder events, int index,
                               String name, String description, String action) {
        cmd.append("#SubcommandCards", "Pages/SubcommandCard.ui");

        String cardSelector = "#SubcommandCards[" + index + "]";

        // SubcommandCard uses .Text for label elements
        cmd.set(cardSelector + " #SubcommandName.Text", name);
        cmd.set(cardSelector + " #SubcommandDescription.Text", description);

        // Bind click event if action specified
        if (action != null) {
            String selector = "#SubcommandCards[" + index + "]";
            if (action.equals("reloadConfig")) {
                events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                        EventData.of("Action", "reloadConfig"));
            } else if (action.startsWith("toggle_")) {
                String configKey = action.substring(7);
                events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                        EventData.of("Action", "toggleConfig").put("Value", configKey));
            }
        }

        return index + 1;
    }

    private int buildOverviewContent(UICommandBuilder cmd, UIEventBuilder events, int index) {
        DashboardStats stats = DashboardStats.collect();

        index = addContentCard(cmd, events, index,
                "Crashes Prevented: " + stats.getTotalCrashesPrevented(),
                "Total server crashes prevented by HyFixes sanitizers", null);

        index = addContentCard(cmd, events, index,
                "Sanitizers: " + stats.getActiveSanitizers() + "/" + stats.getTotalSanitizers(),
                "Currently enabled crash prevention systems", null);

        index = addContentCard(cmd, events, index,
                "Health: " + stats.getHealthStatus(),
                "Overall system health status", null);

        index = addContentCard(cmd, events, index,
                "Memory: " + stats.getMemoryUsage(),
                "Current server memory consumption", null);

        index = addContentCard(cmd, events, index,
                "Uptime: " + stats.getUptime(),
                "Time since server started", null);

        index = addContentCard(cmd, events, index,
                "Protected Chunks: " + stats.getProtectedChunks(),
                "Chunks protected from unloading", null);

        index = addContentCard(cmd, events, index,
                "Unload Rate: " + stats.getChunkUnloadRate(),
                "Chunk unload success rate", null);

        index = addContentCard(cmd, events, index,
                "Version: " + stats.getPluginVersion(),
                "Current HyFixes plugin version", null);

        return index;
    }

    private int buildSanitizersContent(UICommandBuilder cmd, UIEventBuilder events, int index) {
        DashboardStats stats = DashboardStats.collect();

        for (DashboardStats.SanitizerInfo sanitizer : stats.getSanitizerInfos()) {
            String status = sanitizer.isEnabled() ? "[ON]" : "[OFF]";
            index = addContentCard(cmd, events, index,
                    sanitizer.getName() + " " + status,
                    "Fixes applied: " + sanitizer.getFixCount(), null);
        }

        return index;
    }

    private int buildChunksContent(UICommandBuilder cmd, UIEventBuilder events, int index) {
        DashboardStats stats = DashboardStats.collect();

        index = addContentCard(cmd, events, index,
                "Protected: " + stats.getProtectedChunks(),
                "Number of chunks protected from cleanup", null);

        String protStatus = stats.isChunkProtectionEnabled() ? "ENABLED" : "DISABLED";
        index = addContentCard(cmd, events, index,
                "Chunk Protection: " + protStatus,
                "Prevents important chunks from being unloaded", null);

        String mapStatus = stats.isMapAwareModeEnabled() ? "ENABLED" : "DISABLED";
        index = addContentCard(cmd, events, index,
                "Map-Aware Mode: " + mapStatus,
                "Pre-renders maps before chunk unload", null);

        index = addContentCard(cmd, events, index,
                "Stats: " + stats.getChunkUnloadAttempts() + " attempts, " + stats.getChunkUnloadSuccesses() + " success",
                "Success rate: " + stats.getChunkUnloadRate(), null);

        index = addContentCard(cmd, events, index,
                "Last Cleanup: " + stats.getLastCleanupTime(),
                "Interval: " + stats.getCleanupInterval() + " ticks", null);

        return index;
    }

    private int buildConfigContent(UICommandBuilder cmd, UIEventBuilder events, int index) {
        ConfigManager configManager = ConfigManager.getInstance();
        HyFixesConfig config = configManager.getConfig();

        // Toggleable options
        String verboseStatus = config.isVerbose() ? "[ON]" : "[OFF]";
        index = addContentCard(cmd, events, index,
                "Verbose Logging " + verboseStatus,
                "Click to toggle detailed logging", "toggle_verbose");

        String unloadStatus = config.isChunkUnloadEnabled() ? "[ON]" : "[OFF]";
        index = addContentCard(cmd, events, index,
                "Chunk Unload " + unloadStatus,
                "Click to toggle chunk unload management", "toggle_chunkUnload");

        String protectStatus = config.isChunkProtectionEnabled() ? "[ON]" : "[OFF]";
        index = addContentCard(cmd, events, index,
                "Chunk Protection " + protectStatus,
                "Click to toggle chunk protection", "toggle_chunkProtection");

        String mapAwareStatus = config.isMapAwareModeEnabled() ? "[ON]" : "[OFF]";
        index = addContentCard(cmd, events, index,
                "Map-Aware Mode " + mapAwareStatus,
                "Click to toggle map pre-rendering", "toggle_mapAwareMode");

        String logSanStatus = config.logSanitizerActions() ? "[ON]" : "[OFF]";
        index = addContentCard(cmd, events, index,
                "Log Sanitizers " + logSanStatus,
                "Click to toggle sanitizer action logging", "toggle_logSanitizerActions");

        String logChunkStatus = config.logChunkProtectionEvents() ? "[ON]" : "[OFF]";
        index = addContentCard(cmd, events, index,
                "Log Chunks " + logChunkStatus,
                "Click to toggle chunk protection logging", "toggle_logChunkProtection");

        // Reload button
        index = addContentCard(cmd, events, index,
                "[RELOAD CONFIG]",
                "Click to reload config.json from disk", "reloadConfig");

        // Info
        String loadedStatus = configManager.isLoadedFromFile() ? "YES" : "NO (defaults)";
        index = addContentCard(cmd, events, index,
                "Config: plugins/hyfixes/config.json",
                "Loaded from file: " + loadedStatus, null);

        return index;
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, DashboardEventData data) {
        if (data == null || data.action == null) {
            return;
        }

        try {
            switch (data.action) {
                case "selectTab" -> {
                    currentTab = data.value != null ? data.value : TAB_OVERVIEW;
                    rebuild();
                }
                case "refresh" -> rebuild();
                case "toggleConfig" -> {
                    toggleConfigSetting(data.value);
                    rebuild();
                }
                case "reloadConfig" -> {
                    ConfigManager.getInstance().reload();
                    notifyPlayer(ref, store, "Configuration reloaded from disk!");
                    rebuild();
                }
            }
        } catch (Exception e) {
            HyFixes.getInstance().getLogger().at(Level.WARNING).withCause(e)
                    .log("[Dashboard] Error handling event: " + data);
        }
    }

    private void toggleConfigSetting(String configKey) {
        ConfigManager configManager = ConfigManager.getInstance();
        HyFixesConfig config = configManager.getConfig();

        switch (configKey) {
            case "verbose" -> config.setVerbose(!config.isVerbose());
            case "chunkUnload" -> config.setChunkUnloadEnabled(!config.isChunkUnloadEnabled());
            case "chunkProtection" -> config.setChunkProtectionEnabled(!config.isChunkProtectionEnabled());
            case "mapAwareMode" -> config.setMapAwareModeEnabled(!config.isMapAwareModeEnabled());
            case "logSanitizerActions" -> config.setLogSanitizerActions(!config.logSanitizerActions());
            case "logChunkProtection" -> config.setLogChunkProtectionEvents(!config.logChunkProtectionEvents());
        }

        configManager.saveConfig();
    }

    private void notifyPlayer(Ref<EntityStore> ref, Store<EntityStore> store, String message) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            ChatColorUtil.sendMessage(player, "&a[HyFixes] " + message);
        }
    }
}
