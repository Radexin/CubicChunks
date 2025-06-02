package com.radexin.cubicchunks.gen;

import com.radexin.cubicchunks.chunk.CubeChunk;
import com.radexin.cubicchunks.chunk.CubicChunkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Integrates vanilla structure generation with cubic chunks.
 * Handles cross-cube structure placement, deferred generation, and proper structure validation.
 */
public class VanillaStructureIntegrator {
    private final Level level;
    private final CubicChunkManager chunkManager;
    private final Enhanced3DBiomeProvider biomeProvider;
    private final StructureManager structureManager;
    
    // Structure generation executor
    private final ExecutorService structureExecutor = Executors.newFixedThreadPool(2);
    
    // Structure tracking and coordination
    private final Map<Long, PendingStructure> pendingStructures = new ConcurrentHashMap<>();
    private final Set<Long> generatedStructures = ConcurrentHashMap.newKeySet();
    private final Map<Long, List<StructureFeature>> cubeStructures = new ConcurrentHashMap<>();
    
    // Structure placement rules
    private final Map<Class<? extends Structure>, StructurePlacementRules> placementRules = new HashMap<>();
    
    public VanillaStructureIntegrator(Level level, CubicChunkManager chunkManager, Enhanced3DBiomeProvider biomeProvider) {
        this.level = level;
        this.chunkManager = chunkManager;
        this.biomeProvider = biomeProvider;
        this.structureManager = null; // TODO: Initialize properly when available
        
        initializePlacementRules();
    }
    
    /**
     * Generates structures for a cubic chunk.
     */
    public CompletableFuture<Void> generateStructures(CubeChunk cube) {
        return CompletableFuture.runAsync(() -> {
            try {
                generateStructuresSync(cube);
            } catch (Exception e) {
                // Log error and continue
                System.err.println("Error generating structures for cube " + 
                    cube.getCubeX() + "," + cube.getCubeY() + "," + cube.getCubeZ() + ": " + e.getMessage());
            }
        }, structureExecutor);
    }
    
    private void generateStructuresSync(CubeChunk cube) {
        // Check for pending structures that should be completed
        processPendingStructures(cube);
        
        // Generate new structures
        generateNewStructures(cube);
        
        // Apply generated structures to the cube
        applyStructuresToCube(cube);
    }
    
    private void initializePlacementRules() {
        // Initialize structure placement rules for different structure types
        placementRules.put(Structure.class, new StructurePlacementRules(
            8, 24,  // minSpacing, maxSpacing
            Arrays.asList("plains", "forest", "desert"), // validBiomes
            32, 128, // minHeight, maxHeight
            false    // requiresSurface
        ));
    }
    
    private void processPendingStructures(CubeChunk cube) {
        // Find pending structures that might affect this cube
        List<PendingStructure> applicableStructures = new ArrayList<>();
        
        for (PendingStructure pending : pendingStructures.values()) {
            if (pending.affectsCube(cube)) {
                applicableStructures.add(pending);
            }
        }
        
        // Process applicable structures
        for (PendingStructure structure : applicableStructures) {
            if (structure.canGenerate(chunkManager)) {
                generateStructure(structure);
                pendingStructures.remove(structure.id);
                generatedStructures.add(structure.id);
            }
        }
    }
    
    private void generateNewStructures(CubeChunk cube) {
        // Only generate structures on surface-level cubes for now
        if (cube.getCubeY() < 0 || cube.getCubeY() > 8) {
            return;
        }
        
        RandomSource random = RandomSource.create();
        long seed = ((long) cube.getCubeX() << 32) | ((long) cube.getCubeZ() << 16) | cube.getCubeY();
        random = RandomSource.create(seed);
        
        // Check for various structure types
        tryGenerateVillage(cube, random);
        tryGenerateDungeon(cube, random);
        tryGenerateMineshaft(cube, random);
        tryGenerateRuinedPortal(cube, random);
    }
    
    private void tryGenerateVillage(CubeChunk cube, RandomSource random) {
        if (random.nextFloat() < 0.002) { // 0.2% chance
            BlockPos center = findSuitableVillageLocation(cube, random);
            if (center != null) {
                Holder<Biome> biome = biomeProvider.getBiome3D(center.getX(), center.getY(), center.getZ());
                if (isValidVillageBiome(biome)) {
                    scheduleVillageGeneration(center, biome, random.nextLong());
                }
            }
        }
    }
    
