package dev.n9ezz.cf;

import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

/**
 * A small, deterministic local neural network, not an oracle for server RNG.
 * Features contain only already observed outcomes. Evaluation is prequential:
 * score the untouched prediction, train on the new result, then advance history.
 */
public final class CfNeuralModel {
    public static final int WARMUP = 200;
    public static final int MIN_VALIDATION = 100;
    private static final int INPUTS = 12;
    private static final int HIDDEN = 8;
    private static final int HISTORY = 32;
    private static final int WINDOW = 100;
    private static final long MAX_SAMPLES = 1_000_000_000L;
    private static final double MARGIN = 0.015;
    private static final double WEIGHT_LIMIT = 20;
    private final double[][] inputWeights = new double[HIDDEN][INPUTS];
    private final double[] hiddenBias = new double[HIDDEN];
    private final double[] outputWeights = new double[HIDDEN];
    private double outputBias;
    private final int[] history = new int[HISTORY]; // newest first; 0 means unavailable
    private int historySize;
    private long trainedSamples;
    private long wins;
    private long validationSamples;
    private double lossSum;
    private double empiricalLossSum;
    private double constantAdvantageSquares;
    private double empiricalAdvantageSquares;
    private final double[] recentLoss = new double[WINDOW];
    private final double[] recentEmpiricalLoss = new double[WINDOW];

    public record Prediction(double probability, long trainedSamples, long validationSamples,
                             double brier, double baselineBrier, double empiricalBrier,
                             double recentBrier, double recentBaselineBrier,
                             double recentEmpiricalBrier, boolean usable) {}

    public CfNeuralModel() {
        Random random = new Random(0x4346424F54L);
        for (int h = 0; h < HIDDEN; h++) {
            for (int i = 0; i < INPUTS; i++) inputWeights[h][i] = random.nextGaussian() * 0.15;
            outputWeights[h] = random.nextGaussian() * 0.15;
        }
    }

    public Prediction predict() {
        double probability = boundedProbability(forward(features(), new double[HIDDEN]));
        int recentCount = (int) Math.min(validationSamples, WINDOW);
        double recentSum = 0;
        double recentEmpiricalSum = 0;
        double recentConstantSquares = 0;
        double recentEmpiricalSquares = 0;
        for (int i = 0; i < recentCount; i++) {
            recentSum += recentLoss[i];
            recentEmpiricalSum += recentEmpiricalLoss[i];
            recentConstantSquares += square(0.25 - recentLoss[i]);
            recentEmpiricalSquares += square(recentEmpiricalLoss[i] - recentLoss[i]);
        }
        // Both lifetime and recent out-of-sample scores must beat TWO baselines.
        // This is a conservative heuristic, not a p-value or a future guarantee.
        boolean usable = historySize >= 8 && validationSamples >= MIN_VALIDATION
                && recentCount == WINDOW
                && beats(validationSamples, validationSamples * 0.25 - lossSum, constantAdvantageSquares)
                && beats(validationSamples, empiricalLossSum - lossSum, empiricalAdvantageSquares)
                && beats(recentCount, recentCount * 0.25 - recentSum, recentConstantSquares)
                && beats(recentCount, recentEmpiricalSum - recentSum, recentEmpiricalSquares);
        return new Prediction(probability, trainedSamples, validationSamples,
                average(lossSum, validationSamples), validationSamples == 0 ? Double.NaN : 0.25,
                average(empiricalLossSum, validationSamples), average(recentSum, recentCount),
                recentCount == 0 ? Double.NaN : 0.25, average(recentEmpiricalSum, recentCount), usable);
    }

    public void observe(boolean win) {
        if (trainedSamples >= MAX_SAMPLES) throw new IllegalStateException("Neural sample limit reached");
        double[] inputs = features();
        double[] hidden = new double[HIDDEN];
        double rawProbability = forward(inputs, hidden);
        double target = win ? 1 : 0;
        if (trainedSamples >= WARMUP && historySize >= 8) {
            // Neither this label nor its training update has reached the predictor.
            double loss = square(boundedProbability(rawProbability) - target);
            double empiricalProbability = (wins + 1.0) / (trainedSamples + 2.0);
            double empiricalLoss = square(empiricalProbability - target);
            int slot = (int) (validationSamples % WINDOW);
            recentLoss[slot] = loss;
            recentEmpiricalLoss[slot] = empiricalLoss;
            lossSum += loss;
            empiricalLossSum += empiricalLoss;
            constantAdvantageSquares += square(0.25 - loss);
            empiricalAdvantageSquares += square(empiricalLoss - loss);
            validationSamples++;
        }
        // Binary cross-entropy SGD. Hidden gradients use the OLD output weights.
        double rate = 0.04 / Math.sqrt(1 + trainedSamples / 20_000.0);
        double outputGradient = rawProbability - target;
        for (int h = 0; h < HIDDEN; h++) {
            double hiddenGradient = outputGradient * outputWeights[h] * (1 - hidden[h] * hidden[h]);
            for (int i = 0; i < INPUTS; i++)
                inputWeights[h][i] = boundedWeight(inputWeights[h][i] - rate * hiddenGradient * inputs[i]);
            hiddenBias[h] = boundedWeight(hiddenBias[h] - rate * hiddenGradient);
            outputWeights[h] = boundedWeight(outputWeights[h] - rate * outputGradient * hidden[h]);
        }
        outputBias = boundedWeight(outputBias - rate * outputGradient);
        trainedSamples++;
        if (win) wins++;
        System.arraycopy(history, 0, history, 1, HISTORY - 1);
        history[0] = win ? 1 : -1;
        historySize = Math.min(HISTORY, historySize + 1);
    }

