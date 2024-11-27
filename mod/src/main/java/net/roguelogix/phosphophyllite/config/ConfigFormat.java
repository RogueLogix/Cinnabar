package net.roguelogix.phosphophyllite.config;

import net.roguelogix.phosphophyllite.parsers.Element;

public enum ConfigFormat {
    JSON5,
    TOML,
    ;
    
    public Element parse(String string) {
        return switch (this) {
            case JSON5 -> net.roguelogix.phosphophyllite.parsers.JSON5.parseString(string);
            case TOML -> net.roguelogix.phosphophyllite.parsers.TOML.parseString(string);
        };
    }
    
    public String parse(Element element) {
        return switch (this) {
            case JSON5 -> net.roguelogix.phosphophyllite.parsers.JSON5.parseElement(element);
            case TOML -> net.roguelogix.phosphophyllite.parsers.TOML.parseElement(element);
        };
    }
}
