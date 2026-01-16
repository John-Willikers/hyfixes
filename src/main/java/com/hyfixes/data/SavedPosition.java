package com.hyfixes.data;

import com.hypixel.hytale.math.vector.Transform;

import java.util.UUID;

/**
 * Stores a player's saved position before entering an instance.
 * Used to restore their position if instance exit fails.
 */
public class SavedPosition {

    private final UUID worldUuid;
    private final String worldName;
    private final Transform transform;
    private final long savedAt;

    public SavedPosition(UUID worldUuid, String worldName, Transform transform) {
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.transform = transform;
        this.savedAt = System.currentTimeMillis();
    }

    public UUID getWorldUuid() {
        return worldUuid;
    }

    public String getWorldName() {
        return worldName;
    }

    public Transform getTransform() {
        return transform;
    }

    public long getSavedAt() {
        return savedAt;
    }

    /**
     * Check if this saved position is still valid (less than 24 hours old).
     * Old positions are likely stale and shouldn't be used.
     */
    public boolean isValid() {
        long age = System.currentTimeMillis() - savedAt;
        return age < 24 * 60 * 60 * 1000; // 24 hours
    }

    @Override
    public String toString() {
        return "SavedPosition{" +
            "worldName='" + worldName + '\'' +
            ", transform=" + transform +
            ", savedAt=" + savedAt +
            '}';
    }
}
