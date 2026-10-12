package dev.n9ezz.cf;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;
import java.util.Random;

public final class CfNeuralModelTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        CfNeuralModel empty = new CfNeuralModel();
        check(!empty.predict().usable(), "untrained model cannot enable predictions");
        check(Double.isNaN(empty.predict().brier()), "no fabricated initial validation score");
        check(empty.predict().equals(new CfNeuralModel().predict()), "deterministic initialization");
        CfNeuralModel alternating = new CfNeuralModel();
        for (int i = 0; i < 2_000; i++) {
            CfNeuralModel.Prediction before = alternating.predict();
            boolean win = (i & 1) == 0;
            if (i < 300) check(!before.usable(), "requires 200 warmup and 100 later validation outcomes");
            if (i >= 1_800) check((before.probability() > 0.5) == win, "learns unseen next alternating outcome");
            check(before.probability() >= 0.05 && before.probability() <= 0.95, "never emits certainty");
            alternating.observe(win);
        }
        CfNeuralModel.Prediction learned = alternating.predict();
        check(learned.usable(), "predictable sequence earns validated usable status");
        check(learned.brier() < learned.baselineBrier() - 0.1, "learned model beats constant baseline");
        check(learned.brier() < learned.empiricalBrier() - 0.1, "learned model beats empirical baseline");
        check(learned.validationSamples() == 1_800, "training warmup excluded from reported evaluation");

        CfNeuralModel correlated = new CfNeuralModel();
        Random correlationRng = new Random(83180);
        boolean previous = false;
        for (int i = 0; i < 5_000; i++) {
            boolean outcome = correlationRng.nextDouble() < 0.9 ? !previous : previous;
            correlated.observe(outcome);
            previous = outcome;
        }
        check(correlated.predict().usable(), "learns noisy Markov dependence without assuming perfect outcomes");
        check(correlated.predict().brier() < 0.15, "noisy correlation improves future squared-error score");
        check((correlated.predict().probability() > 0.5) != previous,
                "noisy sequence forecast uses known preceding result");

        CfNeuralModel prequential = new CfNeuralModel();
        for (int i = 0; i < 200; i++) prequential.observe((i & 1) == 0);
        double forecast = prequential.predict().probability();
        Properties beforeObservation = prequential.save();
        prequential.observe(false); // deliberately violate the learned alternating pattern
        close(prequential.predict().brier(), forecast * forecast, 1e-15,
                "loss scores forecast made BEFORE the training label was supplied");
        CfNeuralModel opposite = CfNeuralModel.fromProperties(beforeObservation);
        close(opposite.predict().probability(), forecast, 0, "future outcome cannot affect forecast");
        opposite.observe(true);
        close(opposite.predict().brier(), (1 - forecast) * (1 - forecast), 1e-15,
                "counterfactual label scores same original forecast");

        // A streak alone is not evidence of time-series predictability: the online
        // base-rate estimator learns the same bias, and must also be beaten.
        CfNeuralModel allWins = new CfNeuralModel();
        for (int i = 0; i < 3_000; i++) allWins.observe(true);
        check(!allWins.predict().usable(), "constant winning outcomes fail empirical-baseline comparison");
        CfNeuralModel biasedIndependent = new CfNeuralModel();
        Random biasRng = new Random(51484);
        for (int i = 0; i < 5_000; i++) biasedIndependent.observe(biasRng.nextDouble() < 0.9);
        check(!biasedIndependent.predict().usable(), "base-rate bias is not falsely called a temporal edge");

        for (int seed = 0; seed < 24; seed++) {
            CfNeuralModel independent = new CfNeuralModel();
            Random rng = new Random(seed * 19531L + 781L);
            boolean everUsable = false;
            for (int i = 0; i < 3_000; i++) {
                independent.observe(rng.nextBoolean());
                if (independent.predict().usable()) everUsable = true;
            }
            check(!everUsable, "seeded independent flips do not claim a demonstrated edge, seed=" + seed);
        }

        // Changing the process must remove the recent-performance approval even
        // though excellent old validation observations remain in the lifetime sum.
        Random changedRng = new Random(92384);
        for (int i = 0; i < 1_000; i++) alternating.observe(changedRng.nextBoolean());
        check(!alternating.predict().usable(), "recent-window check rejects an obsolete past pattern");

        CfNeuralModel persisted = roundTrip(alternating);
        check(persisted.predict().equals(alternating.predict()), "persistence keeps exact prediction and metrics");
        check(persisted.save().equals(alternating.save()), "persistence keeps every saved bit");
        for (int i = 0; i < 150; i++) {
            boolean win = i % 3 == 0;
            alternating.observe(win);
            persisted.observe(win);
            check(persisted.predict().equals(alternating.predict()), "restored ring position and next update are exact");
        }
        long total = persisted.predict().trainedSamples();
        long validation = persisted.predict().validationSamples();
        persisted.breakSequence();
        check(!persisted.predict().usable(), "discontinuity disables use without observed history");
        check(persisted.predict().trainedSamples() == total, "break keeps training weights and counters");
        check(roundTrip(persisted).save().equals(persisted.save()), "discontinuity persists exactly");
        for (int i = 0; i < 8; i++) persisted.observe(true);
        check(persisted.predict().validationSamples() == validation, "unknown history not counted as sequence validation");
        persisted.observe(false);
        check(persisted.predict().validationSamples() == validation + 1, "validation resumes with known preceding history");

        Properties valid = persisted.save();
        malformed(valid, "schema", "cf-mlp-999");
        malformed(valid, "inputs", "13");
        malformed(valid, "hidden", "7");
        malformed(valid, "inputWeight.0.0", "NaN");
        malformed(valid, "outputWeight.0", "Infinity");
        malformed(valid, "outputBias", "-21");
        malformed(valid, "trained", "-1");
        malformed(valid, "wins", "999999999");
        malformed(valid, "validated", "999999999");
        malformed(valid, "history.0", "0");
        malformed(valid, "history.31", "1");
        malformed(valid, "lossSum", "-1");
        malformed(valid, "empiricalLossSum", "NaN");
        malformed(valid, "constantAdvantageSquares", "0");
        malformed(valid, "recentLoss.0", "1");
        Properties missing = copy(valid);
        missing.remove("inputWeight.3.5");
        rejects(() -> CfNeuralModel.fromProperties(missing), "missing required weight");
        Properties zero = empty.save();
        malformed(zero, "recentLoss.50", "0.2");
        malformed(zero, "lossSum", "0.00000000001");
        CfNeuralModel partial = new CfNeuralModel();
        for (int i = 0; i < 212; i++) partial.observe((i & 1) == 0);
        check(roundTrip(partial).save().equals(partial.save()), "partly filled ring survives validation");
        Properties partialBad = partial.save();
        partialBad.setProperty("lossSum", "1.0");
        rejects(() -> CfNeuralModel.fromProperties(partialBad), "partial-ring totals must agree with samples");
        check(roundTrip(empty).save().equals(empty.save()), "empty state survives validation");
        System.out.println("CfNeuralModelTest: " + checks + " checks passed");
    }

    private static CfNeuralModel roundTrip(CfNeuralModel model) throws Exception {
        StringWriter writer = new StringWriter();
        model.save().store(writer, "test");
        Properties read = new Properties();
        read.load(new StringReader(writer.toString()));
        return CfNeuralModel.fromProperties(read);
    }
    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }
    private static void malformed(Properties source, String key, String value) {
        Properties changed = copy(source);
        changed.setProperty(key, value);
        rejects(() -> CfNeuralModel.fromProperties(changed), "reject malformed field " + key);
    }
    private static void rejects(Runnable operation, String description) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(description);
    }
    private static void close(double actual, double expected, double tolerance, String description) {
        check(Math.abs(actual - expected) <= tolerance, description + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
