package graphics.cinnabar.lib.config;

import graphics.cinnabar.lib.parsers.Element;

public enum ConfigFormat {
    JSON5,
    TOML,
    ;
    
    public Element parse(String string) {
        return switch (this) {
            case JSON5 -> graphics.cinnabar.lib.parsers.JSON5.parseString(string);
            case TOML -> graphics.cinnabar.lib.parsers.TOML.parseString(string);
        };
    }
    
    public String parse(Element element) {
        return switch (this) {
            case JSON5 -> graphics.cinnabar.lib.parsers.JSON5.parseElement(element);
            case TOML -> graphics.cinnabar.lib.parsers.TOML.parseElement(element);
        };
    }
}
