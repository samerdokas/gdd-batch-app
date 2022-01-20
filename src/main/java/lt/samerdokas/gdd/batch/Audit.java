package lt.samerdokas.gdd.batch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

class Audit implements BiConsumer<String, JsonObject>, Function<String, Optional<JsonObject>> {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    
    private final Path auditPath;
    
    Audit(Path downloadsPath) {
        auditPath = downloadsPath.resolve(".audit");
    }
    
    @Override
    public void accept(String url, JsonObject jsonObject) {
        try {
            Files.createDirectories(auditPath);
            try (Writer out = Files.newBufferedWriter(fileOf(url), StandardCharsets.UTF_8)) {
                JsonWriter writer = GSON.newJsonWriter(out);
                writer.setIndent("  ");
                GSON.toJson(jsonObject, writer);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
    
    @Override
    public Optional<JsonObject> apply(String url) {
        try (Reader in = Files.newBufferedReader(fileOf(url), StandardCharsets.UTF_8)) {
            return Optional.of(JsonParser.parseReader(in).getAsJsonObject());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
    
    private Path fileOf(String url) {
        return auditPath.resolve(hashOf(url) + ".json");
    }
    
    private String hashOf(String url) {
        StringBuilder result = new StringBuilder(256 >>> 3);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            for (byte b : digest.digest(url.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format(Locale.ROOT, "%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
