package graphics.cinnabar.lib.robn;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.*;

/**
 * This is a Java port of the C++ implementation
 */
public class ROBN {
    
    public static <T> ByteArrayList toROBN(T t) {
        ByteArrayList ObjectArrayList = new ByteArrayList();
        toROBN(t, ObjectArrayList);
        return ObjectArrayList;
    }
    
    public static Object fromROBN(List<Byte> buf) {
        return fromROBN(buf.iterator());
    }
    
    public enum Type {
        Undefined(0),
        
        String(1),
        Bool(2),
        
        Int8(4),
        Int16(5),
        Int32(6),
        Int64(7),
        Int128(18),
        BigInt(21),
        
        uInt8(8),
        uInt16(9),
        uInt32(10),
        uInt64(11),
        uInt128(19),
        
        Float(12),
        Double(13),
        LongDouble(20),
        BigFloat(22),
        
        Vector(15),
        Pair(16),
        Map(17),
        
        ;
        
        public final byte val;
        
        Type(int i) {
            val = (byte) i;
        }
        
        public static <T> Type typeID(Class<T> tClass) {
            
            if (tClass == java.lang.String.class) {
                return String;
            }
            
            if (tClass == boolean.class || tClass == Boolean.class) {
                return Bool;
            }
            
            if (tClass == byte.class || tClass == Byte.class) {
                return Int8;
            }
            if (tClass == short.class || tClass == Short.class) {
                return Int16;
            }
            if (tClass == int.class || tClass == Integer.class) {
                return Int32;
            }
            if (tClass == long.class || tClass == Long.class) {
                return Int64;
            }
            
            if (tClass == BigDecimal.class) {
                return BigFloat;
            }
            if (tClass == BigInteger.class) {
                return BigInt;
            }
            
            // java doesnt have unsigned integers, *so*
            
            if (tClass == float.class || tClass == java.lang.Float.class) {
                return Float;
            }
            if (tClass == double.class || tClass == java.lang.Double.class) {
                return Double;
            }
            
            if (Collection.class.isAssignableFrom(tClass)) {
                return Vector;
            }
            
            if (java.util.Map.class.isAssignableFrom(tClass) || ROBNObject.class.isAssignableFrom(tClass)) {
                return Map;
            }
            
            return Undefined;
        }
        
        static int primitiveTypeSize(Type type) {
            return switch (type) {
                case Bool, Int8, uInt8 -> 1;
                case Int16, uInt16 -> 2;
                case Int32, uInt32, Float -> 4;
                case Int64, uInt64, Double -> 8;
                case Int128, uInt128 -> 16;
                default -> 0;
            };
        }
        
        private static final HashMap<Byte, Type> idLookup = new HashMap<>();
        
        static {
            idLookup.put(Undefined.val, Undefined);
            idLookup.put(String.val, String);
            idLookup.put(Bool.val, Bool);
            idLookup.put(Int8.val, Int8);
            idLookup.put(Int16.val, Int16);
            idLookup.put(Int32.val, Int32);
            idLookup.put(Int64.val, Int64);
            idLookup.put(Int128.val, Int128);
            idLookup.put(BigInt.val, BigInt);
            idLookup.put(uInt8.val, uInt8);
            idLookup.put(uInt16.val, uInt16);
            idLookup.put(uInt32.val, uInt32);
            idLookup.put(uInt64.val, uInt64);
            idLookup.put(uInt128.val, uInt128);
            idLookup.put(Float.val, Float);
            idLookup.put(Double.val, Double);
            idLookup.put(LongDouble.val, LongDouble);
            idLookup.put(BigFloat.val, BigFloat);
            idLookup.put(Vector.val, Vector);
            idLookup.put(Pair.val, Pair);
            idLookup.put(Map.val, Map);
            
        }
        
        public static Type fromID(byte id) {
            id &= 0x7F;
            return idLookup.get(id);
        }
    }
    
    public enum Endianness {
        LITTLE(0),
        BIG(1 << 7),
        
        // im always going to encode to little endian from Java, its *probably* LE under the hood regardless
        // like, it almost always is, and BE/LE isn't going to be any faster/slow in Java
        NATIVE(ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN) ? BIG.val : LITTLE.val);
        
