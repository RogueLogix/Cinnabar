package graphics.cinnabar.lib.robn;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;

import java.util.List;
import java.util.Map;

public interface ROBNObject {
    default ByteArrayList toROBN() {
        return ROBN.toROBN(toROBNMap());
    }
    
    Map<String, Object> toROBNMap();
    
    default void fromROBN(List<Byte> buf) {
        Object robnObject = ROBN.fromROBN(buf);
        if (!(robnObject instanceof Map)) {
            throw new IllegalArgumentException("Malformed binary");
        }
        // TODO: 7/26/20 check to make sure the keys are strings
        //noinspection unchecked
        fromROBNMap((Map<String, Object>) robnObject);
    }
    
    void fromROBNMap(Map<String, Object> map);
}
