package dev.logix.cinnabar.internal.memory;

import dev.logix.cinnabar.Cinnabar;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import net.roguelogix.phosphophyllite.threading.ThreadSafety;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.joml.*;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;
import sun.misc.Unsafe;

import javax.annotation.Nonnull;
import java.lang.Math;
import java.lang.Runtime;
import java.util.concurrent.locks.ReentrantReadWriteLock;


// TODO: maybe a specific debug flag for this? the checks here fairly specific (and expensive)
import static dev.logix.cinnabar.internal.CinnabarDebug.DEBUG;
import static dev.logix.cinnabar.internal.memory.LeakDetection.*;

@ThreadSafety.Many
@NonnullDefault
@SuppressWarnings({"DuplicatedCode", "UnusedReturnValue"})
public record PointerWrapper(long pointer, long size) implements Comparable<PointerWrapper> {
    
    private static final boolean JOML_UNSAFE_AVAILABLE;
    private static final Unsafe THE_UNSAFE;
    
    static {
        boolean available = false;
        try {
            final var memUtilClass = PointerWrapper.class.getClassLoader().loadClass("org.joml.MemUtil");
            final var memUtilUnsafeClass = PointerWrapper.class.getClassLoader().loadClass("org.joml.MemUtil$MemUtilUnsafe");
            final var instanceField = memUtilClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            final var memUtilInstance = instanceField.get(null);
            available = memUtilUnsafeClass.isInstance(memUtilInstance);
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            Cinnabar.LOGGER.info(e);
        }
        JOML_UNSAFE_AVAILABLE = available;
        
        try {
            final var memUtilUnsafeField = MemoryUtil.class.getDeclaredField("UNSAFE");
            memUtilUnsafeField.setAccessible(true);
            THE_UNSAFE = (Unsafe) memUtilUnsafeField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
    
    private static final long BYTE_ARRAY_BASE_OFFSET = THE_UNSAFE.arrayBaseOffset(byte[].class);
    private static final long SHORT_ARRAY_BASE_OFFSET = THE_UNSAFE.arrayBaseOffset(byte[].class);
    private static final long INT_ARRAY_BASE_OFFSET = THE_UNSAFE.arrayBaseOffset(byte[].class);
    private static final long LONG_ARRAY_BASE_OFFSET = THE_UNSAFE.arrayBaseOffset(byte[].class);
    
    public static PointerWrapper NULLPTR = new PointerWrapper(0, 0);
    
    public boolean isNull() {
        return pointer == 0;
    }
    
    public static PointerWrapper alloc(long size, long align) {
        // LWJGL says this needs to be freed by an aligned_free
        // C11 spec and jemalloc say otherwise, this should be fine
        final long ptr = MemoryUtil.nmemAlignedAlloc(align, size);
        return trackPointer(new PointerWrapper(ptr, size));
    }
    
    public static PointerWrapper alloc(long size) {
        final long ptr = MemoryUtil.nmemAlloc(size);
        return trackPointer(new PointerWrapper(ptr, size));
    }
    
    public PointerWrapper realloc(long newSize) {
        if (size == newSize) {
            return this;
        }
        // this allows NULLPTR.realloc(size) to be used, avoiding a special initial alloc
        if (this.pointer == 0) {
            return alloc(newSize);
        }
        final var newPtr = MemoryUtil.nmemRealloc(this.pointer, newSize);
        final var newWrapped = new PointerWrapper(newPtr, newSize);
        // retracks to realloc location and size
        untrackPointer(this);
        trackPointer(newWrapped);
        return newWrapped;
    }
    
    public void free() {
        if (this == NULLPTR) {
            // technically, save to call free on a nullptr at the native level, but debug will complain if enabled
            return;
        }
        untrackPointer(this);
        MemoryUtil.nmemFree(pointer);
    }
    
    public void verifyCanAccessRange(long offset, long size, boolean read) {
        verifyCanAccessLocation(pointer + offset, size, read);
    }
    
    public void verifyCanRead(long offset, long size) {
        verifyCanAccessRange(offset, size, true);
    }
    
    public void verifyCanWrite(long offset, long size) {
        verifyCanAccessRange(offset, size, false);
    }
    
    public void verifyCanAccessRange(long size, boolean read) {
        verifyCanAccessRange(0, size, read);
    }
    
    public void verifyCanRead(long size) {
        verifyCanAccessRange(size, true);
    }
    
    public void verifyCanWrite(long size) {
        verifyCanAccessRange(size, false);
    }
    
    public void verifyCanAccess(boolean read) {
        verifyCanAccessRange(size, read);
    }
    
    public void verifyCanRead() {
        verifyCanAccess(true);
    }
    
    public void verifyCanWrite() {
        verifyCanAccess(false);
    }
    
    public PointerWrapper clear() {
        return set((byte) 0);
    }
    
    public PointerWrapper set(byte data) {
        return set(0, size, data);
    }
    
    public PointerWrapper set(long offset, long size, byte data) {
        verifyCanWrite(offset, size);
        LibCString.nmemset(pointer + offset, data, size);
        return this;
    }
    
    public static void copy(long srcPtr, long srcSize, long dstPtr, long dstSize, long size) {
        if (DEBUG) {
            if (size <= 0) {
                throw new IllegalArgumentException("Attempt to copy pointer with invalid size: " + size);
            }
            if (srcSize >= 0 && size > srcSize) {
                throw new IllegalArgumentException("Attempt to copy pointer would read past end of src. src size: " + srcSize + ", copy size: " + size);
            }
            if (dstSize >= 0 && size > dstSize) {
                throw new IllegalArgumentException("Attempt to copy pointer would read past end of dst. dst size: " + dstSize + ", copy size: " + size);
            }
            verifyCanAccessLocation(srcPtr, size, true);
            verifyCanAccessLocation(dstPtr, size, false);
        }
        boolean overlaps = srcPtr == dstPtr;
        overlaps |= srcPtr < dstPtr && dstPtr < srcPtr + size;
        overlaps |= dstPtr < srcPtr && srcPtr < dstPtr + size;
        if (overlaps) {
            LibCString.nmemmove(dstPtr, srcPtr, size);
        } else {
            LibCString.nmemcpy(dstPtr, srcPtr, size);
        }
    }
    
    public static void copy(PointerWrapper src, long srcOffset, PointerWrapper dst, long dstOffset, long size) {
        if (src.pointer == 0 || dst.pointer == 0) {
            throw new IllegalStateException("Attempt to use NULLPTR");
        }
        final var srcPtr = src.pointer + srcOffset;
        final var dstPtr = dst.pointer + dstOffset;
        if (DEBUG) {
            if (srcOffset < 0) {
                throw new IllegalArgumentException("Attempt to copy pointer with invalid srcOffset: " + srcOffset);
            }
            if (dstOffset < 0) {
                throw new IllegalArgumentException("Attempt to copy pointer with invalid dstOffset: " + dstOffset);
            }
        }
        copy(srcPtr, src.size - srcOffset, dstPtr, dst.size - dstOffset, size);
    }
    
    public void copy(long srcOffset, long dstOffset, long size) {
        copy(this, srcOffset, this, dstOffset, size);
    }
    
    public void copyTo(long srcOffset, PointerWrapper dst, long dstOffset, long size) {
        copy(this, srcOffset, dst, dstOffset, size);
    }
    
    public void copyToSize(long srcOffset, PointerWrapper dst, long size) {
        copyTo(srcOffset, dst, 0, size);
    }
    
    public void copyTo(long srcOffset, PointerWrapper dst, long dstOffset) {
        copyTo(srcOffset, dst, 0, Math.min(size - srcOffset, dst.size));
    }
    
    public void copyTo(long srcOffset, PointerWrapper dst) {
        copyTo(srcOffset, dst, 0, Math.min(size - srcOffset, dst.size));
    }
    
    public void copyTo(PointerWrapper dst, long dstOffset, long size) {
        copyTo(0, dst, dstOffset, size);
    }
    
    public void copyTo(PointerWrapper dst, long dstOffset) {
        copyTo(0, dst, dstOffset, Math.min(size, dst.size - dstOffset));
    }
    
    public void copyToSize(PointerWrapper dst, long size) {
        copyTo(0, dst, 0, size);
    }
    
    public void copyTo(PointerWrapper dst) {
        copyTo(0, dst, 0, Math.min(size, dst.size));
    }
    
    private void checkRange(long offset, long writeSize, boolean read) {
        checkRange(offset, writeSize, writeSize, read);
    }
    
    private void checkRange(long offset, long writeSize, long alignment, boolean read) {
        if (this.pointer == 0) {
            throw new IllegalStateException("Attempt to use NULLPTR");
        }
        final var dstPtr = pointer + offset;
        if (DEBUG) {
            if (offset < 0) {
                throw new IllegalArgumentException("Attempt to access before beginning of pointer");
            }
            if (offset + writeSize > size) {
                throw new IllegalArgumentException("Attempt to access past end of pointer");
            }
            if ((dstPtr % alignment) != 0) {
                throw new IllegalArgumentException("Attempt to access unaligned address");
            }
            verifyCanAccessLocation(dstPtr, writeSize, read);
        }
    }
    
    public PointerWrapper putByte(long offset, byte val) {
        checkRange(offset, 1, false);
        MemoryUtil.memPutByte(pointer + offset, val);
        return this;
    }
    
    public PointerWrapper putShort(long offset, short val) {
        checkRange(offset, 2, false);
        MemoryUtil.memPutShort(pointer + offset, val);
        return this;
    }
    
    public PointerWrapper putInt(long offset, int val) {
        checkRange(offset, 4, false);
        MemoryUtil.memPutInt(pointer + offset, val);
        return this;
    }
    
    public PointerWrapper putLong(long offset, long val) {
        checkRange(offset, 8, false);
        MemoryUtil.memPutLong(pointer + offset, val);
        return this;
    }
    
    public PointerWrapper putFloat(long offset, float val) {
        checkRange(offset, 4, false);
        MemoryUtil.memPutFloat(pointer + offset, val);
        return this;
    }
    
    public PointerWrapper putDouble(long offset, double val) {
        checkRange(offset, 8, false);
        MemoryUtil.memPutDouble(pointer + offset, val);
        return this;
    }
    
    public PointerWrapper putVector3i(long offset, Vector3ic vector) {
        checkRange(offset, 12, 16, false);
        final var dstPtr = pointer + offset;
        if (JOML_UNSAFE_AVAILABLE) {
            vector.getToAddress(pointer + offset);
        } else {
            MemoryUtil.memPutInt(dstPtr, vector.x());
            MemoryUtil.memPutInt(dstPtr + 4, vector.y());
            MemoryUtil.memPutInt(dstPtr + 8, vector.z());
        }
        return this;
    }
    
    public PointerWrapper putVector3f(long offset, Vector3fc vector) {
        checkRange(offset, 12, 16, false);
        final var dstPtr = pointer + offset;
        if (JOML_UNSAFE_AVAILABLE) {
            vector.getToAddress(pointer + offset);
        } else {
            MemoryUtil.memPutFloat(dstPtr, vector.x());
            MemoryUtil.memPutFloat(dstPtr + 4, vector.y());
            MemoryUtil.memPutFloat(dstPtr + 8, vector.z());
        }
        return this;
    }
    
    public PointerWrapper putVector4i(long offset, Vector4ic vector) {
        checkRange(offset, 16, false);
        final var dstPtr = pointer + offset;
        if (JOML_UNSAFE_AVAILABLE) {
            vector.getToAddress(pointer + offset);
        } else {
            MemoryUtil.memPutInt(dstPtr, vector.x());
            MemoryUtil.memPutInt(dstPtr + 4, vector.y());
            MemoryUtil.memPutInt(dstPtr + 8, vector.z());
            MemoryUtil.memPutInt(dstPtr + 12, vector.w());
        }
        return this;
    }
    
    public PointerWrapper putVector4f(long offset, Vector4fc vector) {
        checkRange(offset, 16, false);
        final var dstPtr = pointer + offset;
        if (JOML_UNSAFE_AVAILABLE) {
            vector.getToAddress(pointer + offset);
        } else {
            MemoryUtil.memPutFloat(dstPtr, vector.x());
            MemoryUtil.memPutFloat(dstPtr + 4, vector.y());
            MemoryUtil.memPutFloat(dstPtr + 8, vector.z());
            MemoryUtil.memPutFloat(dstPtr + 12, vector.w());
        }
        return this;
    }
    
    public PointerWrapper putMatrix4f(long offset, Matrix4fc matrix) {
        checkRange(offset, 64, 16, false);
        final var dstPtr = pointer + offset;
        if (JOML_UNSAFE_AVAILABLE) {
            matrix.getToAddress(dstPtr);
        } else {
            MemoryUtil.memPutFloat(dstPtr, matrix.m00());
            MemoryUtil.memPutFloat(dstPtr + 4, matrix.m01());
            MemoryUtil.memPutFloat(dstPtr + 8, matrix.m02());
            MemoryUtil.memPutFloat(dstPtr + 12, matrix.m03());
            MemoryUtil.memPutFloat(dstPtr + 16, matrix.m10());
            MemoryUtil.memPutFloat(dstPtr + 20, matrix.m11());
            MemoryUtil.memPutFloat(dstPtr + 24, matrix.m12());
            MemoryUtil.memPutFloat(dstPtr + 28, matrix.m13());
            MemoryUtil.memPutFloat(dstPtr + 32, matrix.m20());
            MemoryUtil.memPutFloat(dstPtr + 36, matrix.m21());
            MemoryUtil.memPutFloat(dstPtr + 40, matrix.m22());
            MemoryUtil.memPutFloat(dstPtr + 44, matrix.m23());
            MemoryUtil.memPutFloat(dstPtr + 48, matrix.m30());
            MemoryUtil.memPutFloat(dstPtr + 52, matrix.m31());
            MemoryUtil.memPutFloat(dstPtr + 56, matrix.m32());
            MemoryUtil.memPutFloat(dstPtr + 60, matrix.m33());
        }
        return this;
    }
    
    public PointerWrapper putMatrix3x4f(long offset, Matrix4fc matrix) {
        checkRange(offset, 48, 16, false);
        // TODO: check new JOML for getToAddress3x4, or an open issue for it
        final var dstPtr = pointer + offset;
        MemoryUtil.memPutFloat(dstPtr, matrix.m00());
        MemoryUtil.memPutFloat(dstPtr + 4, matrix.m01());
        MemoryUtil.memPutFloat(dstPtr + 8, matrix.m02());
        MemoryUtil.memPutFloat(dstPtr + 12, matrix.m03());
        MemoryUtil.memPutFloat(dstPtr + 16, matrix.m10());
        MemoryUtil.memPutFloat(dstPtr + 20, matrix.m11());
        MemoryUtil.memPutFloat(dstPtr + 24, matrix.m12());
        MemoryUtil.memPutFloat(dstPtr + 28, matrix.m13());
        MemoryUtil.memPutFloat(dstPtr + 32, matrix.m20());
        MemoryUtil.memPutFloat(dstPtr + 36, matrix.m21());
        MemoryUtil.memPutFloat(dstPtr + 40, matrix.m22());
        MemoryUtil.memPutFloat(dstPtr + 44, matrix.m23());
        return this;
    }
    
    public PointerWrapper putMatrix4fToGLSLMat3(long offset, Matrix4fc matrix) {
        checkRange(offset, 48, 16, false);
        final var dstPtr = pointer + offset;
        MemoryUtil.memPutFloat(dstPtr, matrix.m00());
        MemoryUtil.memPutFloat(dstPtr + 4, matrix.m01());
        MemoryUtil.memPutFloat(dstPtr + 8, matrix.m02());
        MemoryUtil.memPutFloat(dstPtr + 16, matrix.m10());
        MemoryUtil.memPutFloat(dstPtr + 20, matrix.m11());
        MemoryUtil.memPutFloat(dstPtr + 24, matrix.m12());
        MemoryUtil.memPutFloat(dstPtr + 32, matrix.m20());
        MemoryUtil.memPutFloat(dstPtr + 36, matrix.m21());
        MemoryUtil.memPutFloat(dstPtr + 40, matrix.m22());
        return this;
    }
    
    public PointerWrapper putMatrix3fToGLSLMat3(long offset, Matrix3fc matrix) {
        checkRange(offset, 48, 16, false);
        final var dstPtr = pointer + offset;
        MemoryUtil.memPutFloat(dstPtr, matrix.m00());
        MemoryUtil.memPutFloat(dstPtr + 4, matrix.m01());
        MemoryUtil.memPutFloat(dstPtr + 8, matrix.m02());
        MemoryUtil.memPutFloat(dstPtr + 16, matrix.m10());
        MemoryUtil.memPutFloat(dstPtr + 20, matrix.m11());
        MemoryUtil.memPutFloat(dstPtr + 24, matrix.m12());
        MemoryUtil.memPutFloat(dstPtr + 32, matrix.m20());
        MemoryUtil.memPutFloat(dstPtr + 36, matrix.m21());
        MemoryUtil.memPutFloat(dstPtr + 40, matrix.m22());
        return this;
    }
    
    public PointerWrapper putByteArray(long offset, byte[] array) {
        checkRange(offset, array.length, 1, false);
        THE_UNSAFE.copyMemory(array, BYTE_ARRAY_BASE_OFFSET, null, offset, array.length);
        return this;
    }
    
    public PointerWrapper putShortArray(long offset, short[] array) {
        checkRange(offset, array.length * 2L, 2, false);
        THE_UNSAFE.copyMemory(array, SHORT_ARRAY_BASE_OFFSET, null, offset, array.length * 2L);
        return this;
    }
    
    public PointerWrapper putIntArray(long offset, int[] array) {
        checkRange(offset, array.length * 4L, 4, false);
        THE_UNSAFE.copyMemory(array, INT_ARRAY_BASE_OFFSET, null, offset, array.length * 8L);
        return this;
    }
    
    public PointerWrapper putLongArray(long offset, long[] array) {
        checkRange(offset, array.length * 8L, 8, false);
        THE_UNSAFE.copyMemory(array, LONG_ARRAY_BASE_OFFSET, null, offset, array.length * 8L);
        return this;
    }
    
    public PointerWrapper putShortIdx(long index, short val) {
        return putShort(index * MagicNumbers.SHORT_BYTE_SIZE, val);
    }
    
    public PointerWrapper putIntIdx(long index, int val) {
        return putInt(index * MagicNumbers.INT_BYTE_SIZE, val);
    }
    
    public PointerWrapper putLongIdx(long index, long val) {
        return putLong(index * MagicNumbers.LONG_BYTE_SIZE, val);
    }
    
    public PointerWrapper putFloatIdx(long index, float val) {
        return putFloat(index * MagicNumbers.FLOAT_BYTE_SIZE, val);
    }
    
    public PointerWrapper putDoubleIdx(long index, double val) {
        return putDouble(index * MagicNumbers.DOUBLE_BYTE_SIZE, val);
    }
    
    public PointerWrapper putVector3iIdx(long index, Vector3ic vector) {
        return putVector3i(index * MagicNumbers.IVEC3_BYTE_SIZE, vector);
    }
    
    public PointerWrapper putVector4iIdx(long index, Vector4ic vector) {
        return putVector4i(index * MagicNumbers.IVEC4_BYTE_SIZE, vector);
    }
    
    public PointerWrapper putVector4fIdx(long index, Vector4fc vector) {
        return putVector4f(index * MagicNumbers.VEC4_BYTE_SIZE, vector);
    }
    
    public PointerWrapper putMatrix4fIdx(long index, Matrix4fc matrix4f) {
        return putMatrix4f(index * MagicNumbers.MATRIX_4F_BYTE_SIZE, matrix4f);
    }
    
    public byte getByte(long offset) {
        checkRange(offset, 1, true);
        return MemoryUtil.memGetByte(pointer + offset);
    }
    
    public short getShort(long offset) {
        checkRange(offset, 2, true);
        return MemoryUtil.memGetShort(pointer + offset);
    }
    
    public int getInt(long offset) {
        checkRange(offset, 4, true);
        return MemoryUtil.memGetInt(pointer + offset);
    }
    
    public long getLong(long offset) {
        checkRange(offset, 8, true);
        return MemoryUtil.memGetLong(pointer + offset);
    }
    
    public float getFloat(long offset) {
        checkRange(offset, 4, true);
        return MemoryUtil.memGetFloat(pointer + offset);
    }
    
    public double getDouble(long offset) {
        checkRange(offset, 8, true);
        return MemoryUtil.memGetDouble(pointer + offset);
    }
    
    public void getMatrix4f(long offset, Matrix4f matrix) {
        checkRange(offset, 64, 16, true);
        matrix.setFromAddress(pointer + offset);
    }
    
    public void getMatrix3x4f(long offset, Matrix4f matrix) {
        checkRange(offset, 64, 16, true);
        matrix.setFromAddress(pointer + offset);
    }
    
    public PointerWrapper getMatrix3fFromGLSLMat3(long offset, Matrix3f matrix) {
        checkRange(offset, 48, 16, false);
        final var dstPtr = pointer + offset;
        matrix.m00(MemoryUtil.memGetFloat(dstPtr));
        matrix.m01(MemoryUtil.memGetFloat(dstPtr + 4));
        matrix.m02(MemoryUtil.memGetFloat(dstPtr + 8));
        matrix.m10(MemoryUtil.memGetFloat(dstPtr + 16));
        matrix.m11(MemoryUtil.memGetFloat(dstPtr + 20));
        matrix.m12(MemoryUtil.memGetFloat(dstPtr + 24));
        matrix.m20(MemoryUtil.memGetFloat(dstPtr + 32));
        matrix.m21(MemoryUtil.memGetFloat(dstPtr + 36));
        matrix.m22(MemoryUtil.memGetFloat(dstPtr + 40));
        return this;
    }
    
    public PointerWrapper getMatrix4fFromGLSLMat3(long offset, Matrix4f matrix) {
        checkRange(offset, 48, 16, false);
        final var dstPtr = pointer + offset;
        matrix.m00(MemoryUtil.memGetFloat(dstPtr));
        matrix.m01(MemoryUtil.memGetFloat(dstPtr + 4));
        matrix.m02(MemoryUtil.memGetFloat(dstPtr + 8));
        matrix.m10(MemoryUtil.memGetFloat(dstPtr + 16));
        matrix.m11(MemoryUtil.memGetFloat(dstPtr + 20));
        matrix.m12(MemoryUtil.memGetFloat(dstPtr + 24));
        matrix.m20(MemoryUtil.memGetFloat(dstPtr + 32));
        matrix.m21(MemoryUtil.memGetFloat(dstPtr + 36));
        matrix.m22(MemoryUtil.memGetFloat(dstPtr + 40));
        return this;
    }
    
    public void getVector2f(long offset, Vector2f vector) {
        checkRange(offset, 8, 8, true);
        vector.setFromAddress(pointer + offset);
    }
    
    public void getVector3i(long offset, Vector3i vector) {
        checkRange(offset, 12, 16, true);
        vector.setFromAddress(pointer + offset);
    }
    
    public void getVector3f(long offset, Vector3f vector) {
        checkRange(offset, 12, 16, true);
        vector.setFromAddress(pointer + offset);
    }
    
    public void getNormalizedVector3fFromI16vec3(long offset, Vector3f vector) {
        checkRange(offset, 6, 8, true);
        vector.x = getShort(offset) * (1.0f / Short.MAX_VALUE);
        vector.y = getShort(offset + 2) * (1.0f / Short.MAX_VALUE);
        vector.z = getShort(offset + 4) * (1.0f / Short.MAX_VALUE);
    }
    
    public void getNormalizedVector4fFromU8vec4(long offset, Vector4f vector) {
        checkRange(offset, 4, 4, true);
        vector.x = Byte.toUnsignedInt(getByte(offset)) * (1.0f / 255.0f);
        vector.y = Byte.toUnsignedInt(getByte(offset + 1)) * (1.0f / 255.0f);
        vector.z = Byte.toUnsignedInt(getByte(offset + 2)) * (1.0f / 255.0f);
        vector.w = Byte.toUnsignedInt(getByte(offset + 3)) * (1.0f / 255.0f);
    }
    
    public void getByteArray(long offset, byte[] array) {
        checkRange(offset, array.length, 1, true);
        THE_UNSAFE.copyMemory(null, offset, array, BYTE_ARRAY_BASE_OFFSET, array.length);
    }
    
    public void getShortArray(long offset, short[] array) {
        checkRange(offset, array.length * 2L, 2, true);
        THE_UNSAFE.copyMemory(null, offset, array, SHORT_ARRAY_BASE_OFFSET, array.length * 2L);
    }
    
    public void getIntArray(long offset, int[] array) {
        checkRange(offset, array.length * 4L, 4, true);
        THE_UNSAFE.copyMemory(null, offset, array, INT_ARRAY_BASE_OFFSET, array.length * 8L);
    }
    
    public void getIntArray(long offset, long[] array) {
        checkRange(offset, array.length * 8L, 8, true);
        THE_UNSAFE.copyMemory(null, offset, array, LONG_ARRAY_BASE_OFFSET, array.length * 8L);
    }
    
    public short getShortIdx(long index) {
        return getShort(index * MagicNumbers.SHORT_BYTE_SIZE);
    }
    
    public int getIntIdx(long index) {
        return getInt(index * MagicNumbers.INT_BYTE_SIZE);
    }
    
    public long getLongIdx(long index) {
        return getLong(index * MagicNumbers.LONG_BYTE_SIZE);
    }
    
    public float getFloatIdx(long index) {
        return getFloat(index * MagicNumbers.FLOAT_BYTE_SIZE);
    }
    
    public double getDoubleIdx(long index) {
        return getDouble(index * MagicNumbers.DOUBLE_BYTE_SIZE);
    }
    
    public void getMatrix4fIdx(long index, Matrix4f matrix4f) {
        getMatrix4f(index * MagicNumbers.MATRIX_4F_BYTE_SIZE, matrix4f);
    }
    
    public PointerWrapper sliceArrayIdx(int index, long size) {
        return slice(size * index, size);
    }
    
    public boolean canSlice(long offset, long size){
        return size > 0 && offset >= 0 && (offset + size) <= this.size;
    }
    
    public PointerWrapper slice(long offset) {
        if (offset == 0) {
            return this;
        }
        return slice(offset, size - offset);
    }
    
    public PointerWrapper slice(long offset, long size) {
        if (this.pointer == 0) {
            throw new NullPointerException("Attempt to use NULLPTR");
        }
        if (DEBUG) {
            if (size <= 0) {
                throw new IllegalArgumentException("Attempt to slice pointer to invalid size: " + size);
            }
            if (offset < 0) {
                throw new IllegalArgumentException("Attempt to slice pointer to invalid offset: " + offset);
            }
            if (offset >= this.size) {
                throw new IllegalArgumentException("Attempt to slice pointer to offset past end. offset: " + offset + ", source size: " + this.size);
            }
            if ((offset + size) > this.size) {
                throw new IllegalArgumentException("Attempt to slice pointer to offset and size past end. offset: " + offset + ", size: " + size + ", source size: " + this.size);
            }
        }
        return new PointerWrapper(pointer + offset, size);
    }
    
    public boolean contains(PointerWrapper that) {
        return this.pointer <= that.pointer && that.pointer + that.size <= pointer + size;
    }
    
    @Override
    public int compareTo(@Nonnull PointerWrapper other) {
        var ptrCompare = Long.compare(this.pointer, other.pointer);
        if (ptrCompare != 0) {
            return ptrCompare;
        }
        // this is backwards so that child allocations are placed afterward
        return Long.compare(other.size, this.size);
    }
}
