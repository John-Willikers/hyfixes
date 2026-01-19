package com.hyfixes.ui;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.simple.StringCodec;

/**
 * Event data codec for the HyFixes Dashboard UI.
 * Handles button clicks, tab navigation, and config toggles.
 *
 * The codec serializes/deserializes data between the UI and server,
 * allowing the dashboard to respond to user interactions.
 */
public class DashboardEventData {

    private static final KeyedCodec<String> ACTION_CODEC = new KeyedCodec<>("Action", new StringCodec());
    private static final KeyedCodec<String> VALUE_CODEC = new KeyedCodec<>("Value", new StringCodec());

    /**
     * The action to perform (e.g., "selectTab", "refresh", "toggleConfig", "reloadConfig")
     */
    public String action = "";

    /**
     * The value associated with the action (e.g., tab name, config key)
     */
    public String value = "";

    /**
     * BuilderCodec for serializing/deserializing DashboardEventData.
     * Used by InteractiveCustomUIPage to handle UI events.
     */
    public static final BuilderCodec<DashboardEventData> CODEC = BuilderCodec
            .builder(DashboardEventData.class, DashboardEventData::new)
            .append(ACTION_CODEC, (d, v) -> d.action = v, d -> d.action).add()
            .append(VALUE_CODEC, (d, v) -> d.value = v, d -> d.value).add()
            .build();

    /**
     * Default constructor required for codec deserialization.
     */
    public DashboardEventData() {
    }

    /**
     * Convenience constructor for creating event data.
     *
     * @param action The action type
     * @param value The action value
     */
    public DashboardEventData(String action, String value) {
        this.action = action;
        this.value = value;
    }

    /**
     * Factory method for creating event data to be sent from the UI.
     * Used in UIEventBuilder bindings.
     *
     * @param action The action type
     * @param value The action value
     * @return A new DashboardEventData instance
     */
    public static DashboardEventData of(String action, String value) {
        return new DashboardEventData(action, value);
    }

    @Override
    public String toString() {
        return "DashboardEventData{action='" + action + "', value='" + value + "'}";
    }
}
