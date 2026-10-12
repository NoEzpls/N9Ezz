package dev.n9ezz.cf;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;

/** Local-only journal and crash marker. No uploads, account data, or server edits. */
final class CfStore {
    record State(boolean unresolved, long wins, long losses) { }
    private final Path directory;
    private State state;
    private Properties metadata = new Properties();

    CfStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Path path = directory.resolve("state.properties");
        if (!Files.exists(path)) {
            state = new State(false, 0, 0);
            commit(state);
        } else {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
            try {
                String pending = properties.getProperty("unresolved");
                if (!"true".equals(pending) && !"false".equals(pending)) throw new IllegalArgumentException();
                long wins = Long.parseLong(properties.getProperty("wins"));
                long losses = Long.parseLong(properties.getProperty("losses"));
                if (wins < 0 || losses < 0) throw new IllegalArgumentException();
                Math.addExact(wins, losses);
                state = new State(Boolean.parseBoolean(pending), wins, losses);
                for (String key : properties.stringPropertyNames()) {
                    if (key.startsWith("nn.") || key.startsWith("prefs.") || key.equals("model.scope"))
                        metadata.setProperty(key, properties.getProperty(key));
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Повреждено состояние CF; автоматическая игра заблокирована", invalid);
            }
        }
    }

    State state() { return state; }
    Properties metadata() {
        Properties copy = new Properties();
        copy.putAll(metadata);
        return copy;
    }

    void commit(State next) throws IOException {
        commit(next, metadata);
    }

    /** Outcome totals, learned weights and pending marker must commit together. */
    void commit(State next, Properties nextMetadata) throws IOException {
        Properties properties = new Properties();
        for (String key : nextMetadata.stringPropertyNames()) {
            if (!(key.startsWith("nn.") || key.startsWith("prefs.") || key.equals("model.scope")))
                throw new IllegalArgumentException("Unsupported state metadata: " + key);
            properties.setProperty(key, nextMetadata.getProperty(key));
        }
        properties.setProperty("unresolved", Boolean.toString(next.unresolved()));
        properties.setProperty("wins", Long.toString(next.wins()));
        properties.setProperty("losses", Long.toString(next.losses()));
        StringWriter writer = new StringWriter();
        properties.store(writer, "CF assistant: unresolved must be saved BEFORE sending a command");
        Path temporary = Files.createTempFile(directory, "state-", ".tmp");
        try {
            // Force the file before atomic replacement: a process crash must not
            // turn an already sent stake into an automatic retry after restart.
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(writer.toString());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, directory.resolve("state.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = next;
            metadata = new Properties();
            metadata.putAll(nextMetadata);
        } finally { Files.deleteIfExists(temporary); }
    }

    void log(String type, String message, String amount, String opponent) throws IOException {
        Path path = directory.resolve("messages.tsv");
        // Keep one previous 8 MiB segment. The confirmed totals live separately.
        if (Files.exists(path) && Files.size(path) >= 8 * 1024 * 1024) {
            Files.move(path, directory.resolve("messages.previous.tsv"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!Files.exists(path)) Files.writeString(path, "time_utc\ttype\tmessage\treported_amount\topponent\n", StandardOpenOption.CREATE_NEW);
        Files.writeString(path, Instant.now() + "\t" + escape(type) + "\t" + escape(message)
                + "\t" + escape(amount) + "\t" + escape(opponent) + "\n", StandardOpenOption.APPEND);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}
