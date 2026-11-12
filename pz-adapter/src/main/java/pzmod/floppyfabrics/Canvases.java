package pzmod.floppyfabrics;

import java.util.HashMap;
import java.util.Map;
import zombie.core.textures.Texture;

/**
 * Cached texture lookups for canvases (loose PNGs, e.g. this mod's media/floppyfabrics/pole_flag.png) and for sprite textures used by
 * static quads. {@code Texture.getSharedTexture} resolves {@code media/...} paths through the active mods' file map
 * (ZomboidFileSystem.getString, RECON §3b) and sprite names through the texture packs. Loading is asynchronous: callers
 * check {@code isReady()}. A missing file is logged once and cached as missing (that flag then draws as vanilla).
 */
final class Canvases {
    private static final Object MISSING = new Object();
    private static final Map<String, Object> cache = new HashMap<>();

    private Canvases() {}

    static Texture get(String path) {
        return lookup(path, "canvas texture");
    }

    static Texture sprite(String spriteName) {
        return lookup(spriteName, "sprite texture");
    }

    private static Texture lookup(String key, String what) {
        if (key == null) return null;
        Object hit = cache.get(key);
        if (hit == null) {
            Texture t = null;
            try {
                t = Texture.getSharedTexture(key);
            } catch (Throwable e) {
                FloppyFabrics.warn("loading " + what + " " + key + " failed: " + e);
            }
            if (t == null) {
                FloppyFabrics.warn(what + " not found: " + key + " (is the mod providing it enabled?) — that flag draws as vanilla");
                cache.put(key, MISSING);
                return null;
            }
            cache.put(key, t);
            return t;
        }
        return hit == MISSING ? null : (Texture) hit;
    }
}