    /** Unknown/missed results must never silently become adjacent history features. */
    public void breakSequence() {
        Arrays.fill(history, 0);
        historySize = 0;
    }

    private double[] features() {
        double[] values = new double[INPUTS];
        for (int i = 0; i < 8; i++) values[i] = history[i];
        int shortCount = Math.min(historySize, 8);
        for (int i = 0; i < shortCount; i++) values[8] += history[i];
        if (shortCount > 0) values[8] /= shortCount;
        for (int i = 0; i < historySize; i++) values[9] += history[i];
        if (historySize > 0) values[9] /= historySize;
        int run = 0;
        while (run < Math.min(historySize, 8) && history[run] == history[0]) run++;
        values[10] = run == 0 ? 0 : history[0] * run / 8.0;
        values[11] = historySize / (double) HISTORY;
        return values;
    }

    private double forward(double[] inputs, double[] hidden) {
        double result = outputBias;
        for (int h = 0; h < HIDDEN; h++) {
            double activation = hiddenBias[h];
            for (int i = 0; i < INPUTS; i++) activation += inputs[i] * inputWeights[h][i];
            hidden[h] = Math.tanh(activation);
            result += hidden[h] * outputWeights[h];
        }
        return 1 / (1 + Math.exp(-result));
    }

    private static boolean beats(long count, double advantageSum, double squaredSum) {
        if (count < 2) return false;
        double mean = advantageSum / count;
        double variance = Math.max(0, (squaredSum - advantageSum * mean) / (count - 1));
        return mean > Math.max(MARGIN, 2.58 * Math.sqrt(variance / count));
    }

    /** Hex doubles retain every bit, so resuming cannot change the next prediction. */
    public Properties save() {
        Properties p = new Properties();
        p.setProperty("schema", "cf-mlp-1");
        p.setProperty("inputs", Integer.toString(INPUTS));
        p.setProperty("hidden", Integer.toString(HIDDEN));
        p.setProperty("trained", Long.toString(trainedSamples));
        p.setProperty("wins", Long.toString(wins));
        p.setProperty("validated", Long.toString(validationSamples));
        p.setProperty("historySize", Integer.toString(historySize));
        for (int i = 0; i < HISTORY; i++) p.setProperty("history." + i, Integer.toString(history[i]));
        for (int h = 0; h < HIDDEN; h++) {
            put(p, "hiddenBias." + h, hiddenBias[h]);
            put(p, "outputWeight." + h, outputWeights[h]);
            for (int i = 0; i < INPUTS; i++) put(p, "inputWeight." + h + "." + i, inputWeights[h][i]);
        }
        put(p, "outputBias", outputBias);
        put(p, "lossSum", lossSum);
        put(p, "empiricalLossSum", empiricalLossSum);
        put(p, "constantAdvantageSquares", constantAdvantageSquares);
        put(p, "empiricalAdvantageSquares", empiricalAdvantageSquares);
        for (int i = 0; i < WINDOW; i++) {
            put(p, "recentLoss." + i, recentLoss[i]);
            put(p, "recentEmpiricalLoss." + i, recentEmpiricalLoss[i]);
        }
        return p;
    }

