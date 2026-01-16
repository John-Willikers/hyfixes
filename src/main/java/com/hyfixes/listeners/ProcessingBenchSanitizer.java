package com.hyfixes.listeners;

import com.hyfixes.HyFixes;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState;
import com.hypixel.hytale.builtin.crafting.window.ProcessingBenchWindow;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * FIX: ProcessingBench Window NPE Crash
 *
 * PROBLEM: When a player breaks a processing bench (campfire, crafting table, etc.)
 * while another player has it open, Hytale's ProcessingBenchState.onDestroy() calls
 * WindowManager.closeAndRemoveAll() which triggers window close handlers.
 *
 * These handlers try to access block data that is already being destroyed, causing:
 *   1. BlockType.getDefaultStateKey() NPE - "this.data" is null
 *   2. BenchWindow.onClose0() NPE - "ref" is null
 *
 * This kicks the player who had the window open!
 *
 * SOLUTION: Hook into ProcessingBenchState removal and safely clear/close the windows
 * BEFORE the crash-causing onDestroy() code runs. By clearing the windows map,
 * the problematic closeAndRemoveAll() call becomes a no-op.
 */
public class ProcessingBenchSanitizer extends RefSystem<ChunkStore> {

    private final HyFixes plugin;
    private boolean loggedOnce = false;
    private int fixedCount = 0;

    public ProcessingBenchSanitizer(HyFixes plugin) {
        this.plugin = plugin;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return BlockStateModule.get().getComponentType(ProcessingBenchState.class);
    }

    @Override
    public void onEntityAdded(
            Ref<ChunkStore> ref,
            AddReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        // No action needed when processing benches are added
    }

    @Override
    public void onEntityRemove(
            Ref<ChunkStore> ref,
            RemoveReason reason,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if (!loggedOnce) {
            plugin.getLogger().at(Level.INFO).log(
                "[ProcessingBenchSanitizer] Active - monitoring processing bench removals"
            );
            loggedOnce = true;
        }

        // Only process actual removals, not chunk unloads
        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        try {
            // Get the ProcessingBenchState component
            ProcessingBenchState benchState = store.getComponent(ref,
                BlockStateModule.get().getComponentType(ProcessingBenchState.class));
            if (benchState == null) {
                return;
            }

            // Get the windows map before the destroy cascade
            Map<UUID, ProcessingBenchWindow> windows = benchState.getWindows();
            if (windows == null || windows.isEmpty()) {
                return;
            }

            // Log that we're handling this case
            int windowCount = windows.size();

            // Clear the windows map to prevent the crash cascade in onDestroy()
            // When closeAndRemoveAll() runs, it will find an empty map and do nothing
            // The windows themselves will be cleaned up via GC
            windows.clear();

            fixedCount++;
            plugin.getLogger().at(Level.WARNING).log(
                "[ProcessingBenchSanitizer] Prevented crash #" + fixedCount +
                " - cleared " + windowCount + " open window(s) before bench destruction"
            );

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log(
                "[ProcessingBenchSanitizer] Error during sanitization: " + e.getMessage()
            );
        }
    }

    public int getFixedCount() {
        return fixedCount;
    }
}
