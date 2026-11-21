package pzmod.floppyfabrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The version in the status line (bug reports) must match the shipped mod.info. */
class VersionTest {
    private static final Path MOD_INFO =
            Path.of(System.getProperty("floppyfabrics.modDir", "../mod"), "42", "mod.info");

    @Test
    void versionMatchesModInfo() throws Exception {
        String modversion = Files.readAllLines(MOD_INFO, StandardCharsets.UTF_8).stream()
                .filter(l -> l.startsWith("modversion="))
                .map(l -> l.substring("modversion=".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no modversion= in " + MOD_INFO));
        assertEquals(modversion, FloppyFabrics.VERSION, "bump FloppyFabrics.VERSION together with mod.info");
    }
}
