package net.roguelogix.phosphophyllite.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.modlauncher.api.LambdaExceptionUtils;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Contract;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;


public class Util {
    
    public static final Direction[] DIRECTIONS = Direction.values();
    
    private static final Level[] lastLevel = new Level[2];
    private static final ChunkAccess[] lastChunk = new ChunkAccess[2];
    private static final long[] lastChunkPos = new long[2];
    
    public static void setBlockState(Level level, BlockPos pos, BlockState state) {
        var arrayIndex = level.isClientSide ? 1 : 0;
        final var chunkPos = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (lastLevel[arrayIndex] != level || chunkPos != lastChunkPos[arrayIndex]) {
            lastLevel[arrayIndex] = level;
            lastChunkPos[arrayIndex] = chunkPos;
            lastChunk[arrayIndex] = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        }
        lastChunk[arrayIndex].setBlockState(pos, state, false);
    }
    
    @Contract(pure = true)
    public static BlockEntity getTile(Level level, BlockPos pos) {
        var arrayIndex = level.isClientSide ? 1 : 0;
        final var chunkPos = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (lastLevel[arrayIndex] != level || chunkPos != lastChunkPos[arrayIndex] || lastChunk[arrayIndex] == null) {
            lastLevel[arrayIndex] = level;
            lastChunkPos[arrayIndex] = chunkPos;
            lastChunk[arrayIndex] = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        }
        if (lastChunk[arrayIndex] == null) {
            return null;
        }
        return lastChunk[arrayIndex].getBlockEntity(pos);
    }
    
