package com.radexin.cubicchunks.chunk;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Predicate;
import java.util.Collections;
import java.util.stream.Stream;

/**
 * Represents a single 16x16x16 cube in the cubic chunks world.
 * Handles block storage, entity management, lighting, and ticking.
 * Provides integration with Minecraft's chunk system through adapter methods.
 */
public class CubeChunk {
    public static final int SIZE = 16;
    private final int cubeX, cubeY, cubeZ;
    private final ChunkPos chunkPos;
    
    // Block storage using PalettedContainer for better memory efficiency
    private final PalettedContainer<BlockState> blockContainer;
    private final PalettedContainer<Holder<Biome>> biomeContainer;
    
    // Lighting arrays
    private final byte[] skyLight = new byte[SIZE * SIZE * SIZE];
    private final byte[] blockLight = new byte[SIZE * SIZE * SIZE];
    
    // Entity and block entity management
    private final List<Entity> entities = new ArrayList<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    
    // Tick scheduling
    private final Map<BlockPos, Long> scheduledTicks = new HashMap<>();
    
    // Chunk status and metadata
    private boolean isDirty = false;
    private boolean isGenerated = false;
    private long lastTickTime = 0;
    private long inhabitedTime = 0;

    public CubeChunk(int cubeX, int cubeY, int cubeZ, Registry<Biome> biomeRegistry) {
        this.cubeX = cubeX;
        this.cubeY = cubeY;
        this.cubeZ = cubeZ;
        this.chunkPos = new ChunkPos(cubeX, cubeZ);
        
        // Initialize paletted containers
        this.blockContainer = new PalettedContainer<>(
            Block.BLOCK_STATE_REGISTRY,
            Blocks.AIR.defaultBlockState(),
            PalettedContainer.Strategy.SECTION_STATES
        );
        
        // Handle null biome registry by using a default biome
        if (biomeRegistry != null) {
            this.biomeContainer = new PalettedContainer<>(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES
            );
        } else {
            // Create a minimal biome container with a default biome
            this.biomeContainer = new PalettedContainer<>(
                null, // No registry available
                null, // No default biome holder
                PalettedContainer.Strategy.SECTION_BIOMES
            );
        }
        
        // Initialize lighting
        for (int i = 0; i < skyLight.length; i++) {
            skyLight[i] = 0;
            blockLight[i] = 0;
        }
    }

    // Core block access methods
    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    public BlockState getBlockState(int x, int y, int z) {
        checkBounds(x, y, z);
        return blockContainer.get(x, y, z);
    }

    public BlockState setBlockState(BlockPos pos, BlockState state, boolean moved) {
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        return setBlockState(localX, localY, localZ, state);
    }

    public BlockState setBlockState(int x, int y, int z, BlockState state) {
        checkBounds(x, y, z);
        BlockState oldState = blockContainer.getAndSet(x, y, z, state);
        
        if (!oldState.equals(state)) {
            isDirty = true;
            
            // Update lighting if block light properties changed
            updateLightingForBlock(x, y, z, state);
            
            // Handle block entity changes
            handleBlockEntityChange(x, y, z, oldState, state);
        }
        
        return oldState;
    }

    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    public FluidState getFluidState(int x, int y, int z) {
        return getBlockState(x, y, z).getFluidState();
    }

    // Lighting methods
    public Stream<BlockPos> getLights() {
        return blockEntities.keySet().stream()
            .filter(pos -> getBlockState(pos).getLightEmission() > 0);
    }

    // Block entity management
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    public void addBlockEntity(BlockEntity blockEntity) {
        blockEntities.put(blockEntity.getBlockPos(), blockEntity);
        isDirty = true;
    }

    public void removeBlockEntity(BlockPos pos) {
        blockEntities.remove(pos);
        isDirty = true;
    }

    public Map<BlockPos, BlockEntity> getBlockEntities() {
        return Collections.unmodifiableMap(blockEntities);
    }

    public void setBlockEntity(BlockEntity blockEntity) {
        addBlockEntity(blockEntity);
    }

