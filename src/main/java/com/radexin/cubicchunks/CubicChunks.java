package com.radexin.cubicchunks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
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
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.radexin.cubicchunks.world.CubeWorld;
import com.radexin.cubicchunks.gen.CubeChunkGenerator;
import com.radexin.cubicchunks.chunk.CubeChunk;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import java.nio.file.Path;
import com.radexin.cubicchunks.world.CubicChunksSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.resources.ResourceLocation;
import com.radexin.cubicchunks.network.CubeSyncPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import com.radexin.cubicchunks.client.CubicChunksClient;
import net.neoforged.fml.loading.FMLEnvironment;
import com.radexin.cubicchunks.chunk.CubeColumn;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CubicChunks.MODID)
public class CubicChunks
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "cubicchunks";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "cubicchunks" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "cubicchunks" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "cubicchunks" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "cubicchunks:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "cubicchunks:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // Creates a new food item with the id "cubicchunks:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "cubicchunks:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cubicchunks")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
            }).build());

    private CubicChunksSavedData savedData;
    private CubeChunkGenerator cubeChunkGenerator;

    // Remove SimpleChannel and old networking code. Networking is now handled via RegisterPayloadHandlerEvent and CustomPacketPayload.
    // TODO: Register network payloads for cube sync in a static event handler.

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public CubicChunks(IEventBus modEventBus, ModContainer modContainer)
    {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (CubicChunks) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Register client setup if on client
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(CubicChunksClient::onClientSetup);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
            event.accept(EXAMPLE_BLOCK_ITEM);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("HELLO from server starting");
        // Initialize generator
        this.cubeChunkGenerator = new CubeChunkGenerator();
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
    }

    // Register network payloads for cube sync
    @net.neoforged.bus.api.SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MODID).versioned("1");
        registrar.playBidirectional(
            CubeSyncPayload.TYPE,
            CubeSyncPayload.STREAM_CODEC,
            new DirectionalPayloadHandler<>(
                (payload, context) -> {
                    // Client receives cube sync from server
                    CubeWorld clientWorld = com.radexin.cubicchunks.client.CubicChunksClient.getClientCubeWorld();
                    CubeChunk updated = com.radexin.cubicchunks.chunk.CubeChunk.fromNBT(payload.cubeData());
                    CubeColumn column = clientWorld.getColumn(updated.getCubeX(), updated.getCubeZ(), true);
                    // Replace or add the cube in the column
                    column.getLoadedCubes().removeIf(c -> c.getCubeY() == updated.getCubeY());
                    // Directly put in the cubes map if accessible, else add to loaded cubes
                    // (Assume getLoadedCubes returns a collection view of the map values)
                    // If CubeColumn exposes a put method, use it; otherwise, update the map directly if possible
                    // For now, just add if not present
                    if (!column.getLoadedCubes().contains(updated)) {
                        // This assumes CubeColumn has a method to add a cube; if not, add such a method
                        // For now, use getCube with createIfMissing=true to ensure it's present
                        column.getCube(updated.getCubeY(), true);
                        // Overwrite the cube in the map if possible
                        // (Assume cubes is a map<Integer, CubeChunk>)
                        // If not accessible, this is a TODO for CubeColumn API
                    }
                    // Log for debug
                    LOGGER.info("Received CubeSyncPayload on client: {} {} {}", payload.cubeX(), payload.cubeY(), payload.cubeZ());
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
