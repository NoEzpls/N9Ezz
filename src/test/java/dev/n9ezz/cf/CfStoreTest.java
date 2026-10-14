package dev.n9ezz.cf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;

/** Dependency-free persistence regression tests using synthetic data only. */
public final class CfStoreTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        System.out.println("CfStoreTest: " + run() + " checks passed");
    }

    public static int run() throws Exception {
        checks = 0;
        Path root = Files.createTempDirectory("cf-store-test-");
        try {
            freshAndCrashPending(root.resolve("fresh"));
            legacyMetadataAndBackup(root.resolve("legacy"));
            corruptFilesRemainUntouched(root.resolve("corrupt"));
            rejectInvalidCommit(root.resolve("invalid"));
            atomicFailureKeepsMemory(root.resolve("atomic"));
            logEscapingAndRotation(root.resolve("logs"));
            return checks;
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void freshAndCrashPending(Path directory) throws Exception {
        CfStore store = new CfStore(directory);
        check(store.state().equals(new CfStore.State(false, 0, 0)), "fresh state inactive");
        check("0.2.2".equals(store.metadata().getProperty("format.version")), "format version saved");
        store.commit(new CfStore.State(true, 3, 4));
        CfStore recovered = new CfStore(directory);
        check(recovered.state().unresolved(), "crash/reopen preserves pending bet");
        check(recovered.state().wins() == 3 && recovered.state().losses() == 4, "counter roundtrip");
        check(!Files.exists(directory.resolve("state.0.2.1.bak")), "fresh install has no fake legacy backup");
    }

    private static void legacyMetadataAndBackup(Path directory) throws Exception {
        Files.createDirectories(directory);
        String legacy = "# synthetic legacy fixture\nunresolved=true\nwins=9\nlosses=10\n"
                + "nn.weight.0=0.125\nnp.unknown=preserved\nprefs.goal=10000000\n"
                + "prefs.maxStake=50000\nmodel.scope=RaidMine-monety\n";
        Path stateFile = directory.resolve("state.properties");
        Files.writeString(stateFile, legacy, StandardCharsets.UTF_8);
        CfStore store = new CfStore(directory);
        check(store.state().unresolved(), "legacy pending marker retained");
        check(Files.readString(directory.resolve("state.0.2.1.bak")).equals(legacy), "exact migration backup");
        check(Files.readString(stateFile).equals(legacy), "loading legacy does not rewrite state");
        Properties metadata = store.metadata();
        check("0.125".equals(metadata.getProperty("nn.weight.0")), "legacy model weight retained");
        check("preserved".equals(metadata.getProperty("np.unknown")), "unknown extension retained");
        check(!metadata.containsKey("wins") && !metadata.containsKey("unresolved"), "core separated from metadata");
        metadata.setProperty("session.phase", "VERIFYING");
        store.commit(new CfStore.State(true, 9, 10), metadata);
        metadata.setProperty("session.phase", "MUTATED_AFTER_COMMIT");
        check("VERIFYING".equals(store.metadata().getProperty("session.phase")), "commit copies metadata");
        Properties exposed = store.metadata();
        exposed.setProperty("nn.weight.0", "999");
        check("0.125".equals(store.metadata().getProperty("nn.weight.0")), "metadata accessor defensive copy");
        CfStore recovered = new CfStore(directory);
        check("VERIFYING".equals(recovered.metadata().getProperty("session.phase")), "session metadata roundtrip");
        check("10000000".equals(recovered.metadata().getProperty("prefs.goal")), "legacy settings preserved");
        check("RaidMine-monety".equals(recovered.metadata().getProperty("model.scope")), "legacy scope preserved");
        check("preserved".equals(recovered.metadata().getProperty("np.unknown")), "unknown key survives rewrite");
        check(Files.readString(directory.resolve("state.0.2.1.bak")).equals(legacy), "backup unchanged after upgrade");
        Files.writeString(stateFile, legacy.replace("wins=9", "wins=11"));
        new CfStore(directory);
        check(Files.readString(directory.resolve("state.0.2.1.bak")).equals(legacy), "backup not overwritten on repeated legacy load");
    }

    private static void corruptFilesRemainUntouched(Path root) throws Exception {
        String[] corrupted = {
                "unresolved=TRUE\nwins=0\nlosses=0\n",
                "unresolved=garbage\nwins=0\nlosses=0\n",
                "wins=0\nlosses=0\n",
                "unresolved=true\nwins=-1\nlosses=0\n",
                "unresolved=true\nwins=+1\nlosses=0\n",
                "unresolved=true\nwins=1.5\nlosses=0\n",
                "unresolved=true\nwins=9223372036854775808\nlosses=0\n",
                "unresolved=true\nwins=9223372036854775807\nlosses=1\n",
                "unresolved=true\nwins=0\n",
                "unresolved=true\nwins=0\nlosses=0\nextra=\\uZZZZ\n"
        };
        for (int i = 0; i < corrupted.length; i++) {
            Path directory = root.resolve(Integer.toString(i));
            Files.createDirectories(directory);
            Path stateFile = directory.resolve("state.properties");
            byte[] original = corrupted[i].getBytes(StandardCharsets.UTF_8);
            Files.write(stateFile, original);
            expect(IOException.class, () -> new CfStore(directory), "corrupt state blocks load " + i);
            check(Arrays.equals(original, Files.readAllBytes(stateFile)), "corruption not overwritten " + i);
            check(!Files.exists(directory.resolve("state.0.2.1.bak")), "corrupt state not blessed as migration backup " + i);
        }
    }

    private static void rejectInvalidCommit(Path directory) throws Exception {
        CfStore store = new CfStore(directory);
        byte[] original = Files.readAllBytes(directory.resolve("state.properties"));
        expect(IllegalArgumentException.class, () -> new CfStore.State(false, -1, 0), "negative wins rejected");
        expect(IllegalArgumentException.class, () -> new CfStore.State(false, 0, -1), "negative losses rejected");
        expect(ArithmeticException.class, () -> new CfStore.State(false, Long.MAX_VALUE, 1), "sum overflow rejected");
        Properties mixed = new Properties();
        mixed.put("nn.weight.0", 1.0);
        expect(IllegalArgumentException.class, () -> store.commit(new CfStore.State(true, 0, 0), mixed), "non-string metadata rejected");
        Properties core = new Properties();
        core.setProperty("unresolved", "false");
        expect(IllegalArgumentException.class, () -> store.commit(new CfStore.State(true, 0, 0), core), "core cannot be replaced through metadata");
        check(Arrays.equals(original, Files.readAllBytes(directory.resolve("state.properties"))), "invalid commit leaves disk untouched");
        check(!store.state().unresolved(), "invalid commit leaves memory untouched");
    }

    private static void atomicFailureKeepsMemory(Path directory) throws Exception {
        CfStore store = new CfStore(directory);
        Path stateFile = directory.resolve("state.properties");
        Path saved = directory.resolve("saved-state");
        Files.move(stateFile, saved);
        Files.createDirectory(stateFile);
        try {
            expect(IOException.class, () -> store.commit(new CfStore.State(true, 1, 2)), "failed replacement propagates IO error");
            check(store.state().equals(new CfStore.State(false, 0, 0)), "failed replacement leaves in-memory state untouched");
            try (var files = Files.list(directory)) {
                check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "failed replacement cleans temp file");
            }
        } finally {
            Files.delete(stateFile);
            Files.move(saved, stateFile);
        }
        check(new CfStore(directory).state().equals(new CfStore.State(false, 0, 0)), "previous durable state remains readable");
    }

    private static void logEscapingAndRotation(Path directory) throws Exception {
        CfStore store = new CfStore(directory);
        store.log("STATE\tCHANGE", "ACTIVE\nPAUSED\r\\why", null, "Sample\tPlayer");
        Path log = directory.resolve("messages.tsv");
        var lines = Files.readAllLines(log);
        check(lines.size() == 2, "embedded newlines do not create log records");
        String[] fields = lines.get(1).split("\t", -1);
        check(fields.length == 5, "TSV retains five columns");
        check(fields[1].equals("STATE\\tCHANGE"), "tab escaped in type");
        check(fields[2].equals("ACTIVE\\nPAUSED\\r\\\\why"), "line and slash escaping");
        check(fields[3].isEmpty(), "null amount is empty");
        check(fields[4].equals("Sample\\tPlayer"), "opponent tab escaped");
        try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
            channel.position(8L * 1024 * 1024 - 1);
            channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
        }
        store.log("OBSERVED_CANDIDATE", "Synthetic public chat", "", "");
        check(Files.size(directory.resolve("messages.previous.tsv")) == 8L * 1024 * 1024, "8 MiB rotation preserved previous file");
        var rotated = Files.readAllLines(log);
        check(rotated.size() == 2 && rotated.get(0).startsWith("time_utc\t"), "rotated log has header and one record");
        check(rotated.get(1).contains("\tOBSERVED_CANDIDATE\t"), "candidate stays diagnostic event");
        check(store.state().wins() == 0 && store.state().losses() == 0, "logging never counts observed outcome");
        Files.delete(log);
        Files.createDirectory(log);
        expect(IOException.class, () -> store.log("STATE", "failure", "", ""), "log IO failure propagates");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static void expect(Class<? extends Throwable> type, Checked action, String description) throws Exception {
        checks++;
        try {
            action.run();
        } catch (Throwable actual) {
            if (type.isInstance(actual)) return;
            throw new AssertionError(description + ": wrong exception", actual);
        }
        throw new AssertionError(description + ": no exception");
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
