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
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.CubeColumn;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

public class CubicChunksClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static CubeWorld clientCubeWorld;

    public static CubeWorld getClientCubeWorld() {
        return clientCubeWorld;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("CubicChunks client setup: Initializing cubic chunks client systems");
        // Initialize client CubeWorld
        clientCubeWorld = new CubeWorld(new CubeChunkGenerator());
        
        // Register client events
        NeoForge.EVENT_BUS.register(CubicChunksClient.class);
        
        LOGGER.info("CubicChunks client setup complete");
    }
    
    @SubscribeEvent
    public static void onPlayerJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player joining - resetting client cube world");
        // Reset cube world when joining a server
        clientCubeWorld = new CubeWorld(new CubeChunkGenerator());
    }
    
    @SubscribeEvent
    public static void onPlayerLeave(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player leaving - clearing client cube world");
        // Clear cube world when leaving
        if (clientCubeWorld != null) {
            // Clear all loaded cubes to free memory
            for (CubeChunk cube : clientCubeWorld.getLoadedCubes()) {
                // Cubes will be garbage collected
            }
        }
        clientCubeWorld = null;
    }
    
    /**
     * Called when receiving a cube sync packet from the server
     */
    public static void handleCubeSync(int cubeX, int cubeY, int cubeZ, net.minecraft.nbt.CompoundTag cubeData) {
        if (clientCubeWorld == null) {
            LOGGER.warn("Received cube sync but client cube world is null");
            return;
        }
        
        try {
            CubeChunk receivedCube = CubeChunk.fromNBT(cubeData);
            CubeColumn column = clientCubeWorld.getColumn(cubeX, cubeZ, true);
            
            if (column != null) {
                // Replace the cube in the column
                column.loadCube(cubeY, receivedCube);
                LOGGER.debug("Synced cube at ({}, {}, {})", cubeX, cubeY, cubeZ);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to sync cube at ({}, {}, {}): {}", cubeX, cubeY, cubeZ, e.getMessage());
        }
    }
    
    /**
     * Get the current player's cube coordinates
     */
    public static int[] getPlayerCubeCoords() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            var pos = mc.player.blockPosition();
            return new int[]{
                Math.floorDiv(pos.getX(), CubeChunk.SIZE),
                Math.floorDiv(pos.getY(), CubeChunk.SIZE),
                Math.floorDiv(pos.getZ(), CubeChunk.SIZE)
            };
        }
        return new int[]{0, 0, 0};
    }
} 