    // Biome methods
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        // Convert to biome coordinates (divide by 4)
        int biomeX = (x & 15) >> 2;
        int biomeY = (y & 15) >> 2;
        int biomeZ = (z & 15) >> 2;
        return biomeContainer.get(biomeX, biomeY, biomeZ);
    }

    public void fillBiomesFromNoise(BiomeResolver biomeResolver, net.minecraft.world.level.biome.Climate.Sampler sampler) {
        // Implement 3D biome filling
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    int worldX = (cubeX << 4) + (x << 2) + 2;
                    int worldY = (cubeY << 4) + (y << 2) + 2;
                    int worldZ = (cubeZ << 4) + (z << 2) + 2;
                    
                    Holder<Biome> biome = biomeResolver.getNoiseBiome(worldX >> 2, worldY >> 2, worldZ >> 2, sampler);
                    biomeContainer.set(x, y, z, biome);
                }
            }
        }
        isDirty = true;
    }

    // Tick scheduling
    public void scheduleBlockTick(BlockPos pos, Block block, long triggerTick) {
        scheduledTicks.put(pos, triggerTick);
    }

    public boolean hasScheduledTick(BlockPos pos, Block block) {
        return scheduledTicks.containsKey(pos);
    }

    public int getScheduledTickCount() {
        return scheduledTicks.size();
    }

    // Utility methods
    public boolean isYSpaceEmpty(int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    if (y >= 0 && y < SIZE && !getBlockState(x, y, z).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // Entity management methods  
    public void addEntity(Entity entity) {
        if (!entities.contains(entity)) {
            entities.add(entity);
            isDirty = true;
        }
    }

    public void removeEntity(Entity entity) {
        if (entities.remove(entity)) {
            isDirty = true;
        }
    }

    public List<Entity> getEntities() {
        return new ArrayList<>(entities);
    }

    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, Predicate<? super T> predicate) {
        List<T> list = new ArrayList<>();
        for (Entity entity : entities) {
            T t = entityTypeTest.tryCast(entity);
            if (t != null && predicate.test(t)) {
                list.add(t);
            }
        }
        return list;
    }

    public boolean isEntityInCube(Entity entity) {
        BlockPos pos = entity.blockPosition();
        int worldX = pos.getX();
        int worldY = pos.getY();
        int worldZ = pos.getZ();
        
        // Convert world coordinates to cube coordinates
        int entityCubeX = Math.floorDiv(worldX, SIZE);
        int entityCubeY = Math.floorDiv(worldY, SIZE);
        int entityCubeZ = Math.floorDiv(worldZ, SIZE);
        
        return entityCubeX == cubeX && entityCubeY == cubeY && entityCubeZ == cubeZ;
    }

    // Cube-specific methods
    public byte getSkyLight(int x, int y, int z) {
        checkBounds(x, y, z);
        return skyLight[index(x, y, z)];
    }

    public void setSkyLight(int x, int y, int z, byte light) {
        checkBounds(x, y, z);
        skyLight[index(x, y, z)] = (byte) Math.max(0, Math.min(15, light));
    }

    public byte getBlockLight(int x, int y, int z) {
        checkBounds(x, y, z);
        return blockLight[index(x, y, z)];
    }

    public void setBlockLight(int x, int y, int z, byte light) {
        checkBounds(x, y, z);
        blockLight[index(x, y, z)] = (byte) Math.max(0, Math.min(15, light));
    }

    // Getters and setters
    public ChunkPos getPos() { return chunkPos; }
    public int getCubeX() { return cubeX; }
    public int getCubeY() { return cubeY; }
    public int getCubeZ() { return cubeZ; }
    
    public boolean isDirty() { return isDirty; }
    public void setDirty(boolean dirty) { this.isDirty = dirty; }
    
    public boolean isGenerated() { return isGenerated; }
    public void setGenerated(boolean generated) { this.isGenerated = generated; }
    
    public long getInhabitedTime() { return inhabitedTime; }
    public void setInhabitedTime(long time) { this.inhabitedTime = time; }

    private void handleBlockEntityChange(int x, int y, int z, BlockState oldState, BlockState newState) {
        BlockPos pos = new BlockPos((cubeX * SIZE) + x, (cubeY * SIZE) + y, (cubeZ * SIZE) + z);
        
        // Remove old block entity if block changed
        if (oldState.hasBlockEntity() && !newState.is(oldState.getBlock())) {
            removeBlockEntity(pos);
        }
        
        // Add new block entity if needed
        if (newState.hasBlockEntity()) {
            try {
                BlockEntity blockEntity = ((net.minecraft.world.level.block.EntityBlock) newState.getBlock()).newBlockEntity(pos, newState);
                if (blockEntity != null) {
                    addBlockEntity(blockEntity);
                }
            } catch (Exception e) {
                // Ignore block entity creation errors
            }
        }
    }

    private int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    private void checkBounds(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
            throw new IndexOutOfBoundsException("CubeChunk coordinates out of bounds: " + x + ", " + y + ", " + z);
        }
    }

    private void updateLightingForBlock(int x, int y, int z, BlockState state) {
        // Update block light if this block emits light
        int lightLevel = state.getLightEmission();
        setBlockLight(x, y, z, (byte) lightLevel);
        
        // TODO: Propagate light changes to neighboring blocks/cubes
        // This is a simplified implementation - full lighting would require
        // a proper light propagation algorithm across cube boundaries
    }

    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cubeX", cubeX);
        tag.putInt("cubeY", cubeY);
        tag.putInt("cubeZ", cubeZ);
        tag.putBoolean("isGenerated", isGenerated);
        tag.putLong("lastTickTime", lastTickTime);
        tag.putLong("inhabitedTime", inhabitedTime);
        
        // Serialize block data efficiently
        // TODO: Implement proper block serialization using paletted container format
        
        // Serialize lighting data
        tag.putByteArray("skyLight", skyLight);
        tag.putByteArray("blockLight", blockLight);
        
        // Serialize entities
        ListTag entitiesTag = new ListTag();
        for (Entity entity : entities) {
            CompoundTag entityTag = new CompoundTag();
            if (entity.save(entityTag)) {
                entitiesTag.add(entityTag);
            }
        }
        tag.put("entities", entitiesTag);
        
        return tag;
    }

    public static CubeChunk fromNBT(CompoundTag tag, Registry<Biome> biomeRegistry) {
        int cubeX = tag.getInt("cubeX");
        int cubeY = tag.getInt("cubeY");
        int cubeZ = tag.getInt("cubeZ");
        CubeChunk chunk = new CubeChunk(cubeX, cubeY, cubeZ, biomeRegistry);
        
        chunk.isGenerated = tag.getBoolean("isGenerated");
        chunk.lastTickTime = tag.getLong("lastTickTime");
        chunk.inhabitedTime = tag.getLong("inhabitedTime");
        
        // Load lighting data
        if (tag.contains("skyLight")) {
            byte[] skyLightData = tag.getByteArray("skyLight");
            System.arraycopy(skyLightData, 0, chunk.skyLight, 0, Math.min(skyLightData.length, chunk.skyLight.length));
        }
        
        if (tag.contains("blockLight")) {
            byte[] blockLightData = tag.getByteArray("blockLight");
            System.arraycopy(blockLightData, 0, chunk.blockLight, 0, Math.min(blockLightData.length, chunk.blockLight.length));
        }
        
        return chunk;
    }

    public void tick(Level level) {
        long currentTime = level.getGameTime();
        
        // Only tick every few ticks to reduce performance impact
        if (currentTime - lastTickTime < 20) { // Tick every second
            return;
        }
        
        lastTickTime = currentTime;
        
        // Tick random blocks for growth, decay, etc.
        if (level instanceof ServerLevel serverLevel) {
            RandomSource random = serverLevel.random;
            
            // Randomly tick a few blocks in this cube
            for (int i = 0; i < 3; i++) {
                int x = random.nextInt(SIZE);
                int y = random.nextInt(SIZE);
                int z = random.nextInt(SIZE);
                
                BlockState state = getBlockState(x, y, z);
                
                if (state.isRandomlyTicking()) {
                    int worldX = cubeX * SIZE + x;
                    int worldY = cubeY * SIZE + y;
                    int worldZ = cubeZ * SIZE + z;
                    BlockPos pos = new BlockPos(worldX, worldY, worldZ);
                    
                    try {
                        state.randomTick(serverLevel, pos, random);
                    } catch (Exception e) {
                        // Ignore ticking errors to prevent crashes
                    }
                }
            }
        }
        
        // Tick entities in this cube
        for (Entity entity : new ArrayList<>(entities)) {
            if (entity.isRemoved()) {
                entities.remove(entity);
                isDirty = true;
            }
        }
    }
    
    /**
     * Calculate the combined light level at a position (max of sky and block light)
     */
    public int getLightLevel(int x, int y, int z) {
        return Math.max(getSkyLight(x, y, z), getBlockLight(x, y, z));
    }
    
    /**
     * Check if this cube is empty (all air blocks)
     */
    public boolean isEmpty() {
        // Check if all blocks are air
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    if (!getBlockState(x, y, z).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
    
    /**
     * Get world coordinates for a local position in this cube
     */
    public BlockPos getWorldPos(int localX, int localY, int localZ) {
        return new BlockPos(
            cubeX * SIZE + localX,
            cubeY * SIZE + localY,
            cubeZ * SIZE + localZ
        );
    }
} 