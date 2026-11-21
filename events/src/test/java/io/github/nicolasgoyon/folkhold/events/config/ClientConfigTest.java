package io.github.nicolasgoyon.folkhold.events.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.heetstudio.confee.core.AnnotationConfigs;
import fr.heetstudio.confee.core.ConfeeEnvironment;
import fr.heetstudio.confee.core.ConfigHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client options of the events mod in Confee. Each test loads the config in its own config folder, with no
 * registration. {@code AnnotationConfigs} and {@code ConfeeEnvironment} are internal classes of Confee: the version of
 * Confee is pinned in {@code gradle.properties}.
 */
class ClientConfigTest {

    private static ConfigHandle<ClientConfig> load(Path dir) {
        ConfigHandle<ClientConfig> handle = AnnotationConfigs.create(ClientConfig.class,
                new ConfeeEnvironment(dir, true, true));
        handle.load();
        return handle;
    }

    private static double cameraShake(Path dir, String value) throws Exception {
        // The file that the NeoForge ModConfigSpec of the same option writes.
        Files.writeString(dir.resolve("folkhold_events-client.toml"), """
                #Strength of the camera shake during an earthquake. 1 is the full shake. 0 turns off the
                #shake.
                # Default: 1.0
                # Range: 0.0 ~ 1.0
                cameraShake = %s
                """.formatted(value), StandardCharsets.UTF_8);
        return load(dir).get().cameraShake;
    }

    @Test
    void theFileIsTheFileOfTheNeoForgeClientConfig(@TempDir Path dir) {
        assertEquals(dir.resolve("folkhold_events-client.toml"), load(dir).schema().filePath());
    }

    @Test
    void aMissingFileGivesTheDefault(@TempDir Path dir) throws Exception {
        assertEquals(1.0, load(dir).get().cameraShake);
        String text = Files.readString(dir.resolve("folkhold_events-client.toml"), StandardCharsets.UTF_8);
        assertTrue(text.contains("cameraShake = 1.0"), text);
    }

    @Test
    void theValueOfAFileOfTheNeoForgeFormatStays(@TempDir Path dir) throws Exception {
        assertEquals(0.37, cameraShake(dir, "0.37"));
        assertEquals(0.0, cameraShake(dir, "0.0"));
    }

    @Test
    void aValueOutOfTheRangeIsClamped(@TempDir Path dir) throws Exception {
        assertEquals(1.0, cameraShake(dir, "2.5"));
        assertEquals(0.0, cameraShake(dir, "-1.0"));
    }

    @Test
    void theDefaultAppliesBeforeTheRegistration() {
        assertEquals(1.0, ClientConfig.get().cameraShake);
    }
}
