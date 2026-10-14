package dev.n9ezz.cf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** An isolated controller and fake server: this class has no reference to the live transport. */
final class CfSimulation {
    private CfSimulation() {}

    static String run(Path root, int count) throws IOException {
        if (count < 1 || count > 500) throw new IllegalArgumentException("Число тестовых игр: 1..500");
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "SIM-");
        SimulatedServer fake = new SimulatedServer();
        long[] now = {100_000};
        CfController controller = new CfController(directory, fake, () -> now[0]);
        controller.onChat("/cfbot start " + count);
        String prefix = "/cfbot confirm ";
        String preview = fake.feedback.stream().filter(line -> line.contains(prefix)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Simulation preview missing"));
        controller.onChat(preview.substring(preview.indexOf(prefix)));
        for (int step = 0; step < count * 6 + 20; step++) {
            now[0] += 2000;
            controller.onTick(now[0]);
            while (!fake.commands.isEmpty()) {
                String command = fake.commands.removeFirst();
                if (command.equals("bal")) {
                    controller.onSystemMessage("Ваш баланс: " + String.format(Locale.ROOT, "%,d", fake.coins)
                            + "$ | 0 рейдов | 0 коинов", fake.connection, now[0]);
                } else if (command.equals("cf create 50000")) {
                    fake.creates++;
                    controller.onSystemMessage("┃ Вы успешно создали ставку.", fake.connection, now[0]);
                    // Deterministic fixtures exercise both outcomes; these are not predictions.
                    boolean win = fake.creates % 2 == 1;
                    fake.coins += win ? 42_500 : -50_000;
                    controller.onSystemMessage(win
                            ? "┃ Победа! Вы выиграли 42,500 \uE23A\uF801 (-15%) у ᴋɪɴɢ SimPlayer"
                            : "┃ Поражение! Вы проиграли 50,000 \uE23A\uF801 игроку sᴇɴɪᴏʀ .SimPlayer", fake.connection, now[0]);
                } else throw new IllegalStateException("Unexpected simulated command: " + command);
            }
            if (controller.snapshot().completed() == count && !controller.snapshot().unresolved()) break;
            if (!controller.snapshot().runAllowed() && controller.snapshot().completed() < count)
                throw new IllegalStateException("Simulation paused: " + controller.snapshot().reason());
        }
        if (fake.creates != count || controller.snapshot().completed() != count || controller.snapshot().unresolved())
            throw new IllegalStateException("Simulation did not settle all games");
        Files.writeString(directory.resolve("SIMULATION.txt"), "SYNTHETIC DATA ONLY. Never merge into real state or training.\n");
        return "DRYRUN OK: " + count + " синтетических последовательных игр; реальных команд/ставок 0. Отдельный журнал: simulations/" + directory.getFileName();
    }

    private static final class SimulatedServer implements CfController.Transport {
        final Object connection = new Object();
        final ArrayDeque<String> commands = new ArrayDeque<>();
        final ArrayList<String> feedback = new ArrayList<>();
        long coins = 100_000_000;
        int creates;
        public Object connection() { return connection; }
        public boolean screenOpen() { return false; }
        public void sendCommand(String command) { commands.add(command); }
        public void feedback(String message) { feedback.add(message); }
    }
}
