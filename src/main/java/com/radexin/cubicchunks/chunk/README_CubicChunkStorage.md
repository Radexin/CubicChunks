# Cubic Chunk Storage System

This document explains the cubic chunk storage system implemented for the CubicChunks mod.

## Overview

The storage system extends vanilla Minecraft's approach to handle 3D cubic chunks (16x16x16 blocks) instead of 2D columnar chunks (16x256x16 blocks). Similar to vanilla's `.mca` region files, this system uses `.ccr` (Cubic Chunk Region) files to store chunks.

## Architecture

### 1. CubeChunk (16x16x16 block storage)
- Stores 4096 blocks in a single cube
- Serializes to/from NBT format
- Includes tick() method for future block/entity updates

### 2. CubeColumn (vertical stack of cubes)
- Manages multiple CubeChunks at the same X/Z coordinates
- Handles loading/unloading of individual cubes by Y coordinate
- Serializes all loaded cubes to NBT

### 3. CubicRegionFile (32x32x32 cubic chunks per file)
- Similar to vanilla's RegionFile but supports 3D coordinates
- File format: `r.{regionX}.{regionY}.{regionZ}.ccr`
- Stores multiple columns within a single region file
- Uses NBT for data serialization

### 4. CubicChunkStorage (manages multiple region files)
- High-level interface for save/load operations
- Automatically routes cubes to appropriate region files
- Handles region file creation and management

### 5. CubicChunkProvider (main interface)
- Integrates with CubicChunkMap for in-memory management
- Automatically loads cubes from storage when requested
- Saves cubes before unloading
- Provides saveAll() and close() methods

## File Structure

```
world/
├── region/
│   ├── r.0.0.0.ccr     (region file for cubes 0-31 in all dimensions)
│   ├── r.0.0.1.ccr     (region file for cubes 0-31 in X/Z, 32-63 in Y)
│   ├── r.1.0.0.ccr     (region file for cubes 32-63 in X, 0-31 in Y/Z)
│   └── ...
└── other vanilla files...
```

## Key Features

1. **3D Coordinates**: All systems support (X, Y, Z) addressing
2. **Lazy Loading**: Cubes are loaded from storage only when needed
3. **Automatic Saving**: Cubes are saved before unloading
4. **Region-based Storage**: Groups cubes into manageable region files
5. **NBT Serialization**: Uses standard Minecraft NBT format

## Usage Example

```java
File worldDir = new File("world");
CubicChunkProvider provider = new CubicChunkProvider(worldDir);

// Get or create a cube at (0, 5, 0)
CubeChunk cube = provider.getCube(0, 5, 0, true);

// Modify the cube
cube.setBlockState(8, 8, 8, Blocks.STONE.defaultBlockState());

// Save all changes
provider.saveAll();

// Clean shutdown
provider.close();
```

## Differences from Vanilla

1. **3D Region Coordinates**: Region files are identified by (X, Y, Z) instead of just (X, Z)
2. **Smaller Chunks**: 16x16x16 cubes instead of 16x256x16 columns
3. **Vertical Chunking**: Y-axis is divided into discrete chunks
4. **Extended File Format**: `.ccr` files support 3D chunk storage

This system provides the foundation for infinite vertical worlds while maintaining compatibility with Minecraft's existing save/load patterns. 