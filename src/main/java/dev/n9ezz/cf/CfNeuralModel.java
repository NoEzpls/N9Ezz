/* Restored from the supplied 0.2.1 bytecode with CFR 0.152; see provenance.json. */
package dev.n9ezz.cf;

import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

/** Historical diagnostic model retained for state compatibility.
 * Its probability is an estimate of past data, not a prediction guarantee for server CoinFlip.
 */
public final class CfNeuralModel {
    public static final int WARMUP = 200;
    public static final int MIN_VALIDATION = 100;
    private static final int INPUTS = 12;
    private static final int HIDDEN = 8;
    private static final int HISTORY = 32;
    private static final int WINDOW = 100;
    private static final long MAX_SAMPLES = 1000000000L;
    private static final double MARGIN = 0.015;
    private static final double WEIGHT_LIMIT = 20.0;
    private final double[][] inputWeights = new double[8][12];
    private final double[] hiddenBias = new double[8];
    private final double[] outputWeights = new double[8];
    private double outputBias;
    private final int[] history = new int[32];
    private int historySize;
    private long trainedSamples;
    private long wins;
    private long validationSamples;
    private double lossSum;
    private double empiricalLossSum;
    private double constantAdvantageSquares;
    private double empiricalAdvantageSquares;
    private final double[] recentLoss = new double[100];
    private final double[] recentEmpiricalLoss = new double[100];

    public CfNeuralModel() {
        Random random = new Random(288941559636L);
        for (int i = 0; i < 8; ++i) {
            for (int j = 0; j < 12; ++j) {
                this.inputWeights[i][j] = random.nextGaussian() * 0.15;
            }
            this.outputWeights[i] = random.nextGaussian() * 0.15;
        }
    }

    public Prediction predict() {
        int n;
        double d = CfNeuralModel.boundedProbability(this.forward(this.features(), new double[8]));
        int n2 = (int)Math.min(this.validationSamples, 100L);
        double d2 = 0.0;
        double d3 = 0.0;
        double d4 = 0.0;
        double d5 = 0.0;
        for (n = 0; n < n2; ++n) {
            d2 += this.recentLoss[n];
            d3 += this.recentEmpiricalLoss[n];
            d4 += CfNeuralModel.square(0.25 - this.recentLoss[n]);
            d5 += CfNeuralModel.square(this.recentEmpiricalLoss[n] - this.recentLoss[n]);
        }
        n = this.historySize >= 8 && this.validationSamples >= 100L && n2 == 100 && CfNeuralModel.beats(this.validationSamples, (double)this.validationSamples * 0.25 - this.lossSum, this.constantAdvantageSquares) && CfNeuralModel.beats(this.validationSamples, this.empiricalLossSum - this.lossSum, this.empiricalAdvantageSquares) && CfNeuralModel.beats(n2, (double)n2 * 0.25 - d2, d4) && CfNeuralModel.beats(n2, d3 - d2, d5) ? 1 : 0;
        return new Prediction(d, this.trainedSamples, this.validationSamples, CfNeuralModel.average(this.lossSum, this.validationSamples), this.validationSamples == 0L ? Double.NaN : 0.25, CfNeuralModel.average(this.empiricalLossSum, this.validationSamples), CfNeuralModel.average(d2, n2), n2 == 0 ? Double.NaN : 0.25, CfNeuralModel.average(d3, n2), n != 0);
    }