    private void tryGenerateDungeon(CubeChunk cube, RandomSource random) {
        // Only generate dungeons underground
        if (cube.getCubeY() >= 4) return;
        
        if (random.nextFloat() < 0.008) { // 0.8% chance
            BlockPos center = new BlockPos(
                cube.getCubeX() * CubeChunk.SIZE + random.nextInt(CubeChunk.SIZE),
                cube.getCubeY() * CubeChunk.SIZE + random.nextInt(CubeChunk.SIZE),
                cube.getCubeZ() * CubeChunk.SIZE + random.nextInt(CubeChunk.SIZE)
            );
            
            scheduleDungeonGeneration(center, random.nextLong());
        }
    }
    
    private void tryGenerateMineshaft(CubeChunk cube, RandomSource random) {
        // Only generate mineshafts underground
        if (cube.getCubeY() >= 2) return;
        
        if (random.nextFloat() < 0.004) { // 0.4% chance
            BlockPos start = new BlockPos(
                cube.getCubeX() * CubeChunk.SIZE + random.nextInt(CubeChunk.SIZE),
                cube.getCubeY() * CubeChunk.SIZE + random.nextInt(CubeChunk.SIZE),
                cube.getCubeZ() * CubeChunk.SIZE + random.nextInt(CubeChunk.SIZE)
            );
            
            scheduleMineshaftGeneration(start, random.nextLong());
        }
    }
    
    private void tryGenerateRuinedPortal(CubeChunk cube, RandomSource random) {
        if (random.nextFloat() < 0.003) { // 0.3% chance
            BlockPos center = findSuitablePortalLocation(cube, random);
            if (center != null) {
                scheduleRuinedPortalGeneration(center, random.nextLong());
            }
        }
    }
    
    private BlockPos findSuitableVillageLocation(CubeChunk cube, RandomSource random) {
        // Find a relatively flat area suitable for village generation
        for (int attempt = 0; attempt < 10; attempt++) {
            int localX = random.nextInt(CubeChunk.SIZE);
            int localZ = random.nextInt(CubeChunk.SIZE);
            
            // Look for surface level
            for (int localY = CubeChunk.SIZE - 1; localY >= 0; localY--) {
                BlockState state = cube.getBlockState(localX, localY, localZ);
                if (!state.isAir() && state.isSolid()) {
                    BlockPos pos = cube.getWorldPos(localX, localY + 1, localZ);
                    if (isSuitableVillageGround(pos)) {
                        return pos;
                    }
                    break;
                }
            }
        }
        return null;
    }
    
    private BlockPos findSuitablePortalLocation(CubeChunk cube, RandomSource random) {
        // Find a location suitable for a ruined portal
        int localX = random.nextInt(CubeChunk.SIZE);
        int localY = random.nextInt(CubeChunk.SIZE);
        int localZ = random.nextInt(CubeChunk.SIZE);
        
        return cube.getWorldPos(localX, localY, localZ);
    }
    
