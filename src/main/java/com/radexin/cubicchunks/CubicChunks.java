package com.radexin.cubicchunks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.gen.CubicWorldGenerator;
import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import com.radexin.cubicchunks.world.CubicChunksSavedData;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.radexin.cubicchunks.network.CubeSyncPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import com.radexin.cubicchunks.client.CubicChunksClient;
import net.neoforged.fml.loading.FMLEnvironment;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CubicChunks.MODID)
public class CubicChunks
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "cubicchunks";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    private CubicChunksSavedData savedData;
    private CubicWorldGenerator cubeChunkGenerator;

    // Remove SimpleChannel and old networking code. Networking is now handled via RegisterPayloadHandlerEvent and CustomPacketPayload.
    // TODO: Register network payloads for cube sync in a static event handler.

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CubicChunks(IEventBus modEventBus, ModContainer modContainer)
    {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (CubicChunks) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register client setup if on client
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(CubicChunksClient::onClientSetup);
        }

        // Register payloads on the mod event bus
        modEventBus.addListener(this::registerPayloads);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("HELLO from server starting");
        // Initialize generator
        var biomeRegistry = event.getServer().overworld().registryAccess().registry(net.minecraft.core.registries.Registries.BIOME).get();
        this.cubeChunkGenerator = new CubicWorldGenerator(event.getServer().overworld(), biomeRegistry, null);
        // Load or create CubicChunksSavedData
        this.savedData = com.radexin.cubicchunks.world.CubicChunksSavedData.getOrCreate(event.getServer(), this.cubeChunkGenerator);
    }

    // Helper to send a cube sync payload to a player
    public static void sendCubeSync(ServerPlayer player, CubeChunk cube) {
        var payload = new CubeSyncPayload(
            cube.getCubeX(), cube.getCubeY(), cube.getCubeZ(), cube.toNBT()
        );
        PacketDistributor.sendToPlayer(player, payload);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // Register a simple command to test cube generation
        event.getDispatcher().register(
            Commands.literal("cubiccube")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                int x = IntegerArgumentType.getInteger(ctx, "x");
                                int y = IntegerArgumentType.getInteger(ctx, "y");
                                int z = IntegerArgumentType.getInteger(ctx, "z");
                                CubeChunk cube = savedData.getCubeWorld().getCube(x, y, z, true);
                                ServerPlayer player = ctx.getSource().getPlayer();
                                if (cube != null && player != null) {
                                    player.sendSystemMessage(Component.literal("Generated cube at (" + x + ", " + y + ", " + z + ")"));
                                } else if (player != null) {
                                    player.sendSystemMessage(Component.literal("Failed to generate cube at (" + x + ", " + y + ", " + z + ")"));
                                }
                                // Mark data as dirty for saving
                                savedData.setDirty();
                                return 1;
                            })
                        )
                    )
                )
        );
        
        // Command to set blocks in cubic chunks
        event.getDispatcher().register(
            Commands.literal("cubicsetblock")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("y", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .then(Commands.argument("block", BlockStateArgument.block(event.getBuildContext()))
                                .executes(ctx -> {
                                    int x = IntegerArgumentType.getInteger(ctx, "x");
                                    int y = IntegerArgumentType.getInteger(ctx, "y");
                                    int z = IntegerArgumentType.getInteger(ctx, "z");
                                    var blockInput = BlockStateArgument.getBlock(ctx, "block");
                                    var blockState = blockInput.getState();
                                    int cubeX = Math.floorDiv(x, CubeChunk.SIZE);
                                    int cubeY = Math.floorDiv(y, CubeChunk.SIZE);
                                    int cubeZ = Math.floorDiv(z, CubeChunk.SIZE);
                                    int localX = Math.floorMod(x, CubeChunk.SIZE);
                                    int localY = Math.floorMod(y, CubeChunk.SIZE);
                                    int localZ = Math.floorMod(z, CubeChunk.SIZE);
                                    CubeChunk cube = savedData.getCubeWorld().getCube(cubeX, cubeY, cubeZ, true);
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    if (cube != null) {
                                        cube.setBlockState(localX, localY, localZ, blockState);
                                        savedData.setDirty();
                                        if (player != null) {
                                            player.sendSystemMessage(Component.literal("Set block at (" + x + ", " + y + ", " + z + ") to " + blockState.getBlock().getName().getString()));
                                            // Send cube sync to player (and optionally others)
                                            sendCubeSync(player, cube);
                                        }
                                    } else if (player != null) {
                                        player.sendSystemMessage(Component.literal("Failed to set block at (" + x + ", " + y + ", " + z + ")"));
                                    }
                                    return 1;
                                })
                            )
                        )
                    )
                )
        );
        
        // Command to get information about cubic chunks
        event.getDispatcher().register(
            Commands.literal("cubicinfo")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player != null) {
                        CubeWorld cubeWorld = savedData.getCubeWorld();
                        int loadedCubes = cubeWorld.getLoadedCubeCount();
                        int loadedColumns = cubeWorld.getLoadedColumnCount();
                        
                        player.sendSystemMessage(Component.literal("Cubic Chunks Info:"));
                        player.sendSystemMessage(Component.literal("- Loaded cubes: " + loadedCubes));
                        player.sendSystemMessage(Component.literal("- Loaded columns: " + loadedColumns));
                        
                        // Player position in cube coordinates
                        var pos = player.blockPosition();
                        int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
                        int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
                        int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
                        player.sendSystemMessage(Component.literal("- Your cube: (" + cubeX + ", " + cubeY + ", " + cubeZ + ")"));
                    }
                    return 1;
                })
        );
        
        // Command to teleport to a specific cube
        event.getDispatcher().register(
            Commands.literal("cubictp")
                .then(Commands.argument("cubeX", IntegerArgumentType.integer())
                    .then(Commands.argument("cubeY", IntegerArgumentType.integer())
                        .then(Commands.argument("cubeZ", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                int cubeX = IntegerArgumentType.getInteger(ctx, "cubeX");
                                int cubeY = IntegerArgumentType.getInteger(ctx, "cubeY");
                                int cubeZ = IntegerArgumentType.getInteger(ctx, "cubeZ");
                                ServerPlayer player = ctx.getSource().getPlayer();
                                if (player != null) {
                                    // Calculate world coordinates (center of the cube)
                                    double worldX = cubeX * CubeChunk.SIZE + 8.0;
                                    double worldY = cubeY * CubeChunk.SIZE + 8.0;
                                    double worldZ = cubeZ * CubeChunk.SIZE + 8.0;
                                    
                                    player.teleportTo(worldX, worldY, worldZ);
                                    player.sendSystemMessage(Component.literal("Teleported to cube (" + cubeX + ", " + cubeY + ", " + cubeZ + ")"));
                                }
                                return 1;
                            })
                        )
                    )
                )
        );
    }

    // Register network payloads for cube sync
    public void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MODID).versioned("1");
        registrar.playBidirectional(
            CubeSyncPayload.TYPE,
            CubeSyncPayload.STREAM_CODEC,
            new DirectionalPayloadHandler<>(
                (payload, context) -> {
                    // Client receives cube sync from server
                    CubicChunksClient.handleCubeSync(payload.cubeX(), payload.cubeY(), payload.cubeZ(), payload.cubeData());
                    LOGGER.debug("Received CubeSyncPayload on client: {} {} {}", payload.cubeX(), payload.cubeY(), payload.cubeZ());
                },
                (payload, context) -> {
                    // Server receives cube sync (e.g., from client)
                    // For now, just log receipt
                    LOGGER.info("Received CubeSyncPayload on server: {} {} {}", payload.cubeX(), payload.cubeY(), payload.cubeZ());
                    // Optionally, update the world/cube data here
                }
            )
        );
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
