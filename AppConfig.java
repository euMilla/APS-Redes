package aps.shared.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class AppConfig {

    private static final Path DEFAULT_ENV_PATH = Path.of(".env");
    private static final Path DEFAULT_CONFIG_DIR = Path.of("config");
    private static volatile AppConfig current;

    private final Map<String, String> fileValues;

    private AppConfig(Map<String, String> fileValues) {
        this.fileValues = Map.copyOf(fileValues);
    }

    public static AppConfig current() {
        AppConfig snapshot = current;
        if (snapshot == null) {
            synchronized (AppConfig.class) {
                snapshot = current;
                if (snapshot == null) {
                    snapshot = loadDefault();
                    current = snapshot;
                }
            }
        }
        return snapshot;
    }

    public static AppConfig load(Path path) {
        return loadAll(path);
    }

    public static AppConfig loadDefault() {
        String configDir = System.getProperty("APS_CONFIG_DIR");
        Path baseDir = configDir == null || configDir.isBlank() ? DEFAULT_CONFIG_DIR : Path.of(configDir.trim());
        Path app = baseDir.resolve("app.properties");
        Path database = baseDir.resolve("database.properties");
        if (!Files.exists(app) && !Files.exists(database)) {
            return loadAll(DEFAULT_ENV_PATH);
        }
        return loadAll(
                app,
                database
        );
    }

    public static AppConfig loadAll(Path... paths) {
        Map<String, String> values = new HashMap<>();
        Arrays.stream(paths == null ? new Path[0] : paths)
                .forEach(path -> readInto(values, path));
        return new AppConfig(values);
    }

    public String get(String key, String fallback) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String fileValue = fileValues.get(key);
        if (fileValue != null && !fileValue.isBlank()) {
            return fileValue.trim();
        }
        return fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, Boolean.toString(fallback));
        return "1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "sim".equalsIgnoreCase(value);
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static void readInto(Map<String, String> values, Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = stripQuotes(line.substring(equals + 1).trim());
                if (!key.isBlank() && (!value.isBlank() || !values.containsKey(key))) {
                    values.put(key, value);
                }
            }
        } catch (IOException ignored) {
            // Configuracao local e melhor esforco; chamadas get(...) ainda possuem fallback.
        }
    }
}
