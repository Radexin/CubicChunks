# Entity Integration in Cubic Chunks System

This document explains how entities are properly saved, loaded, and tracked within the cubic chunks system.

## Overview

Each 16x16x16 cubic chunk maintains its own list of entities, ensuring that entities are properly saved and loaded with their respective cubes. This provides several advantages:

1. **Granular Loading**: Only entities in loaded cubes are active
2. **Efficient Memory Usage**: Entities in unloaded cubes don't consume memory
3. **Precise Collision Detection**: Entity queries can be limited to specific cubes
4. **Vertical Chunking Support**: Entities at different Y levels can be managed independently

## Architecture

### Entity Storage per Cube

Each `CubeChunk` maintains:
- A list of entities within its 16x16x16 boundaries
- Methods to add/remove entities as they move between cubes
- NBT serialization that includes entity data

### Entity Manager

The `CubicEntityManager` handles:
- Loading entities from NBT during chunk loading
- Tracking entity movement between cubes
- Managing entity lifecycle (spawn/despawn)
- Updating entity positions across cube boundaries

### Integration with Storage

Entities are automatically saved as part of the cube's NBT data:
- Each cube's `toNBT()` method includes entity serialization
- Entity loading is handled during world loading process
- Entity data persists across world saves/loads

## Entity Lifecycle

### 1. Entity Creation
```java
// Entities are automatically tracked when spawned
Entity mob = new Zombie(EntityType.ZOMBIE, level);
mob.moveTo(x, y, z);
level.addFreshEntity(mob);
// CubicEntityManager automatically assigns it to correct cube
```

### 2. Entity Movement
```java
// When entities move, they're automatically reassigned to new cubes
cubicChunkProvider.updateEntityPositions();
// Called periodically to update entity-to-cube mappings
```

### 3. Entity Persistence
```java
// Entities are saved with their cubes
cubicChunkProvider.saveAll();
// Entity data is included in cube NBT automatically
```

### 4. Entity Loading
```java
// Entities are loaded when cubes are loaded from storage
CubeChunk cube = provider.getCube(x, y, z, true);
// Entity data is restored from NBT automatically
```

## Key Features

### Automatic Tracking
- Entities are automatically assigned to cubes based on their position
- Movement between cubes is handled transparently
- No manual intervention required for most use cases

### Efficient Queries
```java
// Get all entities in a specific cube
List<Entity> entities = cube.getEntities();

// Get specific entity types with predicates
List<Mob> mobs = cube.getEntities(EntityType.MOB, mob -> mob.isAlive());
```

### Cross-Cube Operations
```java
// Find which cube contains an entity
CubeChunk entityCube = provider.getChunkForEntity(entity);

// Track new entities
provider.trackEntity(newEntity);

// Untrack removed entities
provider.untrackEntity(removedEntity);
```

## NBT Structure

Entity data is stored within each cube's NBT structure:

```nbt
{
  "cubeX": 0,
  "cubeY": 5,
  "cubeZ": 0,
  "blocks": [...],
  "entities": [
    {
      "id": "minecraft:zombie",
      "Pos": [8.5, 85.0, 8.5],
      "Health": 20.0f,
      // ... other entity data
    },
    {
      "id": "minecraft:cow",
      "Pos": [12.0, 82.0, 15.0],
      "Health": 10.0f,
      // ... other entity data
    }
  ]
}
```

## Performance Considerations

### Memory Efficiency
- Only entities in loaded cubes consume memory
- Unloaded cubes store entity data on disk only
- Entity tracking maps are cleaned up automatically

### Update Frequency
- Entity position updates should be called regularly but not every tick
- Recommended: Update positions every 5-10 ticks
- Balance between accuracy and performance

### Large Entity Counts
- Each cube maintains its own entity list for O(1) access
- Spatial queries are limited to specific cubes
- Scales well with world size and entity density

## Integration with Vanilla Systems

### Compatibility
- Entities maintain all vanilla behavior and properties
- Save format is compatible with standard NBT structure
- Works with modded entities that follow vanilla patterns

### Tick Scheduling
- Entities are ticked as part of their cube's tick cycle
- Only entities in loaded cubes are actively ticked
- Maintains vanilla entity simulation rules

### Event Handling
- Entity spawn/despawn events work normally
- Movement events can trigger cube reassignment
- Compatible with entity AI and pathfinding

## Usage Examples

### Basic Setup
```java
File worldDir = new File("world");
Level level = getServerLevel();
CubicChunkProvider provider = new CubicChunkProvider(worldDir, level);

// Entities are automatically managed from this point
```

### Manual Entity Management
```java
// Force update entity positions (optional)
provider.updateEntityPositions();

// Get entities in a specific area
CubeChunk cube = provider.getCube(0, 5, 0, false);
if (cube != null) {
    List<Entity> entities = cube.getEntities();
    // Process entities...
}
```

### Custom Entity Handling
```java
// Track custom entities
Entity customEntity = new MyCustomEntity(level);
provider.trackEntity(customEntity);

// Get cube for specific entity
CubeChunk entityCube = provider.getChunkForEntity(customEntity);
```

This entity integration system ensures that the cubic chunks mod maintains full compatibility with vanilla Minecraft's entity system while providing the benefits of 3D chunk management. 