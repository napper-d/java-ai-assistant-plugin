package edu.teamc.courseaiassistant.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CancellationException;

final class RagFiles {
    private RagFiles() {}
    static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    static String sha256(String text) { return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8))); }
    static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, count); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") ? "pdf" : name.endsWith(".pptx") ? "pptx" : "";
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static void validateVector(float[] vector, int expectedDimensions) {
        if (vector == null || vector.length == 0 || vector.length > 65536
                || (expectedDimensions > 0 && vector.length != expectedDimensions)) {
            throw new IllegalArgumentException("Invalid embedding dimensions.");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding.");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalArgumentException("Zero embedding.");
    }
}