    private boolean isSuitableVillageGround(BlockPos pos) {
        // Check if the area around pos is suitable for village generation
        int flatCount = 0;
        int radius = 8;
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos checkPos = pos.offset(x, 0, z);
                CubeChunk cube = getCubeForPosition(checkPos);
                if (cube != null) {
                    int localX = checkPos.getX() & 15;
                    int localY = checkPos.getY() & 15;
                    int localZ = checkPos.getZ() & 15;
                    
                    BlockState groundState = cube.getBlockState(localX, localY - 1, localZ);
                    BlockState airState = cube.getBlockState(localX, localY, localZ);
                    
                    if (groundState.isSolid() && airState.isAir()) {
                        flatCount++;
                    }
                }
            }
        }
        
        return flatCount > (radius * 2 + 1) * (radius * 2 + 1) * 0.6; // 60% flat
    }
    
    private boolean isValidVillageBiome(Holder<Biome> biome) {
        // Check if biome is suitable for village generation
        return true; // Simplified for now
    }
    
    private void scheduleVillageGeneration(BlockPos center, Holder<Biome> biome, long seed) {
        long structureId = generateStructureId(center, "village");
        
        if (!generatedStructures.contains(structureId)) {
            BoundingBox bounds = new BoundingBox(
                center.getX() - 64, center.getY() - 16, center.getZ() - 64,
                center.getX() + 64, center.getY() + 32, center.getZ() + 64
            );
            
            PendingStructure structure = new PendingStructure(
                structureId, "village", center, bounds, biome, seed
            );
            
            pendingStructures.put(structureId, structure);
        }
    }
    
    private void scheduleDungeonGeneration(BlockPos center, long seed) {
        long structureId = generateStructureId(center, "dungeon");
        
        if (!generatedStructures.contains(structureId)) {
            BoundingBox bounds = new BoundingBox(
                center.getX() - 8, center.getY() - 4, center.getZ() - 8,
                center.getX() + 8, center.getY() + 4, center.getZ() + 8
            );
            
            PendingStructure structure = new PendingStructure(
                structureId, "dungeon", center, bounds, null, seed
            );
            
            pendingStructures.put(structureId, structure);
        }
    }
    
    private void scheduleMineshaftGeneration(BlockPos start, long seed) {
        long structureId = generateStructureId(start, "mineshaft");
        
        if (!generatedStructures.contains(structureId)) {
            BoundingBox bounds = new BoundingBox(
                start.getX() - 32, start.getY() - 16, start.getZ() - 32,
                start.getX() + 32, start.getY() + 16, start.getZ() + 32
            );
            
            PendingStructure structure = new PendingStructure(
                structureId, "mineshaft", start, bounds, null, seed
            );
            
            pendingStructures.put(structureId, structure);
        }
    }
    
    private void scheduleRuinedPortalGeneration(BlockPos center, long seed) {
        long structureId = generateStructureId(center, "ruined_portal");
        
        if (!generatedStructures.contains(structureId)) {
            BoundingBox bounds = new BoundingBox(
                center.getX() - 8, center.getY() - 4, center.getZ() - 8,
                center.getX() + 8, center.getY() + 8, center.getZ() + 8
            );
            
            PendingStructure structure = new PendingStructure(
                structureId, "ruined_portal", center, bounds, null, seed
            );
            
            pendingStructures.put(structureId, structure);
        }
    }
    
    private void generateStructure(PendingStructure structure) {
        // Generate the actual structure blocks
        List<StructureFeature> features = new ArrayList<>();
        
        switch (structure.type) {
            case "village":
                features.addAll(generateVillageFeatures(structure));
                break;
            case "dungeon":
                features.addAll(generateDungeonFeatures(structure));
                break;
            case "mineshaft":
                features.addAll(generateMineshaftFeatures(structure));
                break;
            case "ruined_portal":
                features.addAll(generateRuinedPortalFeatures(structure));
                break;
        }
        
        // Store features for application to cubes
        for (StructureFeature feature : features) {
            long cubeKey = packCubeCoords(
                Math.floorDiv(feature.pos.getX(), CubeChunk.SIZE),
                Math.floorDiv(feature.pos.getY(), CubeChunk.SIZE),
                Math.floorDiv(feature.pos.getZ(), CubeChunk.SIZE)
            );
            
            cubeStructures.computeIfAbsent(cubeKey, k -> new ArrayList<>()).add(feature);
        }
    }
    
    private List<StructureFeature> generateVillageFeatures(PendingStructure structure) {
        List<StructureFeature> features = new ArrayList<>();
        RandomSource random = RandomSource.create(structure.seed);
        
        // Generate simple village features (houses, wells, etc.)
        int numBuildings = 3 + random.nextInt(5);
        
        for (int i = 0; i < numBuildings; i++) {
            BlockPos buildingPos = structure.center.offset(
                random.nextInt(40) - 20,
                0,
                random.nextInt(40) - 20
            );
            
            features.addAll(generateSimpleHouse(buildingPos, random));
        }
        
        // Add a well
        features.addAll(generateWell(structure.center, random));
        
        return features;
    }
    
    private List<StructureFeature> generateDungeonFeatures(PendingStructure structure) {
        List<StructureFeature> features = new ArrayList<>();
        RandomSource random = RandomSource.create(structure.seed);
        
        // Generate a simple dungeon room
        int width = 5 + random.nextInt(3);
        int height = 3 + random.nextInt(2);
        int depth = 5 + random.nextInt(3);
        
        BlockPos corner = structure.center.offset(-width/2, -height/2, -depth/2);
        
        // Generate walls
        for (int x = 0; x <= width; x++) {
            for (int y = 0; y <= height; y++) {
                for (int z = 0; z <= depth; z++) {
                    boolean isWall = x == 0 || x == width || y == 0 || y == height || z == 0 || z == depth;
                    boolean isInterior = x > 0 && x < width && y > 0 && y < height && z > 0 && z < depth;
                    
                    BlockPos pos = corner.offset(x, y, z);
                    
                    if (isWall) {
                        features.add(new StructureFeature(pos, Blocks.COBBLESTONE.defaultBlockState()));
                    } else if (isInterior) {
                        features.add(new StructureFeature(pos, Blocks.AIR.defaultBlockState()));
                    }
                }
            }
        }
        
        // Add spawner in center
        features.add(new StructureFeature(structure.center, Blocks.SPAWNER.defaultBlockState()));
        
        return features;
    }
    
    private List<StructureFeature> generateMineshaftFeatures(PendingStructure structure) {
        List<StructureFeature> features = new ArrayList<>();
        RandomSource random = RandomSource.create(structure.seed);
        
        // Generate simple mineshaft tunnels
        int numTunnels = 2 + random.nextInt(4);
        
        for (int i = 0; i < numTunnels; i++) {
            features.addAll(generateMineshaftTunnel(structure.center, random));
        }
        
        return features;
    }
    
    private List<StructureFeature> generateRuinedPortalFeatures(PendingStructure structure) {
        List<StructureFeature> features = new ArrayList<>();
        RandomSource random = RandomSource.create(structure.seed);
        
        // Generate a simple ruined portal frame
        BlockPos corner = structure.center.offset(-2, 0, -1);
        
        // Portal frame
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                boolean isFrame = (x == 0 || x == 3) || (y == 0 || y == 4);
                boolean isDamaged = random.nextFloat() < 0.3;
                
                BlockPos pos = corner.offset(x, y, 0);
                
                if (isFrame && !isDamaged) {
                    features.add(new StructureFeature(pos, Blocks.OBSIDIAN.defaultBlockState()));
                } else if (!isFrame) {
                    features.add(new StructureFeature(pos, Blocks.NETHER_PORTAL.defaultBlockState()));
                }
            }
        }
        
        return features;
    }
    
    private List<StructureFeature> generateSimpleHouse(BlockPos corner, RandomSource random) {
        List<StructureFeature> features = new ArrayList<>();
        
        int width = 5 + random.nextInt(3);
        int height = 4;
        int depth = 5 + random.nextInt(3);
        
        // Generate house structure
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos pos = corner.offset(x, y, z);
                    
                    if (y == 0) {
                        // Floor
                        features.add(new StructureFeature(pos, Blocks.OAK_PLANKS.defaultBlockState()));
                    } else if (y == height - 1) {
                        // Roof
                        features.add(new StructureFeature(pos, Blocks.OAK_PLANKS.defaultBlockState()));
                    } else if (x == 0 || x == width - 1 || z == 0 || z == depth - 1) {
                        // Walls (with some doors/windows)
                        if (random.nextFloat() < 0.1) {
                            features.add(new StructureFeature(pos, Blocks.GLASS.defaultBlockState()));
                        } else {
                            features.add(new StructureFeature(pos, Blocks.COBBLESTONE.defaultBlockState()));
                        }
                    } else {
                        // Interior air
                        features.add(new StructureFeature(pos, Blocks.AIR.defaultBlockState()));
                    }
                }
            }
        }
        
        return features;
    }
    
    private List<StructureFeature> generateWell(BlockPos center, RandomSource random) {
        List<StructureFeature> features = new ArrayList<>();
        
        // Simple well structure
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = center.offset(x, 0, z);
                if (x == 0 && z == 0) {
                    features.add(new StructureFeature(pos, Blocks.WATER.defaultBlockState()));
                } else {
                    features.add(new StructureFeature(pos, Blocks.COBBLESTONE.defaultBlockState()));
                }
            }
        }
        
        return features;
    }
    
    private List<StructureFeature> generateMineshaftTunnel(BlockPos start, RandomSource random) {
        List<StructureFeature> features = new ArrayList<>();
        
        // Generate a simple tunnel
        int length = 16 + random.nextInt(16);
        int direction = random.nextInt(4); // 0=north, 1=east, 2=south, 3=west
        
        int dx = direction == 1 ? 1 : direction == 3 ? -1 : 0;
        int dz = direction == 0 ? -1 : direction == 2 ? 1 : 0;
        
        for (int i = 0; i < length; i++) {
            BlockPos pos = start.offset(dx * i, 0, dz * i);
            
            // Clear tunnel space
            for (int y = 0; y < 3; y++) {
                features.add(new StructureFeature(pos.offset(0, y, 0), Blocks.AIR.defaultBlockState()));
            }
            
            // Add supports occasionally
            if (i % 4 == 0) {
                features.add(new StructureFeature(pos.offset(0, 0, 0), Blocks.OAK_FENCE.defaultBlockState()));
                features.add(new StructureFeature(pos.offset(0, 1, 0), Blocks.OAK_FENCE.defaultBlockState()));
                features.add(new StructureFeature(pos.offset(0, 2, 0), Blocks.OAK_PLANKS.defaultBlockState()));
            }
        }
        
        return features;
    }
    
    private void applyStructuresToCube(CubeChunk cube) {
        long cubeKey = packCubeCoords(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        List<StructureFeature> features = cubeStructures.get(cubeKey);
        
        if (features != null) {
            for (StructureFeature feature : features) {
                int localX = feature.pos.getX() & 15;
                int localY = feature.pos.getY() & 15;
                int localZ = feature.pos.getZ() & 15;
                
                // Only apply if position is within this cube
                if (localX >= 0 && localX < CubeChunk.SIZE &&
                    localY >= 0 && localY < CubeChunk.SIZE &&
                    localZ >= 0 && localZ < CubeChunk.SIZE) {
                    
                    cube.setBlockState(localX, localY, localZ, feature.blockState);
                }
            }
            
            // Remove applied features
            cubeStructures.remove(cubeKey);
        }
    }
    
    private CubeChunk getCubeForPosition(BlockPos pos) {
        int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
        int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        return chunkManager.getCube(cubeX, cubeY, cubeZ);
    }
    
    private long generateStructureId(BlockPos pos, String type) {
        return ((long) pos.getX() << 32) | ((long) pos.getZ() << 16) | pos.getY() | (type.hashCode() & 0xFFL);
    }
    
    private static long packCubeCoords(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) |
               (((long) y & 0x1FFFFF) << 21) |
               (((long) z & 0x1FFFFF) << 42);
    }
    
    public void shutdown() {
        structureExecutor.shutdown();
    }
    
    // Data classes
    private static class PendingStructure {
        final long id;
        final String type;
        final BlockPos center;
        final BoundingBox bounds;
        final Holder<Biome> biome;
        final long seed;
        
        PendingStructure(long id, String type, BlockPos center, BoundingBox bounds, Holder<Biome> biome, long seed) {
            this.id = id;
            this.type = type;
            this.center = center;
            this.bounds = bounds;
            this.biome = biome;
            this.seed = seed;
        }
        
        boolean affectsCube(CubeChunk cube) {
            int cubeMinX = cube.getCubeX() * CubeChunk.SIZE;
            int cubeMinY = cube.getCubeY() * CubeChunk.SIZE;
            int cubeMinZ = cube.getCubeZ() * CubeChunk.SIZE;
            int cubeMaxX = cubeMinX + CubeChunk.SIZE - 1;
            int cubeMaxY = cubeMinY + CubeChunk.SIZE - 1;
            int cubeMaxZ = cubeMinZ + CubeChunk.SIZE - 1;
            
            // Check if bounding box overlaps with cube
            return bounds.minX() <= cubeMaxX && bounds.maxX() >= cubeMinX &&
                   bounds.minY() <= cubeMaxY && bounds.maxY() >= cubeMinY &&
                   bounds.minZ() <= cubeMaxZ && bounds.maxZ() >= cubeMinZ;
        }
        
        boolean canGenerate(CubicChunkManager chunkManager) {
            // Check if all required cubes are loaded
            int minCubeX = Math.floorDiv(bounds.minX(), CubeChunk.SIZE);
            int minCubeY = Math.floorDiv(bounds.minY(), CubeChunk.SIZE);
            int minCubeZ = Math.floorDiv(bounds.minZ(), CubeChunk.SIZE);
            int maxCubeX = Math.floorDiv(bounds.maxX(), CubeChunk.SIZE);
            int maxCubeY = Math.floorDiv(bounds.maxY(), CubeChunk.SIZE);
            int maxCubeZ = Math.floorDiv(bounds.maxZ(), CubeChunk.SIZE);
            
            for (int x = minCubeX; x <= maxCubeX; x++) {
                for (int y = minCubeY; y <= maxCubeY; y++) {
                    for (int z = minCubeZ; z <= maxCubeZ; z++) {
                        if (chunkManager.getCube(x, y, z) == null) {
                            return false;
                        }
                    }
                }
            }
            
            return true;
        }
    }
    
    private static class StructureFeature {
        final BlockPos pos;
        final BlockState blockState;
        
        StructureFeature(BlockPos pos, BlockState blockState) {
            this.pos = pos;
            this.blockState = blockState;
        }
    }
    
    private static class StructurePlacementRules {
        final int minSpacing;
        final int maxSpacing;
        final List<String> validBiomes;
        final int minHeight;
        final int maxHeight;
        final boolean requiresSurface;
        
        StructurePlacementRules(int minSpacing, int maxSpacing, List<String> validBiomes, 
                               int minHeight, int maxHeight, boolean requiresSurface) {
            this.minSpacing = minSpacing;
            this.maxSpacing = maxSpacing;
            this.validBiomes = validBiomes;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.requiresSurface = requiresSurface;
        }
    }
} 