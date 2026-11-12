package tn.esprit.marketsim.user.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import tn.esprit.marketsim.user.UserLevel;

/**
 * Regression softmax versionnee. Les poids sont appris sur un corpus fixe de profils,
 * pas par des seuils poses directement sur le nombre d'ordres ou de connexions.
 */
public final class UserLevelModel {

    public static final String VERSION = "user-level-softmax-v1";
    public static final int MIN_SUCCESSFUL_LOGINS = 3;

    private static final int CLASSES = 4;
    private static final UserLevelModel TRAINED = train();

    private final double[][] weights;

    private UserLevelModel(double[][] weights) {
        this.weights = weights;
    }

    public static UserLevelModel current() {
        return TRAINED;
    }

    public String version() {
        return VERSION;
    }

    public int score(UserLevelFeatures features) {
        double[] probability = probabilities(features.toArray());
        double expected = 0;
        for (int level = 0; level < CLASSES; level++) {
            expected += probability[level] * level;
        }
        long rounded = Math.round(100.0 * expected / (CLASSES - 1));
        if (rounded < 0) {
            return 0;
        }
        if (rounded > 100) {
            return 100;
        }
        return (int) rounded;
    }

    public static UserLevel levelForScore(int score) {
        if (score < 25) {
            return UserLevel.DEBUTANT;
        }
        if (score < 50) {
            return UserLevel.INTERMEDIAIRE;
        }
        if (score < 75) {
            return UserLevel.AVANCE;
        }
        return UserLevel.EXPERT;
    }

    public List<String> explain(UserLevelFeatures features) {
        List<String> codes = new ArrayList<>();
        if (features.loginNorm() >= 0.4) {
            codes.add("ENGAGED_LOGINS");
        }
        if (features.profileNorm() >= 0.2) {
            codes.add("PROFILE_MAINTAINED");
        }
        if (features.orderNorm() >= 0.25) {
            codes.add("TRADING_ACTIVITY");
        }
        if (features.protectionRate() >= 0.4) {
            codes.add("RISK_CONTROLS_USED");
        }
        if (features.symbolDiversity() >= 0.4) {
            codes.add("DIVERSIFIED_SYMBOLS");
        }
        if (codes.isEmpty()) {
            codes.add(features.orderNorm() < 0.1 ? "LIMITED_ACTIVITY" : "LEARNED_PROFILE");
        }
        return codes;
    }

    private double[] probabilities(double[] features) {
        double[] logits = new double[CLASSES];
        double max = Double.NEGATIVE_INFINITY;
        for (int level = 0; level < CLASSES; level++) {
            logits[level] = weights[level][0];
            for (int feature = 0; feature < features.length; feature++) {
                logits[level] += weights[level][feature + 1] * features[feature];
            }
            max = Math.max(max, logits[level]);
        }
        double sum = 0;
        double[] probability = new double[CLASSES];
        for (int level = 0; level < CLASSES; level++) {
            probability[level] = Math.exp(logits[level] - max);
            sum += probability[level];
        }
        for (int level = 0; level < CLASSES; level++) {
            probability[level] /= sum;
        }
        return probability;
    }

    static UserLevelModel train() {
        double[][] centers = {
                {0.06, 0.00, 0.00, 0.00, 0.00, 0.00, 0.00, 0.08},
                {0.40, 0.40, 0.25, 0.30, 0.20, 0.20, 0.35, 0.30},
                {0.65, 0.60, 0.60, 0.65, 0.60, 0.55, 0.65, 0.55},
                {0.92, 0.80, 0.90, 0.85, 0.85, 0.85, 0.80, 0.85}
        };
        Random random = new Random(7);
        int samplesPerClass = 24;
        int samples = samplesPerClass * CLASSES;
        double[][] inputs = new double[samples][UserLevelFeatures.DIMENSION + 1];
        int[] labels = new int[samples];
        int row = 0;
        for (int level = 0; level < CLASSES; level++) {
            for (int sample = 0; sample < samplesPerClass; sample++) {
                inputs[row][0] = 1;
                for (int feature = 0; feature < UserLevelFeatures.DIMENSION; feature++) {
                    double noise = (random.nextDouble() - 0.5) * 0.08;
                    inputs[row][feature + 1] = clamp01(centers[level][feature] + noise);
                }
                labels[row] = level;
                row++;
            }
        }

        double[][] weights = new double[CLASSES][UserLevelFeatures.DIMENSION + 1];
        double learningRate = 0.35;
        for (int epoch = 0; epoch < 600; epoch++) {
            for (int sample = 0; sample < samples; sample++) {
                double[] probability = softmax(weights, inputs[sample]);
                for (int level = 0; level < CLASSES; level++) {
                    double error = probability[level] - (labels[sample] == level ? 1 : 0);
                    for (int feature = 0; feature < weights[level].length; feature++) {
                        double penalty = feature == 0 ? 0 : 0.001 * weights[level][feature];
                        weights[level][feature] -= learningRate * (error * inputs[sample][feature] + penalty);
                    }
                }
            }
        }
        return new UserLevelModel(weights);
    }

    private static double[] softmax(double[][] weights, double[] input) {
        double[] logits = new double[CLASSES];
        double max = Double.NEGATIVE_INFINITY;
        for (int level = 0; level < CLASSES; level++) {
            double logit = 0;
            for (int feature = 0; feature < input.length; feature++) {
                logit += weights[level][feature] * input[feature];
            }
            logits[level] = logit;
            max = Math.max(max, logit);
        }
        double sum = 0;
        double[] probability = new double[CLASSES];
        for (int level = 0; level < CLASSES; level++) {
            probability[level] = Math.exp(logits[level] - max);
            sum += probability[level];
        }
        for (int level = 0; level < CLASSES; level++) {
            probability[level] /= sum;
        }
        return probability;
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
