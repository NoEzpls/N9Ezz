package dev.n9ezz.cf;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Persistent compatibility layer reconstructed from the supplied 0.2.1 bytecode. */
final class CfStore {
    private static final Set<String> CORE_KEYS = Set.of("unresolved", "wins", "losses");
    private static final String FORMAT_VERSION = "0.2.2";
    private static final long LOG_ROTATION_BYTES = 8L * 1024 * 1024;
    private static final String LOG_HEADER = "time_utc\ttype\tmessage\treported_amount\topponent\n";
    private final Path directory;
    private State state;
    private Properties metadata = new Properties();

    CfStore(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory");
        Files.createDirectories(directory);
        Path stateFile = directory.resolve("state.properties");
        if (!Files.exists(stateFile)) {
            // A new installation starts inactive. No recovered metadata can enable a run.
            commit(new State(false, 0L, 0L));
            return;
        }

        Properties loaded = new Properties();
        try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            loaded.load(reader);
            String unresolved = loaded.getProperty("unresolved");
            if (!"true".equals(unresolved) && !"false".equals(unresolved)) {
                throw new IllegalArgumentException("unresolved must be exactly true or false");
            }
            state = new State(Boolean.parseBoolean(unresolved),
                    parseCounter(loaded, "wins"), parseCounter(loaded, "losses"));
            metadata = copyMetadata(loaded, false);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            // Never overwrite an unreadable pending marker with a seemingly clean state.
            throw new IOException("Повреждено состояние CF; автоматическая игра заблокирована", failure);
        }

        if (!loaded.containsKey("format.version")) {
            // Preserve the exact original bytes once, before the first 0.2.2 write.
            Path backup = directory.resolve("state.0.2.1.bak");
            try {
                Files.copy(stateFile, backup);
                try (FileChannel channel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            } catch (FileAlreadyExistsException ignored) {
                // Subsequent loads must not replace the original migration backup.
            }
        }
    }

    synchronized State state() {
        return state;
    }

    synchronized Properties metadata() {
        Properties copy = new Properties();
        copy.putAll(metadata);
        return copy;
    }

    synchronized void commit(State newState) throws IOException {
        commit(newState, metadata);
    }

    synchronized void commit(State newState, Properties newMetadata) throws IOException {
        Objects.requireNonNull(newState, "state");
        Properties nextMetadata = copyMetadata(Objects.requireNonNull(newMetadata, "metadata"), true);
        nextMetadata.setProperty("format.version", FORMAT_VERSION);
        Properties serialized = new Properties();
        serialized.putAll(nextMetadata);
        serialized.setProperty("unresolved", Boolean.toString(newState.unresolved()));
        serialized.setProperty("wins", Long.toString(newState.wins()));
        serialized.setProperty("losses", Long.toString(newState.losses()));
        StringWriter output = new StringWriter();
        serialized.store(output, "CF assistant: unresolved must be saved BEFORE sending a command");

        Path temporary = Files.createTempFile(directory, "state-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(output.toString());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            // A failed atomic replacement is fatal to this action. In particular, there is
            // no delete/rewrite fallback which could lose the unresolved flag on a crash.
            Files.move(temporary, directory.resolve("state.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = newState;
            metadata = nextMetadata;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized void log(String type, String message, String amount, String opponent) throws IOException {
        Path logFile = directory.resolve("messages.tsv");
        if (Files.exists(logFile) && Files.size(logFile) >= LOG_ROTATION_BYTES) {
            Files.move(logFile, directory.resolve("messages.previous.tsv"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.exists(logFile)) {
            Files.writeString(logFile, LOG_HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        String row = Instant.now() + "\t" + escape(type) + "\t" + escape(message)
                + "\t" + escape(amount) + "\t" + escape(opponent) + "\n";
        // Keep the five-column 0.2.1 schema; transition detail belongs in message.
        // Errors intentionally escape to the controller so it can block new sends.
        Files.writeString(logFile, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static long parseCounter(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid counter: " + key);
        }
        return Long.parseLong(value);
    }

    private static Properties copyMetadata(Properties source, boolean rejectCore) {
        Properties copy = new Properties();
        // Properties may be passed by callers; reject non-string entries instead of
        // silently dropping data. String defaults are also copied via stringPropertyNames.
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("State metadata must contain only strings");
            }
        }
        for (String key : source.stringPropertyNames()) {
            if (CORE_KEYS.contains(key)) {
                if (rejectCore) {
                    throw new IllegalArgumentException("Core state is not metadata: " + key);
                }
                continue;
            }
            // Retain old nn.*, prefs.*, model.scope and unknown extension keys. The
            // controller decides which preferences are usable; storage is lossless.
            copy.setProperty(key, source.getProperty(key));
        }
        return copy;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    record State(boolean unresolved, long wins, long losses) {
        State {
            if (wins < 0 || losses < 0) {
                throw new IllegalArgumentException("Negative game counter");
            }
            Math.addExact(wins, losses);
        }
    }
}