    public void observe(boolean bl) {
        double d;
        double d2;
        double d3;
        if (this.trainedSamples >= 1000000000L) {
            throw new IllegalStateException("Neural sample limit reached");
        }
        double[] dArray = this.features();
        double[] dArray2 = new double[8];
        double d4 = this.forward(dArray, dArray2);
        double d5 = d3 = bl ? 1.0 : 0.0;
        if (this.trainedSamples >= 200L && this.historySize >= 8) {
            d2 = CfNeuralModel.square(CfNeuralModel.boundedProbability(d4) - d3);
            d = ((double)this.wins + 1.0) / ((double)this.trainedSamples + 2.0);
            double d6 = CfNeuralModel.square(d - d3);
            int n = (int)(this.validationSamples % 100L);
            this.recentLoss[n] = d2;
            this.recentEmpiricalLoss[n] = d6;
            this.lossSum += d2;
            this.empiricalLossSum += d6;
            this.constantAdvantageSquares += CfNeuralModel.square(0.25 - d2);
            this.empiricalAdvantageSquares += CfNeuralModel.square(d6 - d2);
            ++this.validationSamples;
        }
        d2 = 0.04 / Math.sqrt(1.0 + (double)this.trainedSamples / 20000.0);
        d = d4 - d3;
        for (int i = 0; i < 8; ++i) {
            double d7 = d * this.outputWeights[i] * (1.0 - dArray2[i] * dArray2[i]);
            for (int j = 0; j < 12; ++j) {
                this.inputWeights[i][j] = CfNeuralModel.boundedWeight(this.inputWeights[i][j] - d2 * d7 * dArray[j]);
            }
            this.hiddenBias[i] = CfNeuralModel.boundedWeight(this.hiddenBias[i] - d2 * d7);
            this.outputWeights[i] = CfNeuralModel.boundedWeight(this.outputWeights[i] - d2 * d * dArray2[i]);
        }
        this.outputBias = CfNeuralModel.boundedWeight(this.outputBias - d2 * d);
        ++this.trainedSamples;
        if (bl) {
            ++this.wins;
        }
        System.arraycopy(this.history, 0, this.history, 1, 31);
        this.history[0] = bl ? 1 : -1;
        this.historySize = Math.min(32, this.historySize + 1);
    }

    public void breakSequence() {
        Arrays.fill(this.history, 0);
        this.historySize = 0;
    }

    private double[] features() {
        int n;
        int n2;
        double[] dArray = new double[12];
        for (n2 = 0; n2 < 8; ++n2) {
            dArray[n2] = this.history[n2];
        }
        n2 = Math.min(this.historySize, 8);
        for (n = 0; n < n2; ++n) {
            dArray[8] = dArray[8] + (double)this.history[n];
        }
        if (n2 > 0) {
            dArray[8] = dArray[8] / (double)n2;
        }
        for (n = 0; n < this.historySize; ++n) {
            dArray[9] = dArray[9] + (double)this.history[n];
        }
        if (this.historySize > 0) {
            dArray[9] = dArray[9] / (double)this.historySize;
        }
        for (n = 0; n < Math.min(this.historySize, 8) && this.history[n] == this.history[0]; ++n) {
        }
        dArray[10] = n == 0 ? 0.0 : (double)(this.history[0] * n) / 8.0;
        dArray[11] = (double)this.historySize / 32.0;
        return dArray;
    }

    private double forward(double[] dArray, double[] dArray2) {
        double d = this.outputBias;
        for (int i = 0; i < 8; ++i) {
            double d2 = this.hiddenBias[i];
            for (int j = 0; j < 12; ++j) {
                d2 += dArray[j] * this.inputWeights[i][j];
            }
            dArray2[i] = Math.tanh(d2);
            d += dArray2[i] * this.outputWeights[i];
        }
        return 1.0 / (1.0 + Math.exp(-d));
    }

    private static boolean beats(long l, double d, double d2) {
        if (l < 2L) {
            return false;
        }
        double d3 = d / (double)l;
        double d4 = Math.max(0.0, (d2 - d * d3) / (double)(l - 1L));
        return d3 > Math.max(0.015, 2.58 * Math.sqrt(d4 / (double)l));
    }

    public Properties save() {
        int n;
        Properties properties = new Properties();
        properties.setProperty("schema", "cf-mlp-1");
        properties.setProperty("inputs", Integer.toString(12));
        properties.setProperty("hidden", Integer.toString(8));
        properties.setProperty("trained", Long.toString(this.trainedSamples));
        properties.setProperty("wins", Long.toString(this.wins));
        properties.setProperty("validated", Long.toString(this.validationSamples));
        properties.setProperty("historySize", Integer.toString(this.historySize));
        for (n = 0; n < 32; ++n) {
            properties.setProperty("history." + n, Integer.toString(this.history[n]));
        }
        for (n = 0; n < 8; ++n) {
            CfNeuralModel.put(properties, "hiddenBias." + n, this.hiddenBias[n]);
            CfNeuralModel.put(properties, "outputWeight." + n, this.outputWeights[n]);
            for (int i = 0; i < 12; ++i) {
                CfNeuralModel.put(properties, "inputWeight." + n + "." + i, this.inputWeights[n][i]);
            }
        }
        CfNeuralModel.put(properties, "outputBias", this.outputBias);
        CfNeuralModel.put(properties, "lossSum", this.lossSum);
        CfNeuralModel.put(properties, "empiricalLossSum", this.empiricalLossSum);
        CfNeuralModel.put(properties, "constantAdvantageSquares", this.constantAdvantageSquares);
        CfNeuralModel.put(properties, "empiricalAdvantageSquares", this.empiricalAdvantageSquares);
        for (n = 0; n < 100; ++n) {
            CfNeuralModel.put(properties, "recentLoss." + n, this.recentLoss[n]);
            CfNeuralModel.put(properties, "recentEmpiricalLoss." + n, this.recentEmpiricalLoss[n]);
        }
        return properties;
    }

