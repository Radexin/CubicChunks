package com.radexin.cubicchunks.client;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public class CubicChunkDebugRenderer {
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // Advanced rendering not needed: vanilla debug (F3+G) already shows chunk section borders.
        // Placeholder for future advanced rendering if needed.
    }
} 