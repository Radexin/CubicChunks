package com.radexin.cubicchunks.chunk;

import com.radexin.cubicchunks.Config;
import com.radexin.cubicchunks.world.CubeWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Manages entity tracking, spawning, and storage for cubic chunks.
 * Provides efficient spatial partitioning and entity lifecycle management.
 */
public class CubicEntityManager {
    private final ServerLevel level;
    private final UnifiedCubicChunkManager chunkManager;
    
    // Entity tracking by cube
    private final Map<Long, Set<Entity>> entitiesBycube = new ConcurrentHashMap<>();
    private final Map<Entity, Long> entityToCube = new ConcurrentHashMap<>();
    
    // Spatial indexing for fast entity queries
    private final Map<Long, EntitySpatialBucket> spatialBuckets = new ConcurrentHashMap<>();
    private static final int BUCKET_SIZE = 8; // Entities grouped into 8x8x8 sub-chunks
    
    // Entity spawning
    private final Map<Long, Long> lastSpawnTime = new ConcurrentHashMap<>();
    private final Map<EntityType<?>, Integer> entityCounts = new ConcurrentHashMap<>();
    private final ExecutorService spawningExecutor = Executors.newFixedThreadPool(2);
    
    // Performance optimization
    private final Set<Long> activeSpawningCubes = ConcurrentHashMap.newKeySet();
    private final Map<Long, SpawnCache> spawnCache = new ConcurrentHashMap<>();
    
    // Entity culling for performance
    private final Map<Entity, Long> entityLastUpdate = new ConcurrentHashMap<>();
    private static final long ENTITY_TIMEOUT = 30000; // 30 seconds
    
    public CubicEntityManager(ServerLevel level, UnifiedCubicChunkManager chunkManager) {
        this.level = level;
        this.chunkManager = chunkManager;
    }
    
    /**
     * Loads entities from NBT data into the appropriate cubic chunk.
     */
    public void loadEntitiesFromNBT(CompoundTag chunkNBT, int cubeX, int cubeY, int cubeZ) {
        if (!chunkNBT.contains("entities")) {
            return;
        }

        CubeChunk cube = chunkManager.getCube(cubeX, cubeY, cubeZ, true);
        ListTag entitiesTag = chunkNBT.getList("entities", 10); // 10 = CompoundTag

        for (int i = 0; i < entitiesTag.size(); i++) {
            CompoundTag entityTag = entitiesTag.getCompound(i);
            loadEntityFromNBT(entityTag, cube);
        }
    }