    public static CfNeuralModel fromProperties(Properties properties) {
        int n;
        int n2;
        if (properties == null) {
            throw new IllegalArgumentException("Missing neural state");
        }
        if (!"cf-mlp-1".equals(properties.getProperty("schema"))) {
            throw CfNeuralModel.invalid("schema");
        }
        if (CfNeuralModel.integer(properties, "inputs", 12L, 12L) != 12L || CfNeuralModel.integer(properties, "hidden", 8L, 8L) != 8L) {
            throw CfNeuralModel.invalid("dimensions");
        }
        CfNeuralModel cfNeuralModel = new CfNeuralModel();
        cfNeuralModel.trainedSamples = CfNeuralModel.integer(properties, "trained", 0L, 1000000000L);
        cfNeuralModel.wins = CfNeuralModel.integer(properties, "wins", 0L, cfNeuralModel.trainedSamples);
        cfNeuralModel.validationSamples = CfNeuralModel.integer(properties, "validated", 0L, Math.max(0L, cfNeuralModel.trainedSamples - 200L));
        cfNeuralModel.historySize = (int)CfNeuralModel.integer(properties, "historySize", 0L, Math.min(32L, cfNeuralModel.trainedSamples));
        for (n2 = 0; n2 < 32; ++n2) {
            cfNeuralModel.history[n2] = (int)CfNeuralModel.integer(properties, "history." + n2, -1L, 1L);
            if (n2 < cfNeuralModel.historySize == (cfNeuralModel.history[n2] != 0)) continue;
            throw CfNeuralModel.invalid("history continuity");
        }
        for (n2 = 0; n2 < 8; ++n2) {
            cfNeuralModel.hiddenBias[n2] = CfNeuralModel.number(properties, "hiddenBias." + n2, -20.0, 20.0);
            cfNeuralModel.outputWeights[n2] = CfNeuralModel.number(properties, "outputWeight." + n2, -20.0, 20.0);
            for (int i = 0; i < 12; ++i) {
                cfNeuralModel.inputWeights[n2][i] = CfNeuralModel.number(properties, "inputWeight." + n2 + "." + i, -20.0, 20.0);
            }
        }
        cfNeuralModel.outputBias = CfNeuralModel.number(properties, "outputBias", -20.0, 20.0);
        long l = cfNeuralModel.validationSamples;
        cfNeuralModel.lossSum = CfNeuralModel.number(properties, "lossSum", Math.max(0.0, (double)l * 0.0025 - CfNeuralModel.tolerance(l)), (double)l * 0.9025 + CfNeuralModel.tolerance(l));
        cfNeuralModel.empiricalLossSum = CfNeuralModel.number(properties, "empiricalLossSum", 0.0, l);
        cfNeuralModel.constantAdvantageSquares = CfNeuralModel.number(properties, "constantAdvantageSquares", 0.0, l);
        cfNeuralModel.empiricalAdvantageSquares = CfNeuralModel.number(properties, "empiricalAdvantageSquares", 0.0, l);
        if (l == 0L && (cfNeuralModel.lossSum != 0.0 || cfNeuralModel.empiricalLossSum != 0.0 || cfNeuralModel.constantAdvantageSquares != 0.0 || cfNeuralModel.empiricalAdvantageSquares != 0.0)) {
            throw CfNeuralModel.invalid("empty metrics");
        }
        if (l > 0L && (cfNeuralModel.constantAdvantageSquares + CfNeuralModel.tolerance(l) < CfNeuralModel.square((double)l * 0.25 - cfNeuralModel.lossSum) / (double)l || cfNeuralModel.empiricalAdvantageSquares + CfNeuralModel.tolerance(l) < CfNeuralModel.square(cfNeuralModel.empiricalLossSum - cfNeuralModel.lossSum) / (double)l)) {
            throw CfNeuralModel.invalid("incoherent variance");
        }
        int n3 = (int)Math.min(l, 100L);
        double d = 0.0;
        double d2 = 0.0;
        double d3 = 0.0;
        double d4 = 0.0;
        for (n = 0; n < 100; ++n) {
            cfNeuralModel.recentLoss[n] = CfNeuralModel.number(properties, "recentLoss." + n, n < n3 ? 0.0024999999999900002 : 0.0, n < n3 ? 0.90250000000001 : 0.0);
            cfNeuralModel.recentEmpiricalLoss[n] = CfNeuralModel.number(properties, "recentEmpiricalLoss." + n, 0.0, n < n3 ? 1.0 : 0.0);
            d += cfNeuralModel.recentLoss[n];
            d2 += cfNeuralModel.recentEmpiricalLoss[n];
            if (n >= n3) continue;
            d3 += CfNeuralModel.square(0.25 - cfNeuralModel.recentLoss[n]);
            d4 += CfNeuralModel.square(cfNeuralModel.recentEmpiricalLoss[n] - cfNeuralModel.recentLoss[n]);
        }
        if (d > cfNeuralModel.lossSum + CfNeuralModel.tolerance(l) || d2 > cfNeuralModel.empiricalLossSum + CfNeuralModel.tolerance(l) || d3 > cfNeuralModel.constantAdvantageSquares + CfNeuralModel.tolerance(l) || d4 > cfNeuralModel.empiricalAdvantageSquares + CfNeuralModel.tolerance(l)) {
            throw CfNeuralModel.invalid("recent metrics exceed totals");
        }
        if (l <= 100L && (Math.abs(d - cfNeuralModel.lossSum) > CfNeuralModel.tolerance(l) || Math.abs(d2 - cfNeuralModel.empiricalLossSum) > CfNeuralModel.tolerance(l) || Math.abs(d3 - cfNeuralModel.constantAdvantageSquares) > CfNeuralModel.tolerance(l) || Math.abs(d4 - cfNeuralModel.empiricalAdvantageSquares) > CfNeuralModel.tolerance(l))) {
            throw CfNeuralModel.invalid("recent metrics disagree with totals");
        }
        n = 0;
        for (int i = 0; i < cfNeuralModel.historySize; ++i) {
            if (cfNeuralModel.history[i] <= 0) continue;
            ++n;
        }
        if ((long)n > cfNeuralModel.wins || (long)(cfNeuralModel.historySize - n) > cfNeuralModel.trainedSamples - cfNeuralModel.wins) {
            throw CfNeuralModel.invalid("history outcome counts");
        }
        return cfNeuralModel;
    }

