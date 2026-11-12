package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Other mods add or override flags by shipping media/floppyfabrics/*.json (D52). */
class FlagMergeTest {
    @TempDir
    Path tmp;

    @BeforeEach
    void reset() {
        FlagRegistry.resetForTests();
    }

    private static String wall(String sprite, int x) {
        return "{\"sprite\":\"" + sprite + "\",\"kind\":\"wall\",\"cloth\":{\"x\":" + x + ",\"y\":10,\"w\":40,\"h\":60}}";
    }

    @Test
    void filesAreCollectedInModOrderThenByNameAndLaterOverrides() throws Exception {
        Path ours = Files.createDirectories(tmp.resolve("FloppyFabrics/42/media/floppyfabrics"));
        Path other = Files.createDirectories(tmp.resolve("OtherFlags/common/media/floppyfabrics"));
        Files.writeString(ours.resolve("flags.json"), "{\"flags\":[" + wall("a_0", 1) + "," + wall("b_0", 2) + "]}", StandardCharsets.UTF_8);
        Files.writeString(other.resolve("b_more.json"), "{\"flags\":[" + wall("c_0", 3) + "]}", StandardCharsets.UTF_8);
        Files.writeString(other.resolve("a_override.json"), "{\"flags\":[" + wall("a_0", 9) + "]}", StandardCharsets.UTF_8);
        Files.writeString(other.resolve("readme.txt"), "not json", StandardCharsets.UTF_8);

        List<File> files = FlagRegistry.collectJsonFiles(List.of(
                ours.toFile(), tmp.resolve("missing/dir").toFile(), other.toFile()));
        assertEquals(List.of("flags.json", "a_override.json", "b_more.json"), files.stream().map(File::getName).toList());

        for (File f : files) FlagRegistry.loadJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), f.getPath());
        assertEquals(3, FlagRegistry.size());
        assertEquals(9f, FlagRegistry.byName("a_0").clothX, "the later mod's entry wins");
        assertNotNull(FlagRegistry.byName("c_0"));
    }
}
