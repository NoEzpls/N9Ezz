package dev.n9ezz.cf;

import java.util.Properties;
import java.util.Random;

/** Compatibility checks for the model restored from the supplied 0.2.1 JAR. */
public final class CfRestoredModelTest {
    public static void main(String[] args) {
        var model = new CfNeuralModel();
        var random = new Random(221022L);
        for (int i = 0; i < 1200; i++) {
            if (i == 500) model.breakSequence();
            model.observe(random.nextBoolean());
        }
        var prediction = model.predict();
        check(prediction.trainedSamples() == 1200, "training sample count");
        check(prediction.validationSamples() == 992, "validation after sequence reset");
        // Reference values were evaluated directly with the supplied 0.2.1 bytecode.
        // A tolerance avoids false failures from platform-specific transcendental math.
        check(Math.abs(prediction.probability() - 0.4691279706821143) < 1e-12,
                "restored prediction differs from 0.2.1");
        check(Math.abs(prediction.brier() - 0.25378460276501985) < 1e-12,
                "restored validation differs from 0.2.1");
        check(!prediction.usable(), "random fixture must not advertise validated advantage");
        Properties saved = model.save();
        var restored = CfNeuralModel.fromProperties(saved);
        check(saved.equals(restored.save()), "model round-trip changed weights or metrics");
        check(prediction.equals(restored.predict()), "model round-trip changed prediction");
        for (int i = 0; i < 32; i++) {
            boolean win = random.nextBoolean();
            model.observe(win);
            restored.observe(win);
        }
        check(model.save().equals(restored.save()), "resumed model training diverged");
        saved.setProperty("trained", "NaN");
        rejects(() -> CfNeuralModel.fromProperties(saved), "invalid sample count accepted");
        Properties invalidWeight = model.save();
        invalidWeight.setProperty("outputBias", "Infinity");
        rejects(() -> CfNeuralModel.fromProperties(invalidWeight), "nonfinite weight accepted");
        check(CfStatistics.summarize(0, 0).n() == 0, "empty statistics");
        var stats = CfStatistics.summarize(50, 50);
        check(stats.rate() == 0.5 && stats.lower() < 0.5 && stats.upper() > 0.5,
                "confidence interval");
        rejects(() -> CfStatistics.summarize(-1, 1), "negative statistics accepted");
        rejects(() -> CfStatistics.summarize(Long.MAX_VALUE, 1), "overflow statistics accepted");
        System.out.println("CfRestoredModelTest: 14 compatibility checks passed");
    }

    private static void rejects(Runnable action, String reason) {
        try {
            action.run();
            throw new AssertionError(reason);
        } catch (IllegalArgumentException expected) {
            // Corrupt persisted model state must never be silently accepted.
        }
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
