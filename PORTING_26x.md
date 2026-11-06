# Porting notes: Minecraft 26.x

This mod originally targeted Minecraft 1.21.11 (Fabric). This PR ports it to Minecraft 26.2 / 26.3 (the 2026 version scheme, where the game ships unobfuscated).

## Toolchain changes

| | Before (1.21.11) | After (26.2 / 26.3) |
|------|-----------------|----------------------|
| Minecraft | 1.21.11 | 26.3 (primary), 26.2 (one-line config flip) |
| Fabric Loader | 0.18.4 | 0.19.5 |
| Fabric API | 0.141.3+1.21.11 | 0.161.0+26.3 (also published as 0.161.0+26.2) |
| Loom plugin | `net.fabricmc.fabric-loom-remap` 1.15-SNAPSHOT | `net.fabricmc.fabric-loom` 1.18.2 |
| Gradle | 9.3.0 | 9.7.1 |
| Java | 21 | 25 (26.x servers require Java 25) |
| `mappings` | `loom.officialMojangMappings()` | removed (game is unobfuscated; no remapping needed) |
| dependencies | `modImplementation` | `implementation` (same reason) |
| mixin `compatibilityLevel` | JAVA_21 | JAVA_25 |

## Source changes

1. **`ChunkPos.asLong(int,int)` → `ChunkPos.pack(int,int)`** (renamed in 26.2; affects `MirrorApplyTask`).
2. **`RegionFileStorage.regionCache` element type changed**:
   - 26.2: `Long2ObjectLinkedOpenHashMap<RegionFile>`
   - 26.3: `Long2ObjectLinkedOpenHashMap<Optional<RegionFile>>`
   - Both erase to the same descriptor, so this compiles identically against either version but differs at runtime. `ChunkUnloader.clearRegionCache` now inspects each element (`instanceof`) and handles both shapes, so one source tree serves both versions.
3. All other mixin targets (`ServerLevel.noSave`, `ServerChunkCache.chunkMap`, `ChunkMap.getVisibleChunkIfPresent`/`saveAllChunks`, `SimpleRegionStorage.worker`, `IOWorker.storage`) exist unchanged in both 26.2 and 26.3 — verified with `javap` against the mapped jars and confirmed by booting the mod on real 26.2 and 26.3 dedicated servers (mixins applied, sync server started, `/mirage status` worked, clean shutdown).

## Building

`./gradlew build` as configured targets **26.3** and produces `build/libs/mirage-1.3.0+mc26.3.jar`.

For a **26.2** build, flip two places and build:

- `gradle.properties`: `minecraft_version=26.2`, `fabric_api_version=0.161.0+26.2` (and set `mod_version=1.3.0+mc26.2` if you want it in the filename)
- `src/main/resources/fabric.mod.json`: `"minecraft": "~26.2"`

A local JDK 25 is required to compile; servers need Java 25 at runtime.
