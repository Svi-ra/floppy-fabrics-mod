package pzmod.realflags;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zombie.ZomboidFileSystem;
import zombie.gameStates.ChooseGameInfo;
import zombie.iso.sprite.IsoSprite;

/**
 * Sprite name → {@link FlagDef}, loaded lazily on first use from every active mod's {@code media/realflags/*.json}
 * (at that point the game has resolved the active mods). Lookups by {@link IsoSprite} are cached by identity, including misses,
 * so the per-draw cost is one identity-map probe. Main thread only.
 */
public final class FlagRegistry {
    static final String FLAGS_PATH = "media/realflags/flags.json";
    private static final Object MISS = new Object();

    private static final Map<String, FlagDef> byName = new HashMap<>();
    /** "_on_" overlay sprite name → the definition of its base sprite (not counted as definitions). */
    private static final Map<String, FlagDef> onAliases = new HashMap<>();
    private static final IdentityHashMap<IsoSprite, Object> bySprite = new IdentityHashMap<>();
    private static boolean loaded;

    private FlagRegistry() {}

    /** The flag definition for a sprite, or null. */
    public static FlagDef forSprite(IsoSprite sprite) {
        if (sprite == null) return null;
        Object hit = bySprite.get(sprite);
        if (hit == null) {
            ensureLoaded();
            String name = sprite.getName();
            FlagDef def = name == null ? null : byName.get(name);
            if (def == null && name != null) def = onAliases.get(name);
            if (def == null) {
                // window curtains are generated from the sprite itself (Curtains, DECISIONS D64)
                Object c = Curtains.defFor(sprite);
                if (c == Curtains.RETRY) return null; // texture not loaded yet: ask again next time, do not cache a miss
                def = (FlagDef) c;
            }
            bySprite.put(sprite, def == null ? MISS : def);
            return def;
        }
        return hit == MISS ? null : (FlagDef) hit;
    }

    public static int size() {
        ensureLoaded();
        return byName.size();
    }

    /**
     * Loads every active mod's {@code media/realflags/*.json} (its {@code common/} and version folders), in mod load
     * order, files sorted by name; for the same sprite a later file wins. This lets other mods add or override flags by
     * data alone (DECISIONS D52). Falls back to the single file the game resolves for {@link #FLAGS_PATH} if the mod
     * list cannot be read.
     */
    static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            List<File> dirs = new ArrayList<>();
            for (String modId : ZomboidFileSystem.instance.getModIDs()) {
                ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails(modId);
                if (mod == null) continue;
                if (mod.getCommonDir() != null) dirs.add(new File(mod.getCommonDir(), "media/realflags"));
                if (mod.getVersionDir() != null) dirs.add(new File(mod.getVersionDir(), "media/realflags"));
            }
            List<File> files = collectJsonFiles(dirs);
            if (files.isEmpty()) {
                String path = ZomboidFileSystem.instance.getString(FLAGS_PATH);
                if (new File(path).isFile()) files.add(new File(path));
            }
            if (files.isEmpty()) {
                RealFlags.warn("no media/realflags/*.json found in any active mod; no flags registered");
                return;
            }
            int total = 0;
            for (File f : files) {
                try {
                    total += loadJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), f.getPath());
                } catch (Throwable t) {
                    // one broken file never takes the others down
                    RealFlags.warn("could not read " + f + ": " + t);
                }
            }
            RealFlags.log("loaded " + byName.size() + " flag definitions (" + total + " entries) from " + files.size() + " file(s)");
            preloadTextures();
        } catch (Throwable t) {
            // a broken data setup disables nothing else: the mod simply has no flags
            RealFlags.warn("could not load flag definitions: " + t);
        }
    }

    /** All {@code *.json} files directly in each existing directory, directories in the given order, files by name. */
    static List<File> collectJsonFiles(List<File> dirs) {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File d : dirs) {
            File[] fs = d.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (fs == null) continue;
            Arrays.sort(fs, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            for (File f : fs) {
                if (f.isFile() && seen.add(f.getAbsolutePath())) out.add(f);
            }
        }
        return out;
    }

    /** Parses one flags.json document; returns the number of entries added. Bad entries are logged and skipped. */
    static int loadJson(String text, String source) {
        FlagJson.Parsed parsed = FlagJson.parse(text, source);
        for (String err : parsed.errors()) RealFlags.warn(err + " — entry skipped");
        for (FlagDef def : parsed.flags()) {
            FlagDef prev = byName.put(def.sprite, def);
            if (prev != null) RealFlags.log(source + ": " + def.sprite + " overrides entry from " + prev.source);
        }
        // a lit light draws its "<tileset>_on_<index>" sprite on top (IsoObject.renderAnimatedAttachments, D84): the same
        // definition bends it, through the same object's instance, so the glow follows the bulbs
        for (FlagDef def : parsed.flags()) {
            String on = onSpriteName(def.sprite);
            if (on != null && !byName.containsKey(on)) onAliases.put(on, def);
        }
        bySprite.clear();
        return parsed.flags().size();
    }

    /**
     * Requests canvas and static-quad textures as soon as the definitions are loaded. Texture loading is asynchronous,
     * so this gives them a head start: in Phase 4 the pole drew vanilla's furled flag for ~20 frames on first sight
     * while the canvas was still loading ({@code fallbacks canvas=21}).
     */
    private static void preloadTextures() {
        for (FlagDef def : byName.values()) {
            if (def.kind != FlagDef.Kind.POLE) continue;
            Canvases.get(def.canvasTexture);
            for (FlagDef.StaticQuad q : def.staticQuads) if (q.sprite() != null) Canvases.sprite(q.sprite());
        }
    }

    /** "tileset_12" → "tileset_on_12" (vanilla's lit-overlay naming, IsoWorld.java), or null. */
    static String onSpriteName(String sprite) {
        int u = sprite.lastIndexOf('_');
        if (u <= 0 || u == sprite.length() - 1) return null;
        for (int i = u + 1; i < sprite.length(); i++) if (!Character.isDigit(sprite.charAt(i))) return null;
        return sprite.substring(0, u) + "_on" + sprite.substring(u);
    }

    /** The definition an "_on_" overlay sprite is drawn with (tests). */
    static FlagDef byOnAlias(String sprite) {
        ensureLoaded();
        return onAliases.get(sprite);
    }

    /** Looks a definition up by sprite name (tests, diagnostics). */
    static FlagDef byName(String sprite) {
        ensureLoaded();
        return byName.get(sprite);
    }

    /** Test hook: forget everything. */
    static synchronized void resetForTests() {
        byName.clear();
        onAliases.clear();
        bySprite.clear();
        loaded = true;
    }
}
