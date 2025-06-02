package com.radexin.cubicchunks;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
@EventBusSubscriber(modid = CubicChunks.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // General cubic chunks settings
    private static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    private static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    private static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // Cubic chunks specific settings
    private static final ModConfigSpec.IntValue VERTICAL_RENDER_DISTANCE = BUILDER
            .comment("Vertical render distance in cubes (similar to horizontal render distance)")
            .defineInRange("verticalRenderDistance", 8, 1, 32);

    private static final ModConfigSpec.IntValue MAX_LOADED_CUBES = BUILDER
            .comment("Maximum number of cubes to keep loaded per player")
            .defineInRange("maxLoadedCubes", 2048, 256, 8192);

    private static final ModConfigSpec.BooleanValue ENABLE_CUBIC_LIGHTING = BUILDER
            .comment("Enable 3D lighting calculations for cubic chunks")
            .define("enableCubicLighting", true);

    private static final ModConfigSpec.BooleanValue ENABLE_VERTICAL_BIOMES = BUILDER
            .comment("Enable vertical biome generation")
            .define("enableVerticalBiomes", true);

    private static final ModConfigSpec.IntValue CUBE_GENERATION_THREADS = BUILDER
            .comment("Number of threads to use for cube generation")
            .defineInRange("cubeGenerationThreads", 2, 1, 8);

    private static final ModConfigSpec.BooleanValue ENABLE_DEBUG_RENDERING = BUILDER
            .comment("Enable debug rendering for cubic chunks (shows cube boundaries)")
            .define("enableDebugRendering", false);

    private static final ModConfigSpec.DoubleValue LOD_DISTANCE_THRESHOLD = BUILDER
            .comment("Distance threshold for level-of-detail rendering")
            .defineInRange("lodDistanceThreshold", 64.0, 16.0, 256.0);

    private static final ModConfigSpec.BooleanValue ENABLE_CHUNK_CACHING = BUILDER
            .comment("Enable caching of chunk data to improve performance")
            .define("enableChunkCaching", true);

    private static final ModConfigSpec.IntValue CACHE_SIZE = BUILDER
            .comment("Size of the chunk cache")
            .defineInRange("cacheSize", 512, 64, 2048);

    // World generation settings
    private static final ModConfigSpec.BooleanValue ENABLE_CAVES = BUILDER
            .comment("Enable cave generation in cubic chunks")
            .define("enableCaves", true);

    private static final ModConfigSpec.BooleanValue ENABLE_ORES = BUILDER
            .comment("Enable ore generation in cubic chunks")
            .define("enableOres", true);

    private static final ModConfigSpec.IntValue WORLD_HEIGHT_LIMIT = BUILDER
            .comment("Maximum world height in cubes (each cube is 16 blocks)")
            .defineInRange("worldHeightLimit", 64, 16, 256);

    private static final ModConfigSpec.IntValue WORLD_DEPTH_LIMIT = BUILDER
            .comment("Maximum world depth in cubes (negative Y)")
            .defineInRange("worldDepthLimit", 16, 4, 64);

    // Performance settings
    private static final ModConfigSpec.BooleanValue ASYNC_CHUNK_LOADING = BUILDER
            .comment("Enable asynchronous chunk loading")
            .define("asyncChunkLoading", true);

    private static final ModConfigSpec.IntValue CHUNK_LOAD_RATE_LIMIT = BUILDER
            .comment("Maximum chunks to load per tick")
            .defineInRange("chunkLoadRateLimit", 4, 1, 16);

    // List of items to log
    private static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // Additional performance and feature settings
    private static final ModConfigSpec.BooleanValue ENABLE_LOD_RENDERING = BUILDER
        .comment("Enable Level of Detail rendering for distant cubes")
        .define("enableLODRendering", true);

    private static final ModConfigSpec.BooleanValue ENABLE_FRUSTUM_CULLING = BUILDER
        .comment("Enable frustum culling for cubic chunks")
        .define("enableFrustumCulling", true);

    private static final ModConfigSpec.IntValue RENDER_DISTANCE_3D = BUILDER
        .comment("3D render distance in cubic chunks")
        .defineInRange("renderDistance3D", 8, 1, 32);

    private static final ModConfigSpec.BooleanValue ENABLE_ADVANCED_TERRAIN = BUILDER
        .comment("Enable advanced 3D terrain generation")
        .define("enableAdvancedTerrain", true);

    private static final ModConfigSpec.BooleanValue ENABLE_UNDERGROUND_STRUCTURES = BUILDER
        .comment("Enable underground structure generation")
        .define("enableUndergroundStructures", true);

    private static final ModConfigSpec.IntValue MAX_ENTITIES_PER_CUBE = BUILDER
        .comment("Maximum entities per cubic chunk")
        .defineInRange("maxEntitiesPerCube", 10, 1, 50);

    private static final ModConfigSpec.IntValue ENTITY_SPAWN_RADIUS = BUILDER
        .comment("Radius for entity spawning around players")
        .defineInRange("entitySpawnRadius", 32, 8, 128);

    private static final ModConfigSpec.IntValue LIGHT_CACHE_SIZE = BUILDER
        .comment("Size of the lighting cache")
        .defineInRange("lightCacheSize", 10000, 1000, 100000);

    private static final ModConfigSpec.BooleanValue ENABLE_BATCHED_LIGHTING = BUILDER
        .comment("Enable batched lighting updates for performance")
        .define("enableBatchedLighting", true);

    private static final ModConfigSpec.BooleanValue ENABLE_ENTITY_SPATIAL_INDEXING = BUILDER
        .comment("Enable spatial indexing for entity queries")
        .define("enableEntitySpatialIndexing", true);

    private static final ModConfigSpec.BooleanValue ENABLE_PERFORMANCE_METRICS = BUILDER
        .comment("Enable performance metrics collection")
        .define("enablePerformanceMetrics", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;
    
    // Cubic chunks specific config values
    public static int verticalRenderDistance;
    public static int maxLoadedCubes;
    public static boolean enableCubicLighting;
    public static boolean enableVerticalBiomes;
    public static int cubeGenerationThreads;
    public static boolean enableDebugRendering;
    public static double lodDistanceThreshold;
    public static boolean enableChunkCaching;
    public static int cacheSize;
    public static boolean enableCaves;
    public static boolean enableOres;
    public static int worldHeightLimit;
    public static int worldDepthLimit;
    public static boolean asyncChunkLoading;
    public static int chunkLoadRateLimit;
    
    // New config values
    public static boolean enableLODRendering;
    public static boolean enableFrustumCulling;
    public static int renderDistance3D;
    public static boolean enableAdvancedTerrain;
    public static boolean enableUndergroundStructures;
    public static int maxEntitiesPerCube;
    public static int entitySpawnRadius;
    public static int lightCacheSize;
    public static boolean enableBatchedLighting;
    public static boolean enableEntitySpatialIndexing;
    public static boolean enablePerformanceMetrics;

    private static boolean validateItemName(final Object obj)
    {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // Convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream()
                .map(itemName -> BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemName)))
                .collect(Collectors.toSet());
        
        // Load cubic chunks specific config
        verticalRenderDistance = VERTICAL_RENDER_DISTANCE.get();
        maxLoadedCubes = MAX_LOADED_CUBES.get();
        enableCubicLighting = ENABLE_CUBIC_LIGHTING.get();
        enableVerticalBiomes = ENABLE_VERTICAL_BIOMES.get();
        cubeGenerationThreads = CUBE_GENERATION_THREADS.get();
        enableDebugRendering = ENABLE_DEBUG_RENDERING.get();
        lodDistanceThreshold = LOD_DISTANCE_THRESHOLD.get();
        enableChunkCaching = ENABLE_CHUNK_CACHING.get();
        cacheSize = CACHE_SIZE.get();
        enableCaves = ENABLE_CAVES.get();
        enableOres = ENABLE_ORES.get();
        worldHeightLimit = WORLD_HEIGHT_LIMIT.get();
        worldDepthLimit = WORLD_DEPTH_LIMIT.get();
        asyncChunkLoading = ASYNC_CHUNK_LOADING.get();
        chunkLoadRateLimit = CHUNK_LOAD_RATE_LIMIT.get();
        
        // Load new config values
        enableLODRendering = ENABLE_LOD_RENDERING.get();
        enableFrustumCulling = ENABLE_FRUSTUM_CULLING.get();
        renderDistance3D = RENDER_DISTANCE_3D.get();
        enableAdvancedTerrain = ENABLE_ADVANCED_TERRAIN.get();
        enableUndergroundStructures = ENABLE_UNDERGROUND_STRUCTURES.get();
        maxEntitiesPerCube = MAX_ENTITIES_PER_CUBE.get();
        entitySpawnRadius = ENTITY_SPAWN_RADIUS.get();
        lightCacheSize = LIGHT_CACHE_SIZE.get();
        enableBatchedLighting = ENABLE_BATCHED_LIGHTING.get();
        enableEntitySpatialIndexing = ENABLE_ENTITY_SPATIAL_INDEXING.get();
        enablePerformanceMetrics = ENABLE_PERFORMANCE_METRICS.get();
    }
}