    private static long integer(Properties properties, String string, long l, long l2) {
        try {
            long l3 = Long.parseLong(properties.getProperty(string));
            if (l3 < l || l3 > l2) {
                throw CfNeuralModel.invalid(string);
            }
            return l3;
        }
        catch (NullPointerException | NumberFormatException runtimeException) {
            throw CfNeuralModel.invalid(string);
        }
    }

    private static double number(Properties properties, String string, double d, double d2) {
        try {
            double d3 = Double.parseDouble(properties.getProperty(string));
            if (!Double.isFinite(d3) || d3 < d || d3 > d2) {
                throw CfNeuralModel.invalid(string);
            }
            return d3;
        }
        catch (NullPointerException | NumberFormatException runtimeException) {
            throw CfNeuralModel.invalid(string);
        }
    }

    private static IllegalArgumentException invalid(String string) {
        return new IllegalArgumentException("Invalid neural state: " + string);
    }

    private static void put(Properties properties, String string, double d) {
        properties.setProperty(string, Double.toHexString(d));
    }

    private static double square(double d) {
        return d * d;
    }

    private static double average(double d, long l) {
        return l == 0L ? Double.NaN : d / (double)l;
    }

    private static double boundedProbability(double d) {
        return Math.max(0.05, Math.min(0.95, d));
    }

    private static double boundedWeight(double d) {
        return Math.max(-20.0, Math.min(20.0, d));
    }

    private static double tolerance(long l) {
        return (double)Math.max(1L, l) * 1.0E-10;
    }

    public record Prediction(double probability, long trainedSamples, long validationSamples, double brier, double baselineBrier, double empiricalBrier, double recentBrier, double recentBaselineBrier, double recentEmpiricalBrier, boolean usable) {
    }
}

