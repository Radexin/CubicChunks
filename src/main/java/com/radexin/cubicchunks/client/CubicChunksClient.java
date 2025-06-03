package com.radexin.cubicchunks.client;

import com.radexin.cubicchunks.CubicChunks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.gen.CubicWorldGenerator;
import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.CubeColumn;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.radexin.cubicchunks.client.CubicRenderer;

import java.util.Collection;

public class CubicChunksClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static CubeWorld clientCubeWorld;
    private static CubicRenderer cubeRenderer;

    public static CubeWorld getClientCubeWorld() {
        return clientCubeWorld;
    }

    public static CubicRenderer getCubeRenderer() {
        return cubeRenderer;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("CubicChunks client setup: Initializing cubic chunks client systems");
        
        // Initialize client CubeWorld with CubicWorldGenerator
        CubicWorldGenerator generator = new CubicWorldGenerator(null, null, null);
        clientCubeWorld = new CubeWorld(generator, null, null);
        
        // Initialize cube renderer
        cubeRenderer = new CubicRenderer();
        
        // Register client events
        NeoForge.EVENT_BUS.register(CubicChunksClient.class);
        
        LOGGER.info("CubicChunks client setup complete");
    }
    
    @SubscribeEvent
    public static void onPlayerJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player joining - resetting client cube world");
        // Reset cube world when joining a server
        CubicWorldGenerator generator = new CubicWorldGenerator(null, null, null);
        clientCubeWorld = new CubeWorld(generator, null, null);
        if (cubeRenderer != null) {
            cubeRenderer.clearCache();
        }
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
        
        if (cubeRenderer != null) {
            cubeRenderer.clearCache();
        }
    }
    
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        
        if (clientCubeWorld == null || cubeRenderer == null) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        
        // Get camera position
        Vec3 cameraPos = event.getCamera().getPosition();
        
        // Get player cube coordinates
        int playerCubeX = (int) Math.floor(cameraPos.x / CubeChunk.SIZE);
        int playerCubeY = (int) Math.floor(cameraPos.y / CubeChunk.SIZE);
        int playerCubeZ = (int) Math.floor(cameraPos.z / CubeChunk.SIZE);
        
        // Load cubes around player
        int horizontalRadius = mc.options.renderDistance().get();
        int verticalRadius = com.radexin.cubicchunks.Config.verticalRenderDistance;
        
        clientCubeWorld.loadCubesAround(playerCubeX, playerCubeY, playerCubeZ, horizontalRadius, verticalRadius);
        
        // Unload distant cubes
        clientCubeWorld.unloadCubesOutside(playerCubeX, playerCubeY, playerCubeZ, horizontalRadius + 2, verticalRadius + 2);
        
        // Get visible cubes
        Collection<CubeChunk> visibleCubes = getVisibleCubes(cameraPos, horizontalRadius, verticalRadius);
        
        // Render cubes
        PoseStack poseStack = event.getPoseStack();
        cubeRenderer.renderCubes(poseStack, cameraPos, visibleCubes);
        
        // Process render rebuilds
        cubeRenderer.processRebuilds();
    }
    
    private static Collection<CubeChunk> getVisibleCubes(Vec3 cameraPos, int horizontalRadius, int verticalRadius) {
        if (clientCubeWorld == null) {
            return java.util.Collections.emptyList();
        }
        
        java.util.List<CubeChunk> visibleCubes = new java.util.ArrayList<>();
        
        int centerCubeX = (int) Math.floor(cameraPos.x / CubeChunk.SIZE);
        int centerCubeY = (int) Math.floor(cameraPos.y / CubeChunk.SIZE);
        int centerCubeZ = (int) Math.floor(cameraPos.z / CubeChunk.SIZE);
        
        // Simple frustum culling - check cubes in render distance
        for (int x = centerCubeX - horizontalRadius; x <= centerCubeX + horizontalRadius; x++) {
            for (int z = centerCubeZ - horizontalRadius; z <= centerCubeZ + horizontalRadius; z++) {
                for (int y = centerCubeY - verticalRadius; y <= centerCubeY + verticalRadius; y++) {
                    // Check if cube is within circular horizontal distance
                    double distSq = Math.pow(x - centerCubeX, 2) + Math.pow(z - centerCubeZ, 2);
                    if (distSq <= horizontalRadius * horizontalRadius) {
                        CubeChunk cube = clientCubeWorld.getCube(x, y, z, false);
                        if (cube != null && !cube.isEmpty()) {
                            visibleCubes.add(cube);
                        }
                    }
                }
            }
        }
        
        return visibleCubes;
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
            // Get biome registry from client
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry = null;
            if (mc.level != null) {
                biomeRegistry = mc.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            }
            
            CubeChunk receivedCube = CubeChunk.fromNBT(cubeData, biomeRegistry);
            CubeColumn column = clientCubeWorld.getColumn(cubeX, cubeZ, true);
            
            if (column != null) {
                // Replace the cube in the column
                column.loadCube(cubeY, receivedCube);
                
                // Mark for render rebuild
                if (cubeRenderer != null) {
                    cubeRenderer.markForRebuild(receivedCube);
                }
                
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
    
    /**
     * Tick client cube world
     */
    public static void tick() {
        if (clientCubeWorld != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                clientCubeWorld.tick(mc.level);
            }
        }
    }
} 