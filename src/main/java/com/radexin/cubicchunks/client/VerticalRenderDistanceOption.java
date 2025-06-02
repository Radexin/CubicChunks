package com.radexin.cubicchunks.client;

import com.radexin.cubicchunks.Config;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

public class VerticalRenderDistanceOption {
    public static OptionInstance<Integer> create() {
        return new OptionInstance<>(
            "cubicchunks.options.verticalRenderDistance",
            OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable("cubicchunks.options.verticalRenderDistance").append(": ").append(Integer.toString(value)),
            new OptionInstance.IntRange(1, 32),
            Config.verticalRenderDistance,
            (value) -> {
                Config.verticalRenderDistance = value;
                // Force config save - this ensures the value is persisted
                // Note: NeoForge will automatically save the config when the game closes,
                // but for immediate persistence, we could trigger a save here if needed
            }
        );
    }
} 