package io.github.nicolasgoyon.folkhold.world.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The client options of the world mod in Confee. Each test loads the config in its own config folder, with no
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

    @Test
    void theFileIsTheFileOfTheNeoForgeClientConfig(@TempDir Path dir) {
        assertEquals(dir.resolve("folkhold_world-client.toml"), load(dir).schema().filePath());
    }

    @Test
    void aMissingFileGivesTheDefaultsAndIsCreated(@TempDir Path dir) throws Exception {
        ClientConfig values = load(dir).get();
        assertTrue(values.showTerritoryHud);
        assertTrue(values.showQuestHud);
        String text = Files.readString(dir.resolve("folkhold_world-client.toml"), StandardCharsets.UTF_8);
        assertTrue(text.contains("showTerritoryHud = true"), text);
        assertTrue(text.contains("showQuestHud = true"), text);
        assertFalse(text.contains("serverOptions"), text);
    }

    @Test
    void theValuesOfAFileOfTheNeoForgeFormatStay(@TempDir Path dir) throws Exception {
        // The file that the NeoForge ModConfigSpec of the same options writes.
        Files.writeString(dir.resolve("folkhold_world-client.toml"), """
                #Show the territory HUD at the top right of the screen: the faction that owns the
                #territory, your reputation tier and the village near you.
                showTerritoryHud = false
                #Show the quest HUD at the top left of the screen: your active quests and the minutes
                #before their deadline.
                showQuestHud = false
                """, StandardCharsets.UTF_8);
        ConfigHandle<ClientConfig> handle = load(dir);
        assertFalse(handle.get().showTerritoryHud);
        assertFalse(handle.get().showQuestHud);

        handle.get().showQuestHud = true;
        handle.save();
        ClientConfig values = load(dir).get();
        assertFalse(values.showTerritoryHud);
        assertTrue(values.showQuestHud);
    }

    @Test
    void theDefaultsApplyBeforeTheRegistration() {
        assertTrue(new ClientConfig().showTerritoryHud);
        assertTrue(new ClientConfig().showQuestHud);
    }
}
