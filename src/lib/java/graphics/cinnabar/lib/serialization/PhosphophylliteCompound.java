package graphics.cinnabar.lib.serialization;

import graphics.cinnabar.lib.robn.ROBNObject;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PhosphophylliteCompound implements ROBNObject {
    
    private final Map<String, Object> data = new Object2ObjectOpenHashMap<>();
    
    public PhosphophylliteCompound() {
    }
    
    public PhosphophylliteCompound(Map<String, Object> ROBNMap) {
        fromROBNMap(ROBNMap);
    }
    
    public PhosphophylliteCompound(byte[] ROBNbuffer) {
        this(ByteArrayList.wrap(ROBNbuffer));
    }
    
    public PhosphophylliteCompound(List<Byte> ROBNbuffer) {
        if (ROBNbuffer.isEmpty()) {
            return;
        }
        fromROBN(ROBNbuffer);
    }
    
    public void put(String key, @Nullable Object value){
        if (value == null) {
            return;
        }
        data.put(key, value);
    }
    
    public void put(String key, @Nullable PhosphophylliteCompound compound) {
        if (compound == null) {
            return;
        }
        data.put(key, compound);
    }
    
    public void put(String key, @Nullable String value) {
        if (value == null) {
            return;
        }
        data.put(key, value);
    }
    
    public void put(String key, boolean value) {
        data.put(key, value);
    }
    
    public void put(String key, byte value) {
        data.put(key, value);
    }
    
    public void put(String key, short value) {
        data.put(key, value);
    }
    
    public void put(String key, int value) {
        data.put(key, value);
    }
    
    public void put(String key, long value) {
        data.put(key, value);
    }
    
    public void put(String key, float value) {
        data.put(key, value);
    }
    
    public void put(String key, double value) {
        data.put(key, value);
    }
    
    public void put(String key, List<?> value) {
        data.put(key, value);
    }
    
    public void put(String key, Map<?, ?> value) {
        data.put(key, value);
    }
    
    public Object get(String key) {
        return data.get(key);
    }
    
    public PhosphophylliteCompound getCompound(String key) {
        var val = data.get(key);
        if (!(val instanceof PhosphophylliteCompound)) {
            if (val instanceof Map) {
                var compound = new PhosphophylliteCompound();
                //noinspection unchecked
                compound.fromROBNMap((Map<String, Object>) val);
                data.put(key, compound);
                return compound;
            }
            return new PhosphophylliteCompound();
        }
        return (PhosphophylliteCompound) val;
    }
    
    public String getString(String key) {
        var val = data.get(key);
        if (!(val instanceof String)) {
            return "";
        }
        return (String) val;
    }
    
    public boolean getBoolean(String key) {
        var val = data.get(key);
        if (val instanceof Boolean bool) {
            return bool;
        }
        if (val instanceof Number number) {
            return number.longValue() != 0;
        }
        return false;
    }
    
    public byte getByte(String key) {
        var val = data.get(key);
        if (!(val instanceof Number)) {
            return 0;
        }
        return ((Number) val).byteValue();
    }
    
    public short getShort(String key) {
        var val = data.get(key);
        if (!(val instanceof Number)) {
            return 0;
        }
        return ((Number) val).shortValue();
    }
    
    public int getInt(String key) {
        var val = data.get(key);
        if (!(val instanceof Number)) {
            return 0;
        }
        return ((Number) val).intValue();
    }
    
    public long getLong(String key) {
        var val = data.get(key);
        if (!(val instanceof Number)) {
            return 0;
        }
        return ((Number) val).longValue();
    }
    
    public float getFloat(String key) {
        var val = data.get(key);
        if (!(val instanceof Number)) {
            return 0;
        }
        return ((Number) val).floatValue();
    }
    
    public double getDouble(String key) {
        var val = data.get(key);
        if (!(val instanceof Number)) {
            return 0;
        }
        return ((Number) val).doubleValue();
    }
    
    public List<?> getList(String key) {
        Object obj = data.get(key);
        if (obj instanceof List<?> list) {
            return list;
        }
        return new ArrayList<>();
    }
    
    public Map<?, ?> getMap(String key) {
        Object obj = data.get(key);
        if (obj instanceof Map<?, ?> map) {
            return map;
        }
        return new HashMap<>();
    }
    
    @Override
    public Map<String, Object> toROBNMap() {
        return data;
    }
    
    @Override
    public void fromROBNMap(Map<String, Object> map) {
        data.clear();
        data.putAll(map);
    }
    
    public void combine(PhosphophylliteCompound other) {
        other.data.forEach((str, obj) -> {
            if (obj == null) {
                this.data.remove(str);
                return;
            }
            this.data.put(str, obj);
        });
    }
}