    public static String readResourceLocation(ResourceLocation location) {
        try (BufferedReader reader = Minecraft.getInstance().getResourceManager().getResource(location).orElseThrow().openAsReader()) {
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
                stringBuilder.append("\n");
            }
            return stringBuilder.toString();
        } catch (IOException ignored) {
        }
        return null;
    }
    
    public static JsonObject readJSONFile(ResourceLocation location) {
        if (location.getPath().lastIndexOf(".json") != location.getPath().length() - 5) {
            location = ResourceLocation.fromNamespaceAndPath(location.getNamespace(), location.getPath() + ".json");
        }
        String jsonString = readResourceLocation(location);
        if (jsonString == null) {
            return null;
        }
        JsonElement element = new JsonParser().parse(jsonString);
        if (element instanceof JsonObject) {
            return (JsonObject) element;
        }
        return null;
    }
    
    public static <T extends Exception> void chunkCachedBlockStateIteration(Vector3ic start, Vector3ic end, Level world, LambdaExceptionUtils.BiConsumer_WithExceptions<BlockState, Vector3i, T> func) throws T {
        chunkCachedBlockStateIteration(start, end, world, func, new Vector3i());
    }
    
    public static <T extends Exception> void chunkCachedBlockStateIteration(Vector3ic start, Vector3ic end, Level world, LambdaExceptionUtils.BiConsumer_WithExceptions<BlockState, Vector3i, T> func, Vector3i scratchVector) throws T {
        final int minx = start.x();
        final int miny = start.y();
        final int minz = start.z();
        final int maxx = end.x();
        final int maxy = end.y();
        final int maxz = end.z();
        final int maxX = (maxx + 16) & 0xFFFFFFF0;
        final int maxY = (maxy + 16) & 0xFFFFFFF0;
        final int maxZ = (maxz + 16) & 0xFFFFFFF0;
        final var AIR_STATE = Blocks.AIR.defaultBlockState();
        // ChunkSource implementations are indexed [z][x]
        for (int Z = minz; Z < maxZ; Z += 16) {
            final int sectionMinZ = Math.max((Z) & 0xFFFFFFF0, minz);
            final int sectionMaxZ = Math.min((Z + 16) & 0xFFFFFFF0, maxz + 1);
            for (int X = minx; X < maxX; X += 16) {
                int chunkX = X >> 4;
                int chunkZ = Z >> 4;
                final int sectionMinX = Math.max((X) & 0xFFFFFFF0, minx);
                final int sectionMaxX = Math.min((X + 16) & 0xFFFFFFF0, maxx + 1);
                LevelChunk chunk = (LevelChunk) world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    for (int x = sectionMinX; x < sectionMaxX; x++) {
                        for (int y = miny; y < maxy; y++) {
                            for (int z = sectionMinZ; z < sectionMaxZ; z++) {
                                scratchVector.set(x, y, z);
                                func.accept(AIR_STATE, scratchVector);
                            }
                        }
                    }
                    continue;
                }
                LevelChunkSection[] chunkSections = chunk.getSections();
                int chunkMinSection = chunk.getMinSection();
                for (int Y = miny; Y < maxY; Y += 16) {
                    int sectionMinY = Math.max((Y) & 0xFFFFFFF0, miny);
                    int sectionMaxY = Math.min((Y + 16) & 0xFFFFFFF0, maxy + 1);
                    
                    int chunkSectionIndex = (Y >> 4) - chunkMinSection;
                    LevelChunkSection chunkSection = chunkSections[chunkSectionIndex];
                    
                    if (chunkSection == null) {
                        for (int x = sectionMinX; x < sectionMaxX; x++) {
                            for (int y = sectionMinY; y < sectionMaxY; y++) {
                                for (int z = sectionMinZ; z < sectionMaxZ; z++) {
                                    scratchVector.set(x, y, z);
                                    func.accept(AIR_STATE, scratchVector);
                                }
                            }
                        }
                        continue;
                    }
                    
                    // PalettedContainers are indexed [y][z][x]
                    for (int y = sectionMinY; y < sectionMaxY; y++) {
                        for (int z = sectionMinZ; z < sectionMaxZ; z++) {
                            for (int x = sectionMinX; x < sectionMaxX; x++) {
                                scratchVector.set(x, y, z);
                                BlockState state = chunkSection.getBlockState(x & 15, y & 15, z & 15);
                                func.accept(state, scratchVector);
                            }
                        }
                    }
                }
            }
        }
    }
    
    public static void markRangeDirty(Level world, Vector2i start, Vector2i end) {
        for (int X = start.x; X < ((end.x + 16) & 0xFFFFFFF0); X += 16) {
            for (int Z = start.y; Z < ((end.y + 16) & 0xFFFFFFF0); Z += 16) {
                int chunkX = X >> 4;
                int chunkZ = Z >> 4;
                LevelChunk chunk = world.getChunk(chunkX, chunkZ);
                chunk.setUnsaved(true);
            }
        }
    }
    
    public static void markRangeDirty(Level world, Vector3ic start, Vector3ic end) {
        for (int X = start.x(); X < ((end.x() + 16) & 0xFFFFFFF0); X += 16) {
            for (int Z = start.z(); Z < ((end.z() + 16) & 0xFFFFFFF0); Z += 16) {
                int chunkX = X >> 4;
                int chunkZ = Z >> 4;
                LevelChunk chunk = world.getChunk(chunkX, chunkZ);
                chunk.setUnsaved(true);
            }
        }
    }
    
    private static final Long2ObjectOpenHashMap<BlockState> endOfTickStates = new Long2ObjectOpenHashMap<>(4096, Hash.DEFAULT_LOAD_FACTOR);
    
    public static void setBlockStateWithoutUpdate(BlockPos pos, BlockState state) {
        setBlockStateWithoutUpdate(pos.asLong(), state);
    }
    
    public static void setBlockStateWithoutUpdate(long pos, BlockState state) {
        endOfTickStates.put(pos, state);
    }
    
    public static void setBlockStateWithoutUpdate(Long2ObjectOpenHashMap<BlockState> map) {
        endOfTickStates.putAll(map);
    }
    
    public static void setBlockStates(Map<BlockPos, BlockState> newStates, Level world) {
        HashMap<BlockPos, HashMap<BlockPos, BlockState>> stateChunks = new HashMap<>();
        BlockPos.MutableBlockPos chunkPos = new BlockPos.MutableBlockPos();
        newStates.forEach((pos, state) -> {
            chunkPos.set(pos.getX() >> 4, 0, pos.getZ() >> 4);
            HashMap<BlockPos, BlockState> chunksNewStates = stateChunks.get(chunkPos);
            if (chunksNewStates == null) {
                chunksNewStates = new LinkedHashMap<>(8192);
                stateChunks.put(chunkPos.immutable(), chunksNewStates);
            }
            chunksNewStates.put(pos, state);
        });
        stateChunks.forEach((cPos, states) -> {
            LevelChunk chunk = world.getChunk(cPos.getX(), cPos.getZ());
            LevelChunkSection[] chunkSections = chunk.getSections();
            states.forEach((bPos, state) -> {
                LevelChunkSection section = chunkSections[(bPos.getY() >> 4) - chunk.getMinSection()];
                if (section != null) {
                    section.setBlockState(bPos.getX() & 15, bPos.getY() & 15, bPos.getZ() & 15, state);
                    markForUpdatePacket(bPos);
                }
            });
            chunk.setUnsaved(true);
        });
    }
    
    private static final BlockPos.MutableBlockPos chunkPos = new BlockPos.MutableBlockPos();
    
    public static void setBlockStatesAndUpdateLight(Long2ObjectMap<BlockState> newStates, Level world) {
        stateChunks.clear();
        ((Long2ObjectMap.FastEntrySet<BlockState>) newStates.long2ObjectEntrySet()).fastIterator().forEachRemaining((entry) -> {
            final var posLong = entry.getLongKey();
            final var state = entry.getValue();
            chunkPos.set(BlockPos.getX(posLong) >> 4, 0, BlockPos.getZ(posLong) >> 4);
            Long2ObjectLinkedOpenHashMap<BlockState> chunksNewStates = stateChunks.get(chunkPos.asLong());
            if (chunksNewStates == null) {
                if (existingMaps.isEmpty()) {
                    chunksNewStates = new Long2ObjectLinkedOpenHashMap<>();
                } else {
                    chunksNewStates = existingMaps.pop();
                    chunksNewStates.clear();
                }
                stateChunks.put(chunkPos.asLong(), chunksNewStates);
            }
            chunksNewStates.put(posLong, state);
        });
        ((Long2ObjectMap.FastEntrySet<Long2ObjectLinkedOpenHashMap<BlockState>>) stateChunks.long2ObjectEntrySet()).fastIterator().forEachRemaining((entry) -> {
            final var cPosLong = entry.getLongKey();
            final var states = entry.getValue();
            ServerChunkCache chunkSource = ((ServerChunkCache) world.getChunkSource());
            LevelChunk chunk = (LevelChunk) chunkSource.getChunk(BlockPos.getX(cPosLong), BlockPos.getZ(cPosLong), ChunkStatus.FULL, true);
            assert chunk != null;
            LevelChunkSection[] chunkSections = chunk.getSections();
            ((Long2ObjectMap.FastEntrySet<BlockState>) states.long2ObjectEntrySet()).fastIterator().forEachRemaining((entry1) -> {
                final var bPosLong = entry1.getLongKey();
                final var state = entry1.getValue();
                final var sectionIndex = (BlockPos.getY(bPosLong) >> 4) - chunk.getMinSection();
                LevelChunkSection section = chunkSections[sectionIndex];
                if (section != null) {
                    section.getStates().set(BlockPos.getX(bPosLong) & 15, BlockPos.getY(bPosLong) & 15, BlockPos.getZ(bPosLong) & 15, state);
                    world.getLightEngine().checkBlock(chunkPos.set(bPosLong));
                    chunkSource.blockChanged(chunkPos.set(bPosLong));
                }
            });
            existingMaps.add(states);
            chunk.setUnsaved(true);
        });
    }
    
    private static final ObjectArrayList<Long2ObjectLinkedOpenHashMap<BlockState>> existingMaps = new ObjectArrayList<>();
    private static final Long2ObjectLinkedOpenHashMap<Long2ObjectLinkedOpenHashMap<BlockState>> stateChunks = new Long2ObjectLinkedOpenHashMap<>();
    
    public static void setBlockStates(Long2ObjectMap<BlockState> newStates, Level world) {
        stateChunks.clear();
        ((Long2ObjectMap.FastEntrySet<BlockState>) newStates.long2ObjectEntrySet()).fastIterator().forEachRemaining((entry) -> {
            final var posLong = entry.getLongKey();
            final var state = entry.getValue();
            chunkPos.set(BlockPos.getX(posLong) >> 4, 0, BlockPos.getZ(posLong) >> 4);
            Long2ObjectLinkedOpenHashMap<BlockState> chunksNewStates = stateChunks.get(chunkPos.asLong());
            if (chunksNewStates == null) {
                if (existingMaps.isEmpty()) {
                    chunksNewStates = new Long2ObjectLinkedOpenHashMap<>();
                } else {
                    chunksNewStates = existingMaps.pop();
                    chunksNewStates.clear();
                }
                stateChunks.put(chunkPos.asLong(), chunksNewStates);
            }
            chunksNewStates.put(posLong, state);
        });
        ((Long2ObjectMap.FastEntrySet<Long2ObjectLinkedOpenHashMap<BlockState>>) stateChunks.long2ObjectEntrySet()).fastIterator().forEachRemaining((entry) -> {
            final var cPosLong = entry.getLongKey();
            final var states = entry.getValue();
            LevelChunk chunk = world.getChunk(BlockPos.getX(cPosLong), BlockPos.getZ(cPosLong));
            LevelChunkSection[] chunkSections = chunk.getSections();
            ((Long2ObjectMap.FastEntrySet<BlockState>) states.long2ObjectEntrySet()).fastIterator().forEachRemaining((entry1) -> {
                final var bPosLong = entry1.getLongKey();
                final var state = entry1.getValue();
                LevelChunkSection section = chunkSections[(BlockPos.getY(bPosLong) >> 4) - chunk.getMinSection()];
                if (section != null) {
                    final var oldState = section.getStates().getAndSet(BlockPos.getX(bPosLong) & 15, BlockPos.getY(bPosLong) & 15, BlockPos.getZ(bPosLong) & 15, state);
                    markForUpdatePacket(bPosLong);
                    // this does add some overhead, but in many cases both the new and old state are repeated  for a *lot* of blocks, so this shouldn't cause a bunch of cache misses
                    // the renderchunk rebuild on the client is also much more intensive than this, so *meh*
                    // TODO: add this check to the other setblockstate functions
                    if (oldState == state) {
                        return;
                    }
                    if (oldState.getBlock() != state.getBlock()) {
                        throw new IllegalStateException("Phosphophyllite Util fast setBlockStates does not handle changing block type");
                    }
                }
            });
            existingMaps.add(states);
            chunk.setUnsaved(true);
        });
    }
    
    private static final ObjectArrayList<boolean[]> existingArrays = new ObjectArrayList<>();
    private static final Long2ObjectLinkedOpenHashMap<boolean[]> updateArrays = new Long2ObjectLinkedOpenHashMap<>();
    
    private static void markForUpdatePacket(BlockPos pos) {
        var chunkPos = SectionPos.asLong(pos);
        var updateArray = updateArrays.get(chunkPos);
        if (updateArray == null) {
            if (existingArrays.isEmpty()) {
                updateArray = new boolean[4096];
            } else {
                updateArray = existingArrays.pop();
                Arrays.fill(updateArray, false);
            }
            updateArrays.put(chunkPos, updateArray);
        }
        var sectionPos = SectionPos.sectionRelativePos(pos);
        assert sectionPos >= 0 && sectionPos < 4096;
        updateArray[sectionPos] = true;
    }
    
    private static void markForUpdatePacket(long blockPosLong) {
        long sectionPosLong = SectionPos.blockToSection(blockPosLong);
        var updateArray = updateArrays.get(sectionPosLong);
        if (updateArray == null) {
            if (existingArrays.isEmpty()) {
                updateArray = new boolean[4096];
            } else {
                updateArray = existingArrays.pop();
                Arrays.fill(updateArray, false);
            }
            updateArrays.put(sectionPosLong, updateArray);
        }
        int i = SectionPos.sectionRelative(BlockPos.getX(blockPosLong));
        int j = SectionPos.sectionRelative(BlockPos.getY(blockPosLong));
        int k = SectionPos.sectionRelative(BlockPos.getZ(blockPosLong));
        var sectionPos = (short) (i << 8 | k << 4 | j);
        assert sectionPos >= 0 && sectionPos < 4096;
        updateArray[sectionPos] = true;
    }
    
    private static class SpecialShortArraySet extends ShortArraySet {
        private int size = 0;
        private final short[] elements = new short[4096];
        
        public SpecialShortArraySet() {
        }
        
        @Override
        public int size() {
            return size;
        }
        
        @Override
        public ShortIterator iterator() {
            return new ShortIterator() {
                int index = 0;
                
                @Override
                public boolean hasNext() {
                    return index < size;
                }
                
                @Override
                public short nextShort() {
                    return elements[index++];
                }
            };
        }
        
        public void clear() {
            size = 0;
        }
        
        public void fadd(short val) {
            elements[size++] = val;
        }
    }
    
    public static void updateBlockStates(Level level) {
        if (!endOfTickStates.isEmpty()) {
            setBlockStates(endOfTickStates, level);
            endOfTickStates.clear();
        }
    }
    
    public static void clientTick() {
        lastLevel[1] = null;
    }
    
    public static void serverTick() {
        lastLevel[0] = null;
    }
    
    public static void worldTickEndEvent(Level level) {
        if (!endOfTickStates.isEmpty()) {
            setBlockStates(endOfTickStates, level);
            endOfTickStates.clear();
        }
        if (updateArrays.isEmpty()) {
            return;
        }
        final SpecialShortArraySet shortSet = new SpecialShortArraySet();
        updateArrays.long2ObjectEntrySet().fastIterator().forEachRemaining(entry -> {
            long entryKey = entry.getLongKey();
            var entryArray = entry.getValue();
            var sectionPos = SectionPos.of(entryKey);
            var levelSection = level.getChunk(sectionPos.x(), sectionPos.z()).getSections()[sectionPos.y() - level.getMinSection()];
            if (levelSection != null) {
                shortSet.clear();
                for (int i = 0; i < 4096; i++) {
                    if (entryArray[i]) {
                        shortSet.fadd((short) i);
                    }
                }
                var packet = new ClientboundSectionBlocksUpdatePacket(sectionPos, shortSet, levelSection);
                ((ServerChunkCache) level.getChunkSource()).chunkMap.getPlayers(sectionPos.chunk(), false).forEach(serverPlayer -> {
                    serverPlayer.connection.send(packet);
                });
            }
            existingArrays.add(entryArray);
        });
        updateArrays.clear();
    }
    
    public static Direction directionFromPositions(BlockPos reference, BlockPos neighbor) {
        int xDifference = neighbor.getX() - reference.getX();
        int yDifference = neighbor.getY() - reference.getY();
        int zDifference = neighbor.getZ() - reference.getZ();
        if (Math.abs(xDifference) + Math.abs(yDifference) + Math.abs(zDifference) > 1) {
            throw new IllegalArgumentException("positions not neighbors");
        }
        if (xDifference == -1) {
            return Direction.WEST;
        } else if (xDifference == 1) {
            return Direction.EAST;
        } else if (yDifference == -1) {
            return Direction.DOWN;
        } else if (yDifference == 1) {
            return Direction.UP;
        } else if (zDifference == -1) {
            return Direction.NORTH;
        } else if (zDifference == 1) {
            return Direction.SOUTH;
        }
        throw new IllegalArgumentException("identical positions gives");
    }
    
    
    private static final TagKey<Item> WRENCH_TAG_0 = TagKey.create(Registries.ITEM, ResourceLocation.parse("forge:tools/wrench"));
    private static final TagKey<Item> WRENCH_TAG_1 = TagKey.create(Registries.ITEM, ResourceLocation.parse("forge:wrenches"));
    
    public static boolean isWrench(Item item) {
        return item.builtInRegistryHolder().is(WRENCH_TAG_0) || item.builtInRegistryHolder().is(WRENCH_TAG_1);
    }
    
    @Deprecated(forRemoval = true)
    public static boolean isEntireAreaLoaded(Level level, Vector3ic min, Vector3ic max) {
        return isEntireAreaLoaded(level, SectionPos.blockToSectionCoord(min.x()), SectionPos.blockToSectionCoord(min.z()), SectionPos.blockToSectionCoord(max.x()), SectionPos.blockToSectionCoord(max.z()));
    }
    
    @Deprecated(forRemoval = true)
    public static boolean isEntireAreaLoaded(Level level, int minCX, int minCZ, int maxCX, int maxCZ) {
        for (int i = minCX; i <= maxCX; i++) {
            for (int j = minCZ; j <= maxCZ; j++) {
                if (level.getChunk(i, j, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }
    
    
    public static int clampToInt(long val) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, val));
    }
    
    public static int roundUpPo2(int minSize) {
        int size = Integer.highestOneBit(minSize);
        if (size < minSize) {
            size <<= 1;
        }
        return size;
    }
    
    public static long roundUpPo2(long minSize) {
        long size = Long.highestOneBit(minSize);
        if (size < minSize) {
            size <<= 1;
        }
        return size;
    }
}