    public static CfNeuralModel fromProperties(Properties p) {
        if (p == null) throw new IllegalArgumentException("Missing neural state");
        if (!"cf-mlp-1".equals(p.getProperty("schema"))) throw invalid("schema");
        if (integer(p, "inputs", INPUTS, INPUTS) != INPUTS
                || integer(p, "hidden", HIDDEN, HIDDEN) != HIDDEN) throw invalid("dimensions");
        CfNeuralModel m = new CfNeuralModel();
        m.trainedSamples = integer(p, "trained", 0, MAX_SAMPLES);
        m.wins = integer(p, "wins", 0, m.trainedSamples);
        m.validationSamples = integer(p, "validated", 0, Math.max(0, m.trainedSamples - WARMUP));
        m.historySize = (int) integer(p, "historySize", 0, Math.min(HISTORY, m.trainedSamples));
        for (int i = 0; i < HISTORY; i++) {
            m.history[i] = (int) integer(p, "history." + i, -1, 1);
            if ((i < m.historySize) != (m.history[i] != 0)) throw invalid("history continuity");
        }
        for (int h = 0; h < HIDDEN; h++) {
            m.hiddenBias[h] = number(p, "hiddenBias." + h, -WEIGHT_LIMIT, WEIGHT_LIMIT);
            m.outputWeights[h] = number(p, "outputWeight." + h, -WEIGHT_LIMIT, WEIGHT_LIMIT);
            for (int i = 0; i < INPUTS; i++)
                m.inputWeights[h][i] = number(p, "inputWeight." + h + "." + i, -WEIGHT_LIMIT, WEIGHT_LIMIT);
        }
        m.outputBias = number(p, "outputBias", -WEIGHT_LIMIT, WEIGHT_LIMIT);
        long n = m.validationSamples;
        m.lossSum = number(p, "lossSum", Math.max(0, n * 0.0025 - tolerance(n)), n * 0.9025 + tolerance(n));
        m.empiricalLossSum = number(p, "empiricalLossSum", 0, n);
        m.constantAdvantageSquares = number(p, "constantAdvantageSquares", 0, n);
        m.empiricalAdvantageSquares = number(p, "empiricalAdvantageSquares", 0, n);
        if (n == 0 && (m.lossSum != 0 || m.empiricalLossSum != 0
                || m.constantAdvantageSquares != 0 || m.empiricalAdvantageSquares != 0))
            throw invalid("empty metrics");
        if (n > 0) {
            if (m.constantAdvantageSquares + tolerance(n) < square(n * 0.25 - m.lossSum) / n
                    || m.empiricalAdvantageSquares + tolerance(n) < square(m.empiricalLossSum - m.lossSum) / n)
                throw invalid("incoherent variance");
        }
        int recentCount = (int) Math.min(n, WINDOW);
        double recentTotal = 0, recentEmpiricalTotal = 0, constantSquares = 0, empiricalSquares = 0;
        for (int i = 0; i < WINDOW; i++) {
            m.recentLoss[i] = number(p, "recentLoss." + i, i < recentCount ? 0.0025 - 1e-14 : 0,
                    i < recentCount ? 0.9025 + 1e-14 : 0);
            m.recentEmpiricalLoss[i] = number(p, "recentEmpiricalLoss." + i, 0, i < recentCount ? 1 : 0);
            recentTotal += m.recentLoss[i];
            recentEmpiricalTotal += m.recentEmpiricalLoss[i];
            if (i < recentCount) {
                constantSquares += square(0.25 - m.recentLoss[i]);
                empiricalSquares += square(m.recentEmpiricalLoss[i] - m.recentLoss[i]);
            }
        }
        if (recentTotal > m.lossSum + tolerance(n)
                || recentEmpiricalTotal > m.empiricalLossSum + tolerance(n)
                || constantSquares > m.constantAdvantageSquares + tolerance(n)
                || empiricalSquares > m.empiricalAdvantageSquares + tolerance(n)) throw invalid("recent metrics exceed totals");
        if (n <= WINDOW && (Math.abs(recentTotal - m.lossSum) > tolerance(n)
                || Math.abs(recentEmpiricalTotal - m.empiricalLossSum) > tolerance(n)
                || Math.abs(constantSquares - m.constantAdvantageSquares) > tolerance(n)
                || Math.abs(empiricalSquares - m.empiricalAdvantageSquares) > tolerance(n)))
            throw invalid("recent metrics disagree with totals");
        int historyWins = 0;
        for (int i = 0; i < m.historySize; i++) if (m.history[i] > 0) historyWins++;
        if (historyWins > m.wins || m.historySize - historyWins > m.trainedSamples - m.wins)
            throw invalid("history outcome counts");
        return m;
    }

    private static long integer(Properties p, String key, long min, long max) {
        try {
            long result = Long.parseLong(p.getProperty(key));
            if (result < min || result > max) throw invalid(key);
            return result;
        } catch (NullPointerException | NumberFormatException ex) { throw invalid(key); }
    }

    private static double number(Properties p, String key, double min, double max) {
        try {
            double result = Double.parseDouble(p.getProperty(key));
            if (!Double.isFinite(result) || result < min || result > max) throw invalid(key);
            return result;
        } catch (NullPointerException | NumberFormatException ex) { throw invalid(key); }
    }

    private static IllegalArgumentException invalid(String key) {
        return new IllegalArgumentException("Invalid neural state: " + key);
    }
    private static void put(Properties p, String key, double value) { p.setProperty(key, Double.toHexString(value)); }
    private static double square(double value) { return value * value; }
    private static double average(double sum, long count) { return count == 0 ? Double.NaN : sum / count; }
    private static double boundedProbability(double value) { return Math.max(0.05, Math.min(0.95, value)); }
    private static double boundedWeight(double value) { return Math.max(-WEIGHT_LIMIT, Math.min(WEIGHT_LIMIT, value)); }
    private static double tolerance(long count) { return Math.max(1, count) * 1e-10; }
}
