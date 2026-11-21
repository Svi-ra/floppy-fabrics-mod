package pzmod.floppyfabrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Puts cloth sprites in vanilla's per-frame layer through data, not a patch (DECISIONS D107).
 * <p>
 * {@code FBORenderCell.calculateObjectRenderLayer} sends every object whose sprite has {@code depthFlags} bit 2 (the
 * tile-geometry "Translucent" property, TileGeometryFile.java:903) to the {@code Translucent}/{@code TranslucentSE}
 * layer, exactly where {@link Hooks#onCalculateLayer} moves flags. Bit 2 is read nowhere else in vanilla (bits 1 and 4
 * pick depth shaders). Setting it on our sprites keeps flags, banners and decorations moving even when another Java
 * mod's ZombieBuddy patch on {@code FBORenderCell} replaces ours, which happens when both are applied as the class
 * first loads (WindSway, 2026-11-21).
 * <p>
 * The bits do not stay put on their own: loading a game rebuilds every sprite ({@code IsoSpriteManager.Dispose} clears
 * {@code namedMap}, new {@link IsoSprite} objects follow), and {@code TileGeometryManager.initSpriteProperties} zeroes
 * every sprite's {@code depthFlags}. So marks are kept by sprite name, and {@link #apply} (cheap, run periodically by
 * {@link LayerGuard}) re-marks any live sprite that lost its bit or replaced the one we marked. Only bits we set are
 * ever cleared. Main thread only.
 */
final class LayerRoute {
    /** {@code IsoSprite.depthFlags} bit for "always draw in the translucent (per-frame) layer". */
    static final int TRANSLUCENT = 2;

    /** Sprite name → the live sprite we set the bit on. */
    private static final Map<String, IsoSprite> marked = new HashMap<>();
    private static String witnessName;
    /** How many times bits had to be set again (new world, or vanilla reset them). */
    static int reapplies;

    private LayerRoute() {}

    /**
     * One map lookup: false if the sprites were rebuilt or reset since the last {@link #apply}, so it should run again.
     */
    static boolean looksInPlace() {
        if (witnessName == null) return false;
        IsoSprite live = IsoSpriteManager.instance.namedMap.get(witnessName);
        return live != null && live == marked.get(witnessName) && (live.depthFlags & TRANSLUCENT) != 0;
    }

    /**
     * Sets bit 2 on every live per-frame cloth sprite that does not have it from us yet.
     * @return the number of sprites newly (re)marked
     */
    static int apply() {
        boolean before = !marked.isEmpty();
        int n = 0;
        for (String name : FlagRegistry.perFrameSpriteNames()) {
            IsoSprite live = IsoSpriteManager.instance.namedMap.get(name);
            if (live == null) continue; // tile not in this game (its tileset's mod is not enabled)
            IsoSprite ours = marked.get(name);
            if (ours == live && (live.depthFlags & TRANSLUCENT) != 0) continue; // still in place
            if (ours != live && (live.depthFlags & TRANSLUCENT) != 0) {
                marked.remove(name); // vanilla set it (tile geometry "Translucent"): not ours to touch
                continue;
            }
            live.depthFlags = withTranslucent(live.depthFlags);
            marked.put(name, live);
            n++;
        }
        if (witnessName == null || !marked.containsKey(witnessName)) {
            witnessName = marked.isEmpty() ? null : marked.keySet().iterator().next();
        }
        if (n > 0 && before) reapplies++;
        return n;
    }

    /** Clears the bit on the live sprites we marked (mod disabled or failed); vanilla's own bits stay. */
    static void clear() {
        for (Map.Entry<String, IsoSprite> e : marked.entrySet()) {
            IsoSprite s = e.getValue();
            if (IsoSpriteManager.instance.namedMap.get(e.getKey()) == s) s.depthFlags = withoutTranslucent(s.depthFlags);
        }
        marked.clear();
        witnessName = null;
    }

    static int markedCount() {
        return marked.size();
    }

    static int withTranslucent(int flags) {
        return flags | TRANSLUCENT;
    }

    static int withoutTranslucent(int flags) {
        return flags & ~TRANSLUCENT;
    }

    /**
     * Marks every loaded chunk level dirty, so objects are re-classified and the cached chunk textures re-baked
     * (the flag 256 is FBORenderChunk.DIRTY_OBJECT_MODIFY, as used for single objects elsewhere).
     * @return the number of chunks invalidated
     */
    static int invalidateLoadedChunks() {
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return 0;
        Set<IsoChunk> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int p = 0; p < 4; p++) {
            IsoChunkMap map;
            try {
                map = cell.getChunkMap(p);
            } catch (Throwable t) {
                break; // fewer chunk maps than players
            }
            if (map == null || map.ignore) continue;
            IsoChunk[] chunks = map.getChunks();
            if (chunks == null) continue;
            for (IsoChunk c : chunks) {
                if (c == null || !seen.add(c)) continue;
                for (int z = c.getMinLevel(); z <= c.getMaxLevel(); z++) c.invalidateRenderChunkLevel(z, 256L);
            }
        }
        return seen.size();
    }
}
