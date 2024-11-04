package net.roguelogix.phosphophyllite.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.joml.Vector3ic;

import java.text.NumberFormat;
import java.util.Locale;

@NonnullDefault
public class VectorUtil {
    
    public static boolean less(Vector3ic left, Vector3ic right) {
        return left.x() < right.x() && left.y() < right.y() && left.z() < right.z();
    }
    
    public static boolean less(Vector3ic left, BlockPos right) {
        return left.x() < right.getX() && left.y() < right.getY() && left.z() < right.getZ();
    }
    
    public static boolean less(BlockPos left, Vector3ic right) {
        return greater(right, left);
    }
    
    public static boolean greater(Vector3ic left, Vector3ic right) {
        return left.x() > right.x() && left.y() > right.y() && left.z() > right.z();
    }
    
    public static boolean greater(Vector3ic left, BlockPos right) {
        return left.x() > right.getX() && left.y() > right.getY() && left.z() > right.getZ();
    }
    
    public static boolean greater(BlockPos left, Vector3ic right) {
        return less(right, left);
    }
    
    public static boolean lequal(Vector3ic left, Vector3ic right) {
        return left.x() <= right.x() && left.y() <= right.y() && left.z() <= right.z();
    }
    
    public static boolean lequal(Vector3ic left, BlockPos right) {
        return left.x() <= right.getX() && left.y() <= right.getY() && left.z() <= right.getZ();
    }
    
    public static boolean grequal(Vector3ic left, Vector3ic right) {
        return left.x() >= right.x() && left.y() >= right.y() && left.z() >= right.z();
    }
    
    public static boolean grequal(Vector3ic left, BlockPos right) {
        return left.x() >= right.getX() && left.y() >= right.getY() && left.z() >= right.getZ();
    }
    
    public static Vector3i fromBlockPos(BlockPos blockPos) {
        return fromBlockPos(blockPos, new Vector3i());
    }
    
    public static Vector3i fromBlockPos(BlockPos blockPos, Vector3i vector) {
        return vector.set(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }
    
    public static Vector3i fromBlockPos(long blockPos) {
        return fromBlockPos(blockPos, new Vector3i());
    }
    
    public static Vector3i fromBlockPos(long blockPos, Vector3i vector) {
        return vector.set(BlockPos.getX(blockPos), BlockPos.getY(blockPos), BlockPos.getZ(blockPos));
    }
    
    public static long toBlockPosLong(Vector3ic vector) {
        return BlockPos.asLong(vector.x(), vector.y(), vector.z());
    }
    
    public static Vector2i fromChunkPos(ChunkPos chunkPos) {
        return fromChunkPos(chunkPos, new Vector2i());
    }
    
    public static Vector2i fromChunkPos(ChunkPos chunkPos, Vector2i vector) {
        return vector.set(chunkPos.x, chunkPos.z);
    }
    
    public static Vector2i fromChunkPos(long chunkPos) {
        return fromChunkPos(chunkPos, new Vector2i());
    }
    
    public static Vector2i fromChunkPos(long chunkPos, Vector2i vector) {
        return vector.set(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos));
    }
    
    public static Vector3i fromSectionPos(SectionPos sectionPos) {
        return fromSectionPos(sectionPos, new Vector3i());
    }
    
    public static Vector3i fromSectionPos(SectionPos sectionPos, Vector3i vector) {
        return vector.set(sectionPos.getX(), sectionPos.getY(), sectionPos.getZ());
    }
    
    public static Vector3i fromSectionPos(long sectionPos) {
        return fromSectionPos(sectionPos, new Vector3i());
    }
    
    public static Vector3i fromSectionPos(long sectionPos, Vector3i vector) {
        return vector.set(SectionPos.x(sectionPos), SectionPos.y(sectionPos), SectionPos.z(sectionPos));
    }
    
    public static long blockPosToChunkPosLong(Vector3ic blockpos) {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(blockpos.x()), SectionPos.blockToSectionCoord(blockpos.z()));
    }
    
    public static String asString(Vector3ic vector) {
        final var formatter = NumberFormat.getNumberInstance(Locale.ENGLISH);
        return "(" + formatter.format(vector.x()) + " " + formatter.format(vector.y()) + " " + formatter.format(vector.z()) + ")";
    }
}
