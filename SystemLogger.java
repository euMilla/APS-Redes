package aps.shared.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class SystemLogger {

    private static final Logger LOGGER = Logger.getLogger("aps-redes");

    private SystemLogger() {
    }

    public static synchronized void configure(Path storageDir) {
        Path logsDir = storageDir == null ? Path.of("server-storage", "logs") : storageDir.resolve("logs");
        try {
            Files.createDirectories(logsDir);
            for (Handler handler : LOGGER.getHandlers()) {
                LOGGER.removeHandler(handler);
                handler.close();
            }
            FileHandler fileHandler = new FileHandler(logsDir.resolve("aps-system.log").toString(), true);
            fileHandler.setFormatter(new SimpleFormatter());
            LOGGER.addHandler(fileHandler);
            LOGGER.setUseParentHandlers(true);
            LOGGER.setLevel(Level.INFO);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Nao foi possivel configurar log em arquivo: " + exception.getMessage(), exception);
        }
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warning(message);
    }

    public static void error(String message, Throwable throwable) {
        LOGGER.log(Level.SEVERE, message, throwable);
    }

    public static synchronized void close() {
        for (Handler handler : LOGGER.getHandlers()) {
            LOGGER.removeHandler(handler);
            handler.close();
        }
    }
}
