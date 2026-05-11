package aps.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void loadsEnvFileValues() throws Exception {
        Path file = Files.createTempFile("aps-redes", ".env");
        Files.writeString(file, """
                # comentario
                APS_TEST_BOOL=true
                APS_TEST_USER=aps_user
                APS_TEST_PORT=587
                """, StandardCharsets.UTF_8);

        AppConfig config = AppConfig.load(file);

        assertTrue(config.getBoolean("APS_TEST_BOOL", false));
        assertEquals("aps_user", config.get("APS_TEST_USER", ""));
        assertEquals(587, config.getInt("APS_TEST_PORT", 0));
    }

    @Test
    void laterConfigFilesOverrideEarlierFiles() throws Exception {
        Path app = Files.createTempFile("aps-app", ".properties");
        Path database = Files.createTempFile("aps-db", ".properties");
        Files.writeString(app, """
                DB_ENABLED=false
                APS_CHAT_PORT=5050
                """, StandardCharsets.UTF_8);
        Files.writeString(database, """
                DB_ENABLED=true
                DB_USER=aps_user
                """, StandardCharsets.UTF_8);

        AppConfig config = AppConfig.loadAll(app, database);

        assertTrue(config.getBoolean("DB_ENABLED", false));
        assertEquals("aps_user", config.get("DB_USER", ""));
        assertEquals(5050, config.getInt("APS_CHAT_PORT", 0));
    }
}