    private void loadEntityFromNBT(CompoundTag entityTag, CubeChunk cube) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return; // Client-side entity loading is handled differently
        }

        try {
            Optional<EntityType<?>> entityTypeOptional = EntityType.by(entityTag);
            if (entityTypeOptional.isPresent()) {
                Entity entity = entityTypeOptional.get().create(serverLevel);
                if (entity != null) {
                    entity.load(entityTag);
                    // Verify entity is actually in this cube
                    if (cube.isEntityInCube(entity)) {
                        cube.addEntity(entity);
                        entityToCube.put(entity, getCubeKey(entity.position()));
                        serverLevel.addFreshEntity(entity);
                    }
                }
            }
        } catch (Exception e) {
            // Log error but continue loading other entities
            System.err.println("Failed to load entity from NBT: " + e.getMessage());
        }
    }

    /**
     * Tracks an entity and assigns it to the appropriate cubic chunk.
     */
    public void trackEntity(Entity entity) {
        CubeChunk currentCube = getCubeForEntity(entity);
        CubeChunk previousCube = entityToCube.get(entity) != null ? getCubeForEntity(entity) : null;

        if (currentCube != previousCube) {
            // Entity moved to a different cube
            if (previousCube != null) {
                previousCube.removeEntity(entity);
            }
            if (currentCube != null) {
                currentCube.addEntity(entity);
                entityToCube.put(entity, getCubeKey(entity.position()));
            } else {
                entityToCube.remove(entity);
            }
        }
    }

    /**
     * Removes an entity from tracking.
     */
    public void untrackEntity(Entity entity) {
        CubeChunk cube = entityToCube.get(entity) != null ? getCubeForEntity(entity) : null;
        if (cube != null) {
            cube.removeEntity(entity);
        }
        entityToCube.remove(entity);
    }

    /**
     * Gets the cubic chunk that should contain the given entity based on its position.
     */
    private CubeChunk getCubeForEntity(Entity entity) {
        BlockPos pos = entity.blockPosition();
        int cubeX = Math.floorDiv(pos.getX(), CubeChunk.SIZE);
        int cubeY = Math.floorDiv(pos.getY(), CubeChunk.SIZE);
        int cubeZ = Math.floorDiv(pos.getZ(), CubeChunk.SIZE);
        
        return chunkManager.getCube(cubeX, cubeY, cubeZ, false);
    }

    /**
     * Updates entity positions and moves them between cubes as needed.
     */
    public void updateEntityPositions() {
        for (Map.Entry<Entity, Long> entry : entityLastUpdate.entrySet()) {
            Entity entity = entry.getKey();
            long lastUpdate = entry.getValue();
            
            if (System.currentTimeMillis() - lastUpdate > ENTITY_TIMEOUT) {
                if (entity.isRemoved() || !entity.isAlive()) {
                    untrackEntity(entity);
                }
            }
        }
    }

    /**
     * Gets the cube chunk containing the specified entity.
     */
    public CubeChunk getChunkForEntity(Entity entity) {
        return getCubeForEntity(entity);
    }

    /**
     * Saves all entities from the given cube to NBT format.
     */
    public void saveEntitiesToNBT(CubeChunk cube, CompoundTag chunkNBT) {
        ListTag entitiesTag = new ListTag();
        for (Entity entity : cube.getEntities()) {
            if (!entity.isRemoved() && entity.shouldBeSaved()) {
                CompoundTag entityTag = new CompoundTag();
                if (entity.save(entityTag)) {
                    entitiesTag.add(entityTag);
                }
            }
        }
        chunkNBT.put("entities", entitiesTag);
    }

    /**
     * Adds an entity to the cubic chunk tracking system.
     */
    public void addEntity(Entity entity) {
        Vec3 pos = entity.position();
        long cubeKey = getCubeKey(pos);
        
        // Add to cube tracking
        entitiesBycube.computeIfAbsent(cubeKey, k -> ConcurrentHashMap.newKeySet()).add(entity);
        entityToCube.put(entity, cubeKey);
        
        // Add to spatial bucket
        long bucketKey = getBucketKey(pos);
        spatialBuckets.computeIfAbsent(bucketKey, k -> new EntitySpatialBucket()).addEntity(entity);
        
        // Update entity tracking
        entityLastUpdate.put(entity, System.currentTimeMillis());
        
        // Update type count
        EntityType<?> type = entity.getType();
        entityCounts.merge(type, 1, Integer::sum);
    }
    
    /**
     * Removes an entity from the tracking system.
     */
    public void removeEntity(Entity entity) {
        Long cubeKey = entityToCube.remove(entity);
        if (cubeKey != null) {
            Set<Entity> entities = entitiesBycube.get(cubeKey);
            if (entities != null) {
                entities.remove(entity);
                if (entities.isEmpty()) {
                    entitiesBycube.remove(cubeKey);
                }
            }
        }
        
        // Remove from spatial bucket
        Vec3 pos = entity.position();
        long bucketKey = getBucketKey(pos);
        EntitySpatialBucket bucket = spatialBuckets.get(bucketKey);
        if (bucket != null) {
            bucket.removeEntity(entity);
            if (bucket.isEmpty()) {
                spatialBuckets.remove(bucketKey);
            }
        }
        
        entityLastUpdate.remove(entity);
        
        // Update type count
        EntityType<?> type = entity.getType();
        entityCounts.computeIfPresent(type, (k, v) -> v > 1 ? v - 1 : null);
    }
    
    /**
     * Updates entity position when it moves between cubes.
     */
    public void updateEntityPosition(Entity entity, Vec3 oldPos, Vec3 newPos) {
        long oldCubeKey = getCubeKey(oldPos);
        long newCubeKey = getCubeKey(newPos);
        
        if (oldCubeKey != newCubeKey) {
            // Entity moved to different cube
            Long trackedCubeKey = entityToCube.get(entity);
            
            if (trackedCubeKey != null && trackedCubeKey == oldCubeKey) {
                // Remove from old cube
                Set<Entity> oldEntities = entitiesBycube.get(oldCubeKey);
                if (oldEntities != null) {
                    oldEntities.remove(entity);
                    if (oldEntities.isEmpty()) {
                        entitiesBycube.remove(oldCubeKey);
                    }
                }
                
                // Add to new cube
                entitiesBycube.computeIfAbsent(newCubeKey, k -> ConcurrentHashMap.newKeySet()).add(entity);
                entityToCube.put(entity, newCubeKey);
            }
        }
        
        // Update spatial bucket
        long oldBucketKey = getBucketKey(oldPos);
        long newBucketKey = getBucketKey(newPos);
        
        if (oldBucketKey != newBucketKey) {
            EntitySpatialBucket oldBucket = spatialBuckets.get(oldBucketKey);
            if (oldBucket != null) {
                oldBucket.removeEntity(entity);
                if (oldBucket.isEmpty()) {
                    spatialBuckets.remove(oldBucketKey);
                }
            }
            
            spatialBuckets.computeIfAbsent(newBucketKey, k -> new EntitySpatialBucket()).addEntity(entity);
        }
        
        entityLastUpdate.put(entity, System.currentTimeMillis());
    }
    
    /**
     * Gets all entities in a specific cube.
     */
    public java.util.Collection<Entity> getEntitiesInCube(int cubeX, int cubeY, int cubeZ) {
        long cubeKey = encodeCubeKey(cubeX, cubeY, cubeZ);
        Set<Entity> entities = entitiesBycube.get(cubeKey);
        return entities != null ? new java.util.ArrayList<>(entities) : java.util.Collections.emptyList();
    }
    
    /**
     * Gets entities within a spherical area using spatial indexing.
     */
    public <T extends Entity> java.util.List<T> getEntitiesOfClass(Class<T> entityClass, Vec3 center, double radius) {
        java.util.List<T> result = new java.util.ArrayList<>();
        double radiusSquared = radius * radius;
        
        // Calculate bucket range
        int bucketRadius = (int) Math.ceil(radius / BUCKET_SIZE);
        long centerBucketKey = getBucketKey(center);
        int[] centerBucket = decodeBucketKey(centerBucketKey);
        
        for (int dx = -bucketRadius; dx <= bucketRadius; dx++) {
            for (int dy = -bucketRadius; dy <= bucketRadius; dy++) {
                for (int dz = -bucketRadius; dz <= bucketRadius; dz++) {
                    long bucketKey = encodeBucketKey(
                        centerBucket[0] + dx,
                        centerBucket[1] + dy,
                        centerBucket[2] + dz
                    );
                    
                    EntitySpatialBucket bucket = spatialBuckets.get(bucketKey);
                    if (bucket != null) {
                        for (Entity entity : bucket.getEntities()) {
                            if (entityClass.isInstance(entity)) {
                                double distanceSquared = entity.position().distanceToSqr(center);
                                if (distanceSquared <= radiusSquared) {
                                    result.add(entityClass.cast(entity));
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    /**
     * Spawns entities in loaded cubes based on biome and light conditions.
     */
    public CompletableFuture<Void> spawnEntities(java.util.Collection<CubeChunk> loadedCubes) {
        return CompletableFuture.runAsync(() -> {
            long currentTime = System.currentTimeMillis();
            
            for (CubeChunk cube : loadedCubes) {
                long cubeKey = encodeCubeKey(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
                
                // Check if we should spawn in this cube
                if (shouldSpawnInCube(cube, currentTime)) {
                    spawnInCube(cube);
                    lastSpawnTime.put(cubeKey, currentTime);
                }
            }
        }, spawningExecutor);
    }
    
    private boolean shouldSpawnInCube(CubeChunk cube, long currentTime) {
        long cubeKey = encodeCubeKey(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        
        // Check spawn cooldown
        Long lastSpawn = lastSpawnTime.get(cubeKey);
        if (lastSpawn != null && currentTime - lastSpawn < 10000) { // 10 second cooldown
            return false;
        }
        
        // Check if players are nearby
        Vec3 cubeCenter = new Vec3(
            cube.getCubeX() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0,
            cube.getCubeY() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0,
            cube.getCubeZ() * CubeChunk.SIZE + CubeChunk.SIZE / 2.0
        );
        
        java.util.List<Player> nearbyPlayers = getEntitiesOfClass(Player.class, cubeCenter, 32.0); // 32 block radius
        if (nearbyPlayers.isEmpty()) {
            return false;
        }
        
        // Check entity density
        java.util.Collection<Entity> existingEntities = getEntitiesInCube(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        return existingEntities.size() < 10; // Max 10 entities per cube
    }
    
    private void spawnInCube(CubeChunk cube) {
        // Get spawn cache or create new one
        long cubeKey = encodeCubeKey(cube.getCubeX(), cube.getCubeY(), cube.getCubeZ());
        SpawnCache cache = spawnCache.computeIfAbsent(cubeKey, k -> new SpawnCache(cube));
        
        if (cache.isExpired()) {
            cache.rebuild(cube);
        }
        
        // Attempt spawning based on biome settings
        RandomSource random = level.getRandom();
        
        for (SpawnEntry entry : cache.getValidSpawns()) {
            if (random.nextFloat() < entry.weight) {
                trySpawnEntity(cube, entry.entityType, entry.spawnPos, random);
            }
        }
    }
    
    private void trySpawnEntity(CubeChunk cube, EntityType<?> entityType, BlockPos spawnPos, RandomSource random) {
        // Check spawn conditions
        if (!canSpawnAt(entityType, spawnPos, cube)) {
            return;
        }
        
        // Check entity limits
        int currentCount = entityCounts.getOrDefault(entityType, 0);
        if (currentCount >= 100) { // Max 100 entities of any type
            return;
        }
        
        // Create and spawn entity
        Entity entity = entityType.create(level);
        if (entity != null) {
            entity.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            
            // Finalize spawning
            if (entity instanceof Mob mob) {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos), 
                    MobSpawnType.NATURAL, null);
            }
            
            level.addFreshEntity(entity);
            addEntity(entity);
        }
    }
    
    private boolean canSpawnAt(EntityType<?> entityType, BlockPos pos, CubeChunk cube) {
        // Check basic spawn requirements
        if (!SpawnPlacements.checkSpawnRules(entityType, level, MobSpawnType.NATURAL, pos, level.getRandom())) {
            return false;
        }
        
        // Check light levels
        int lightLevel = cube.getLightLevel(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        
        if (Monster.class.isAssignableFrom(entityType.getBaseClass())) {
            // Monsters spawn in dark areas
            return lightLevel <= 7;
        } else {
            // Peaceful mobs spawn in light areas
            return lightLevel >= 9;
        }
    }
    
    /**
     * Performs entity culling to maintain performance.
     */
    public void performEntityCulling() {
        long currentTime = System.currentTimeMillis();
        java.util.List<Entity> entitiesToRemove = new java.util.ArrayList<>();
        
        for (Map.Entry<Entity, Long> entry : entityLastUpdate.entrySet()) {
            Entity entity = entry.getKey();
            long lastUpdate = entry.getValue();
            
            // Remove entities that haven't been updated recently
            if (currentTime - lastUpdate > ENTITY_TIMEOUT) {
                if (entity.isRemoved() || !entity.isAlive()) {
                    entitiesToRemove.add(entity);
                }
            }
        }
        
        for (Entity entity : entitiesToRemove) {
            removeEntity(entity);
        }
    }
    
    /**
     * Gets performance statistics.
     */
    public EntityStats getStats() {
        int totalEntities = entityToCube.size();
        int activeCubes = entitiesBycube.size();
        int spatialBuckets = this.spatialBuckets.size();
        
        Map<EntityType<?>, Integer> typeCounts = new HashMap<>(entityCounts);
        
        return new EntityStats(totalEntities, activeCubes, spatialBuckets, typeCounts);
    }
    
    private long getCubeKey(Vec3 pos) {
        int cubeX = (int) Math.floor(pos.x / CubeChunk.SIZE);
        int cubeY = (int) Math.floor(pos.y / CubeChunk.SIZE);
        int cubeZ = (int) Math.floor(pos.z / CubeChunk.SIZE);
        return encodeCubeKey(cubeX, cubeY, cubeZ);
    }
    
    private long getBucketKey(Vec3 pos) {
        int bucketX = (int) Math.floor(pos.x / BUCKET_SIZE);
        int bucketY = (int) Math.floor(pos.y / BUCKET_SIZE);
        int bucketZ = (int) Math.floor(pos.z / BUCKET_SIZE);
        return encodeBucketKey(bucketX, bucketY, bucketZ);
    }
    
    private static long encodeCubeKey(int x, int y, int z) {
        return ((long)x << 32) | ((long)y << 16) | (z & 0xFFFFL);
    }
    
    private static long encodeBucketKey(int x, int y, int z) {
        return ((long)x << 32) | ((long)y << 16) | (z & 0xFFFFL);
    }
    
    private static int[] decodeBucketKey(long key) {
        int x = (int)(key >> 32);
        int y = (int)((key >> 16) & 0xFFFF);
        int z = (int)(key & 0xFFFF);
        return new int[]{x, y, z};
    }
    
    public void shutdown() {
        spawningExecutor.shutdown();
    }
    
    // Helper classes
    private static class EntitySpatialBucket {
        private final java.util.Set<Entity> entities = ConcurrentHashMap.newKeySet();
        
        void addEntity(Entity entity) {
            entities.add(entity);
        }
        
        void removeEntity(Entity entity) {
            entities.remove(entity);
        }
        
        java.util.Set<Entity> getEntities() {
            return entities;
        }
        
        boolean isEmpty() {
            return entities.isEmpty();
        }
    }
    
    private static class SpawnCache {
        private static final long CACHE_DURATION = 60000; // 1 minute
        
        private java.util.List<SpawnEntry> validSpawns = new java.util.ArrayList<>();
        private long lastUpdate = 0;
        
        SpawnCache(CubeChunk cube) {
            rebuild(cube);
        }
        
        void rebuild(CubeChunk cube) {
            validSpawns.clear();
            lastUpdate = System.currentTimeMillis();
            
            // Analyze cube for valid spawn positions
            for (int y = 0; y < CubeChunk.SIZE; y++) {
                for (int z = 0; z < CubeChunk.SIZE; z++) {
                    for (int x = 0; x < CubeChunk.SIZE; x++) {
                        BlockPos pos = cube.getWorldPos(x, y, z);
                        
                        // Check if this position is suitable for spawning
                        if (isValidSpawnPosition(cube, x, y, z)) {
                            // Determine what can spawn here based on biome
                            java.util.List<MobSpawnSettings.SpawnerData> possibleSpawns = getPossibleSpawns(cube, pos);
                            
                            for (MobSpawnSettings.SpawnerData spawnerData : possibleSpawns) {
                                validSpawns.add(new SpawnEntry(spawnerData.type, pos, spawnerData.getWeight().asInt()));
                            }
                        }
                    }
                }
            }
        }
        
        private boolean isValidSpawnPosition(CubeChunk cube, int x, int y, int z) {
            // Check if position has solid ground and air above
            if (y == 0) return false;
            
            return !cube.getBlockState(x, y, z).isAir() && 
                   cube.getBlockState(x, y + 1, z).isAir() &&
                   (y + 2 >= CubeChunk.SIZE || cube.getBlockState(x, y + 2, z).isAir());
        }
        
        private java.util.List<MobSpawnSettings.SpawnerData> getPossibleSpawns(CubeChunk cube, BlockPos pos) {
            // Get biome spawn settings - simplified implementation
            return new java.util.ArrayList<>(); // Return empty list for now
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - lastUpdate > CACHE_DURATION;
        }
        
        java.util.List<SpawnEntry> getValidSpawns() {
            return validSpawns;
        }
    }
    
    private static class SpawnEntry {
        final EntityType<?> entityType;
        final BlockPos spawnPos;
        final float weight;
        
        SpawnEntry(EntityType<?> entityType, BlockPos spawnPos, int weight) {
            this.entityType = entityType;
            this.spawnPos = spawnPos;
            this.weight = weight / 100.0f; // Convert to probability
        }
    }
    
    public static class EntityStats {
        public final int totalEntities;
        public final int activeCubes;
        public final int spatialBuckets;
        public final Map<EntityType<?>, Integer> entityCounts;
        
        EntityStats(int totalEntities, int activeCubes, int spatialBuckets, Map<EntityType<?>, Integer> entityCounts) {
            this.totalEntities = totalEntities;
            this.activeCubes = activeCubes;
            this.spatialBuckets = spatialBuckets;
            this.entityCounts = entityCounts;
        }
    }
} 