        public final byte val;
        
        Endianness(int i) {
            val = (byte) i;
        }
        
        @SuppressWarnings("unused")
        public static Endianness fromByte(byte b) {
            if ((b & (1 << 7)) != 0) {
                return BIG;
            } else {
                return LITTLE;
            }
        }
    }
    
    private static void requestBytes(ByteArrayList buf, int requiredBytes) {
        buf.ensureCapacity(buf.size() + requiredBytes);
    }
    
    @SuppressWarnings("rawtypes")
    private static <T> void toROBN(T t, ByteArrayList buf) {
        switch (t) {
            case Boolean b -> {
                buf.add(Type.Bool.val);
                buf.add((byte) (b ? 1 : 0));
                return;
            }
            case String s -> {
                stringToROBN(s, buf);
                return;
            }
            case Number number -> {
                numberToROBN(number, buf);
                return;
            }
            case Collection collection -> {
                vectorToROBN(collection, buf);
                return;
            }
            case Map map -> {
                mapToROBN(map, buf);
                return;
            }
            case Pair pair -> {
                pairToROBN(pair, buf);
                return;
            }
            case ROBNObject robnObject -> {
                mapToROBN(robnObject.toROBNMap(), buf);
                return;
            }
            default -> {
            }
        }
        throw new IllegalArgumentException("Unknown object type");
    }
    
    private static Object fromROBN(Iterator<Byte> iterator) {
        if (iterator.hasNext()) {
            return fromROBN(iterator, Type.fromID(iterator.next()));
        }
        throw new IllegalArgumentException("Malformed Binary");
    }
    
    private static Object fromROBN(Iterator<Byte> iterator, Type type) {
        return switch (type) {
            case String -> stringFromROBN(iterator);
            case Bool, Int8, Int16, Int32, Int64, Int128, BigInt, uInt8, uInt16, uInt32, uInt64, uInt128, Float, Double,
                 LongDouble, BigFloat -> primitiveFromROBN(iterator, type);
            case Vector -> vectorFromROBN(iterator);
            case Map -> mapFromROBN(iterator);
            case Pair -> pairFromROBN(iterator);
            case Undefined -> throw new IllegalArgumentException("Malformed Binary");
        };
    }
    
    @SuppressWarnings("DuplicatedCode")
    private static void numberToROBN(Number number, ByteArrayList buf) {
        switch (number) {
            case Byte b -> {
                buf.add(Type.Int8.val);
                buf.add(number.byteValue());
                return;
            }
            case Short i -> {
                short val = number.shortValue();
                buf.add((byte) (Type.Int16.val | Endianness.NATIVE.val));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 1 : 0))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 0 : 1))) & 0xFF));
                return;
            }
            case Integer i -> {
                int val = number.intValue();
                buf.add((byte) (Type.Int32.val | Endianness.NATIVE.val));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 3 : 0))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 2 : 1))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 1 : 2))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 0 : 3))) & 0xFF));
                return;
            }
            case Long l -> {
                long val = number.longValue();
                buf.add((byte) (Type.Int64.val | Endianness.NATIVE.val));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 7 : 0))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 6 : 1))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 5 : 2))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 4 : 3))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 3 : 4))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 2 : 5))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 1 : 6))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 0 : 7))) & 0xFF));
                return;
            }
            case Float v -> {
                int val = Float.floatToIntBits(number.floatValue());
                buf.add((byte) (Type.Float.val | Endianness.NATIVE.val));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 3 : 0))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 2 : 1))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 1 : 2))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 0 : 3))) & 0xFF));
                return;
            }
            case Double v -> {
                long val = Double.doubleToLongBits(number.doubleValue());
                buf.add((byte) (Type.Double.val | Endianness.NATIVE.val));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 7 : 0))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 6 : 1))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 5 : 2))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 4 : 3))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 3 : 4))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 2 : 5))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 1 : 6))) & 0xFF));
                buf.add((byte) ((val >> (8 * (Endianness.NATIVE.val == Endianness.BIG.val ? 0 : 7))) & 0xFF));
                return;
            }
            
            case BigDecimal bigDecimal -> {
                // TODO: 7/24/20  BigDecimal
            }
            case BigInteger bigInteger -> {
                // TODO: 7/24/20  BigInteger
            }
            default -> {
            }
        }
        throw new IllegalArgumentException("Unsupported number type");
    }
    
    private static Object primitiveFromROBN(Iterator<Byte> iterator, Type type) {
        switch (type) {
            case Bool: {
                if (iterator.hasNext()) {
                    return iterator.next() != 0;
                }
                break;
            }
            case Int8: {
                if (iterator.hasNext()) {
                    return iterator.next();
                }
                break;
            }
            case Int16: {
                short val = 0;
                for (int i = 0; i < 2; i++) {
                    if (iterator.hasNext()) {
                        val |= (short) (((short) iterator.next() & 0xFF) << (8 * i));
                    } else {
                        break;
                    }
                }
                if (Endianness.NATIVE.val == Endianness.BIG.val) {
                    val = Short.reverseBytes(val);
                }
                return val;
            }
            case Int32: {
                int val = 0;
                for (int i = 0; i < 4; i++) {
                    if (iterator.hasNext()) {
                        val |= (((int) iterator.next() & 0xFF) << (8 * i));
                    } else {
                        break;
                    }
                }
                if (Endianness.NATIVE.val == Endianness.BIG.val) {
                    val = Integer.reverseBytes(val);
                }
                return val;
            }
            case Int64: {
                long val = 0;
                for (int i = 0; i < 8; i++) {
                    if (iterator.hasNext()) {
                        val |= (((long) iterator.next() & 0xFF) << (8 * i));
                    } else {
                        break;
                    }
                }
                if (Endianness.NATIVE.val == Endianness.BIG.val) {
                    val = Long.reverseBytes(val);
                }
                return val;
            }
            
            case uInt8:
                return ((byte) primitiveFromROBN(iterator, Type.Int8)) & 0x7F;
            case uInt16:
                return ((short) primitiveFromROBN(iterator, Type.Int16)) & 0x7FFF;
            case uInt32:
                return ((int) primitiveFromROBN(iterator, Type.Int32)) & 0x7FFFFFFF;
            case uInt64:
                return ((long) primitiveFromROBN(iterator, Type.Int64)) & 0x7FFFFFFFFFFFFFFFL;
            
            case Int128:
            case uInt128:
                throw new IllegalArgumentException("Incompatible Binary");
            
            case Float:
                return Float.intBitsToFloat((Integer) primitiveFromROBN(iterator, Type.Int32));
            case Double:
                return Double.longBitsToDouble((Long) primitiveFromROBN(iterator, Type.Int64));
        }
        throw new IllegalArgumentException("Malformed Binary");
    }
    
    private static void vectorToROBN(Collection<?> collection, ByteArrayList buf) {
        
        requestBytes(buf, 11);
        int elementCount = collection.size();
        buf.add(Type.Vector.val);
        toROBN(elementCount, buf);
        
        Type elementType = Type.Undefined;
        Iterator<?> iterator = collection.iterator();
        if (iterator.hasNext()) {
            Object o = iterator.next();
            elementType = Type.typeID(o.getClass());
        }
        buf.add((byte) (elementType.val | Endianness.NATIVE.val));
        
        if (Type.primitiveTypeSize(elementType) != 0) {
            requestBytes(buf, Type.primitiveTypeSize(elementType) * elementCount);
        }
        
        ByteArrayList tmpBuffer = new ByteArrayList();
        for (Object o : collection) {
            tmpBuffer.clear();
            toROBN(o, tmpBuffer);
            requestBytes(buf, tmpBuffer.size() - 1);
            for (int i = 1; i < tmpBuffer.size(); i++) {
                buf.add(tmpBuffer.getByte(i));
            }
        }
        
    }
    
    @SuppressWarnings("DuplicatedCode")
    private static Object vectorFromROBN(Iterator<Byte> iterator) {
        Object vectorLengthObj = fromROBN(iterator);
        if (!(vectorLengthObj instanceof Number)) {
            throw new IllegalArgumentException("Malformed Binary");
        }
        long vectorLength = ((Number) vectorLengthObj).longValue();
        if (vectorLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Incompatible Binary");
        }
        
        ArrayList<Object> arrayList = new ArrayList<>();
        arrayList.ensureCapacity((int) vectorLength);
        if (!iterator.hasNext()) {
            throw new IllegalArgumentException("Malformed Binary");
        }
        Type elementType = Type.fromID(iterator.next());
        
        if (Type.primitiveTypeSize(elementType) != 0) {
            return switch (elementType) {
                
                case Bool -> boolVectorFromROBN(iterator, vectorLength);
                case Int8 -> int8VectorFromROBN(iterator, vectorLength);
                case Int16 -> int16VectorFromROBN(iterator, vectorLength);
                case Int32 -> int32VectorFromROBN(iterator, vectorLength);
                case Int64 -> int64VectorFromROBN(iterator, vectorLength);
                case uInt8 -> uInt8VectorFromROBN(iterator, vectorLength);
                case uInt16 -> uInt16VectorFromROBN(iterator, vectorLength);
                case uInt32 -> uInt32VectorFromROBN(iterator, vectorLength);
                case uInt64 -> uInt64VectorFromROBN(iterator, vectorLength);
                case Float -> floatVectorFromROBN(iterator, vectorLength);
                case Double -> doubleVectorFromROBN(iterator, vectorLength);
                default -> throw new IllegalArgumentException("Malformed Binary");
            };
        }
        
        ObjectArrayList<Object> ObjectArrayList = new ObjectArrayList<>((int) vectorLength);
        
        for (long i = 0; i < vectorLength; i++) {
            ObjectArrayList.add(fromROBN(iterator, elementType));
        }
        return ObjectArrayList;
    }
    
    private static BooleanArrayList boolVectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new BooleanArrayList((int) length);
        for (int i = 0; i < length; i++) {
            if (iterator.hasNext()) {
                list.add(iterator.next() != 0);
            }
        }
        return list;
    }
    
    private static ByteArrayList int8VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new ByteArrayList((int) length);
        for (int i = 0; i < length; i++) {
            if (iterator.hasNext()) {
                list.add(iterator.next().byteValue());
            }
        }
        return list;
    }
    
    private static ByteArrayList uInt8VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = int8VectorFromROBN(iterator, length);
        for (int i = 0; i < list.size(); i++) {
            list.set(i, (byte) (list.getByte(i) & 0x7F));
        }
        return list;
    }
    
    private static ShortArrayList int16VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new ShortArrayList((int) length);
        for (int j = 0; j < length; j++) {
            short val = 0;
            for (int i = 0; i < 2; i++) {
                if (iterator.hasNext()) {
                    val |= (short) (((short) iterator.next() & 0xFF) << (8 * i));
                } else {
                    break;
                }
            }
            if (Endianness.NATIVE.val == Endianness.BIG.val) {
                val = Short.reverseBytes(val);
            }
            list.add(val);
        }
        return list;
    }
    
    private static ShortArrayList uInt16VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = int16VectorFromROBN(iterator, length);
        for (int i = 0; i < list.size(); i++) {
            list.set(i, (short) (list.getShort(i) & 0x7FFF));
        }
        return list;
    }
    
    private static IntArrayList int32VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new IntArrayList((int) length);
        for (int j = 0; j < length; j++) {
            int val = 0;
            for (int i = 0; i < 4; i++) {
                if (iterator.hasNext()) {
                    val |= (((int) iterator.next() & 0xFF) << (8 * i));
                } else {
                    break;
                }
            }
            if (Endianness.NATIVE.val == Endianness.BIG.val) {
                val = Integer.reverseBytes(val);
            }
            list.add(val);
        }
        return list;
    }
    
    private static IntArrayList uInt32VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = int32VectorFromROBN(iterator, length);
        for (int i = 0; i < list.size(); i++) {
            list.set(i, (list.getInt(i) & 0x7FFFFFFF));
        }
        return list;
    }
    
    private static LongArrayList int64VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new LongArrayList((int) length);
        for (int j = 0; j < length; j++) {
            long val = 0;
            for (int i = 0; i < 8; i++) {
                if (iterator.hasNext()) {
                    val |= (((long) iterator.next() & 0xFF) << (8 * i));
                } else {
                    break;
                }
            }
            if (Endianness.NATIVE.val == Endianness.BIG.val) {
                val = Long.reverseBytes(val);
            }
            list.add(val);
        }
        return list;
    }
    
    private static LongArrayList uInt64VectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = int64VectorFromROBN(iterator, length);
        for (int i = 0; i < list.size(); i++) {
            list.set(i, (list.getLong(i) & 0x7FFFFFFFFFFFFFFFL));
        }
        return list;
    }
    
    
    private static FloatArrayList floatVectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new FloatArrayList((int) length);
        for (int j = 0; j < length; j++) {
            int val = 0;
            for (int i = 0; i < 4; i++) {
                if (iterator.hasNext()) {
                    val |= (((int) iterator.next() & 0xFF) << (8 * i));
                } else {
                    break;
                }
            }
            if (Endianness.NATIVE.val == Endianness.BIG.val) {
                val = Integer.reverseBytes(val);
            }
            list.add(Float.intBitsToFloat(val));
        }
        return list;
    }
    
    private static DoubleArrayList doubleVectorFromROBN(Iterator<Byte> iterator, long length) {
        var list = new DoubleArrayList((int) length);
        for (int j = 0; j < length; j++) {
            long val = 0;
            for (int i = 0; i < 8; i++) {
                if (iterator.hasNext()) {
                    val |= (((long) iterator.next() & 0xFF) << (8 * i));
                } else {
                    break;
                }
            }
            if (Endianness.NATIVE.val == Endianness.BIG.val) {
                val = Long.reverseBytes(val);
            }
            list.add(Double.longBitsToDouble(val));
        }
        return list;
    }
    
    
    private static void mapToROBN(Map<?, ?> map, ByteArrayList buf) {
        // because java doesnt have native std::pair support, i get to encode them right here
        // no being lazy like in C++, *fuck*
        
        buf.add(Type.Map.val);
        toROBN(map.size(), buf);
        
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            buf.add(Type.Pair.val);
            buf.addAll(toROBN(entry.getKey()));
            buf.addAll(toROBN(entry.getValue()));
        }
        
        // that was easy
    }
    
    @SuppressWarnings("DuplicatedCode")
    private static Object mapFromROBN(Iterator<Byte> iterator) {
        Object mapLengthOBJ = fromROBN(iterator);
        if (!(mapLengthOBJ instanceof Number)) {
            throw new IllegalArgumentException("Malformed Binary");
        }
        long mapLength = ((Number) mapLengthOBJ).longValue();
        if (mapLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Incompatible Binary");
        }
        
        HashMap<Object, Object> map = new HashMap<>();
        
        for (int i = 0; i < mapLength; i++) {
            if (!iterator.hasNext() || iterator.next() != Type.Pair.val) {
                throw new IllegalArgumentException("Malformed Binary");
            }
            Object key = fromROBN(iterator);
            Object value = fromROBN(iterator);
            map.put(key, value);
        }
        return map;
    }
    
    private static void pairToROBN(Pair<?, ?> pair, ByteArrayList buf) {
        buf.add(Type.Pair.val);
        buf.addAll(toROBN(pair.getFirst()));
        buf.addAll(toROBN(pair.getSecond()));
    }
    
    private static Object pairFromROBN(Iterator<Byte> iterator) {
        Object first = fromROBN(iterator);
        Object second = fromROBN(iterator);
        return new Pair<>(first, second);
    }
    
    
    private static void stringToROBN(String str, ByteArrayList buf) {
        buf.ensureCapacity(buf.size() + str.length() + 2);
        buf.add(Type.String.val);
        for (int i = 0; i < str.length(); i++) {
            buf.add((byte) str.charAt(i));
        }
        buf.add((byte) 0);
    }
    
    private static Object stringFromROBN(Iterator<Byte> iterator) {
        StringBuilder builder = new StringBuilder();
        while (true) {
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException("Malformed Binary");
            }
            byte next = iterator.next();
            if (next == 0) {
                break;
            }
            builder.append((char) next);
        }
        return builder.toString();
    }
}