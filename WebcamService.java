package aps.client.service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import com.github.sarxos.webcam.Webcam;

public final class WebcamService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webcam-capture");
        thread.setDaemon(true);
        return thread;
    });

    public CompletableFuture<byte[]> capturePng() {
        return CompletableFuture.supplyAsync(() -> {
            Webcam webcam = null;
            try {
                webcam = Webcam.getDefault();
                if (webcam == null) return fallbackImage("Nenhuma webcam detectada");
                chooseResolution(webcam);
                if (!webcam.isOpen()) webcam.open(true);
                BufferedImage image = webcam.getImage();
                if (image == null) return fallbackImage("Webcam sem imagem");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } catch (Throwable exception) {
                return fallbackImage("Webcam indisponivel: " + safeMessage(exception));
            } finally {
                if (webcam != null && webcam.isOpen()) webcam.close();
            }
        }, executor);
    }

    public void shutdown() { executor.shutdownNow(); }

    // -------------------------------------------------------------------------

    private static void chooseResolution(Webcam webcam) {
        Dimension[] sizes = webcam.getViewSizes();
        if (sizes == null || sizes.length == 0) return;
        Arrays.stream(sizes)
              .filter(s -> s.width <= 1280 && s.height <= 720)
              .max(Comparator.comparingInt(s -> s.width * s.height))
              .ifPresent(webcam::setViewSize);
    }

    private static byte[] fallbackImage(String reason) {
        try {
            BufferedImage img = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(9, 14, 22));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setColor(new Color(65, 242, 213));
            g.fillRoundRect(48, 48, 864, 444, 28, 28);
            g.setColor(new Color(13, 23, 33));
            g.fillRoundRect(62, 62, 836, 416, 22, 22);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 28));
            g.drawString("Foto de vistoria — modo demonstracao", 100, 160);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 20));
            g.drawString(reason, 100, 210);
            g.setColor(new Color(65, 242, 213));
            g.drawString("Gerado: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")), 100, 255);
            g.setColor(new Color(150, 200, 200));
            g.drawString("A captura real sera usada quando a webcam estiver disponivel.", 100, 310);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao gerar imagem demonstrativa: " + ex.getMessage(), ex);
        }
    }

    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) return ex.getClass().getSimpleName();
        return msg.substring(0, Math.min(msg.length(), 80));
    }
}