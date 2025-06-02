package com.radexin.cubicchunks.client;

import com.radexin.cubicchunks.CubicChunks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;

public class CubicChunksClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static CubeWorld clientCubeWorld;

    public static CubeWorld getClientCubeWorld() {
        return clientCubeWorld;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("CubicChunks client setup: Registering custom renderer and debug overlay (placeholder)");
        // Initialize client CubeWorld
        clientCubeWorld = new CubeWorld(new CubeChunkGenerator());
        // TODO: Register custom renderer for cubic chunks
        // TODO: Register debug overlay for cubic chunk info
    }
} 