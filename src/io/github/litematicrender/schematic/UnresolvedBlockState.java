package io.github.litematicrender.schematic;

import java.util.Map;

/**
 * A palette entry as stored in the .litematic file, before registry resolution.
 */
public record UnresolvedBlockState(String blockId, Map<String, String> properties) {

    public UnresolvedBlockState {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
