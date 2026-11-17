package tn.esprit.marketsim.agent.training;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentAction;
import tn.esprit.marketsim.agent.AgentConfig;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.agent.AgentLabSupport;
import tn.esprit.marketsim.agent.AgentStrategy;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.Evaluation;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.LearningPoint;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.PolicyState;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.TrainingRequest;
import tn.esprit.marketsim.agent.dto.AgentLabDtos.TrainingResult;
import tn.esprit.marketsim.agent.engine.AssetDynamics;
import tn.esprit.marketsim.agent.engine.Backtester;
import tn.esprit.marketsim.agent.engine.MarketFeatures;
import tn.esprit.marketsim.agent.engine.PathGenerator;
import tn.esprit.marketsim.agent.engine.PriceModel;
import tn.esprit.marketsim.agent.engine.QLearningPolicy;
import tn.esprit.marketsim.agent.engine.QTable;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.ledger.AuditLedger;
import tn.esprit.marketsim.market.Asset;

/**
 * Apprentissage par renforcement (Q-learning tabulaire, Watkins 1989).
 *
 * <p>Chaque episode = une annee de marche simulee par Monte Carlo. A chaque jour :
 * <pre>
 *   recompense r = position x rendement x 100  -  frais de transaction  -  lambda x (position x rendement x 100)^2
 *   Q(s,a) <- Q(s,a) + alpha(s,a) [ r + gamma max_a' Q(s',a') - Q(s,a) ]
 *   alpha(s,a) = 1 / (1 + n(s,a))^0.6   (n = nombre de visites)
 * </pre>
 * Le pas d'apprentissage decroissant verifie les conditions de Robbins-Monro (somme alpha = infini,
 * somme alpha^2 &lt; infini) qui garantissent la convergence du Q-learning, et moyenne le bruit
 * enorme des rendements journaliers (signal ~0,1 pour un bruit ~1,5).
 * Le terme lambda penalise la variance : un agent prudent (risque faible) apprend a eviter
 * les marches agites. Exploration epsilon-greedy avec epsilon decroissant de 1 a 0,05.
 */
@Service
@RequiredArgsConstructor
public class QLearningTrainer {

    static final int WARMUP = 60;
    static final int EPISODE_DAYS = 252;
    static final double ALPHA_DECAY = 0.6;
    static final double ALPHA_MIN = 0.002;
    static final double GAMMA = 0.95;
    static final double EPSILON_MIN = 0.05;
    static final int EVALUATION_PATHS = 300;
    static final int CURVE_POINTS = 60;

    private final AgentLabSupport support;
    private final AgentConfigRepository agentRepository;
    private final AuditLedger ledger;

    public TrainingResult train(AgentConfig agent, TrainingRequest req) {
        if (agent.getStrategy() != AgentStrategy.Q_LEARNING) {
            throw new IllegalArgumentException("Choisissez la stratégie Q_LEARNING pour entraîner cet agent");
        }
        long started = System.currentTimeMillis();
        Asset asset = support.asset(req.symbol());
        AssetDynamics dynamics = support.dynamics(asset);
        PriceModel model = req.model() == null ? PriceModel.REGIME_SWITCHING : req.model();
        int episodes = Math.max(50, Math.min(3000, req.episodes() == null ? 500 : req.episodes()));
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
        double risk = agent.risk();
        double lambda = 0.02 * (1 - risk);
        double cost = Backtester.COST * 100;

        QTable table = new QTable();
        int[] visits = new int[QTable.STATES];
        int[][] updates = new int[QTable.STATES][QTable.ACTIONS];
        SplittableRandom rng = new SplittableRandom(seed);
        List<LearningPoint> curve = new ArrayList<>();
        int chunk = Math.max(1, episodes / CURVE_POINTS);
        double chunkReward = 0;
        double chunkReturn = 0;
        double chunkBench = 0;
        int chunkCount = 0;

        for (int ep = 0; ep < episodes; ep++) {
            double epsilon = Math.max(EPSILON_MIN, 1 - (double) ep / (0.7 * episodes));
            double[] p = PathGenerator.generate(100, WARMUP + EPISODE_DAYS, dynamics, model, rng);
            MarketFeatures[] features = new MarketFeatures[p.length];
            for (int t = WARMUP; t < p.length; t++) features[t] = MarketFeatures.compute(p, t, dynamics.sigma());

            double[] outcome = learnEpisode(table, visits, updates, p, features, epsilon, lambda, cost, rng);
            double episodeReward = outcome[0];
            double logWealth = outcome[1];

            chunkReward += episodeReward;
            chunkReturn += Math.exp(logWealth) - 1;
            chunkBench += p[p.length - 1] / p[WARMUP] - 1;
            chunkCount++;
            if (chunkCount == chunk || ep == episodes - 1) {
                curve.add(new LearningPoint(ep + 1, chunkReward / chunkCount, chunkReturn / chunkCount,
                        chunkBench / chunkCount, epsilon));
                chunkReward = chunkReturn = chunkBench = 0;
                chunkCount = 0;
            }
        }

        agent.setQTable(table.serialize());
        agent.setTrainedEpisodes(episodes);
        agent.setTrainedOn(asset.getSymbol());
        agent.setTrainedAt(LocalDateTime.now());
        agentRepository.save(agent);

        Evaluation evaluation = evaluate(table, dynamics, model, risk, seed);
        auditTraining(agent, asset, model, episodes, seed, evaluation);
        return new TrainingResult(asset.getSymbol(), model, episodes, seed,
                System.currentTimeMillis() - started, curve, policy(table, visits), evaluation);
    }

    /** Certifie la politique apprise : empreinte de la Q-table + conditions d'entrainement + score hors echantillon. */
    private void auditTraining(AgentConfig agent, Asset asset, PriceModel model, int episodes, long seed, Evaluation e) {
        Map<String, Object> p = support.auditContext(agent);
        p.put("symbol", asset.getSymbol());
        p.put("model", String.valueOf(model));
        p.put("episodes", episodes);
        p.put("seed", seed);
        p.put("evalPaths", e.paths());
        p.put("evalMeanReturn", e.meanReturn());
        p.put("evalBenchmarkMeanReturn", e.benchmarkMeanReturn());
        p.put("evalProbBeatBenchmark", e.probBeatBenchmark());
        p.put("evalMeanSharpe", e.meanSharpe());
        p.put("evalBenchmarkMeanSharpe", e.benchmarkMeanSharpe());
        ledger.record("TRAINING", agent.getId(), agent.getOwnerId(), agent.getId(),
                String.format(java.util.Locale.FRANCE, "Entraînement RL %s · %d épisodes", asset.getSymbol(), episodes), p);
    }

    /**
     * Un episode d'apprentissage sur la trajectoire {@code p} (indicateurs deja calcules a partir de WARMUP).
     *
     * @return {recompense cumulee, log-richesse de l'episode}
     */
    private static double[] learnEpisode(QTable table, int[] visits, int[][] updates, double[] p,
                                         MarketFeatures[] features, double epsilon, double lambda, double cost,
                                         SplittableRandom rng) {
        int position = 0;
        double episodeReward = 0;
        double logWealth = 0;
        for (int t = WARMUP; t < p.length - 1; t++) {
            int s = QTable.state(features[t], position);
            visits[s]++;
            int a = rng.nextDouble() < epsilon ? rng.nextInt(QTable.ACTIONS) : table.best(s);
            int newPosition = a == AgentAction.BUY.ordinal() ? 1 : a == AgentAction.SELL.ordinal() ? 0 : position;

            double r = Math.log(p[t + 1] / p[t]);
            double pnl = newPosition * r * 100;
            double reward = pnl - cost * Math.abs(newPosition - position) - lambda * pnl * pnl;

            int next = QTable.state(features[t + 1], newPosition);
            double alpha = Math.max(ALPHA_MIN, Math.pow(1 + updates[s][a]++, -ALPHA_DECAY));
            table.update(s, a, reward + GAMMA * table.max(next), alpha);

            episodeReward += reward;
            logWealth += newPosition * r - Backtester.COST * Math.abs(newPosition - position);
            position = newPosition;
        }
        return new double[] {episodeReward, logWealth};
    }

    /**
     * Apprend sur un historique REEL (rejoue la meme serie a chaque episode, exploration epsilon-greedy).
     * Utilise par le walk-forward : on n'entraine que sur le passe de la fenetre de test.
     *
     * @param sigma volatilite annuelle de reference (estimee sur la fenetre d'entrainement)
     */
    public static QTable trainOnSeries(double[] prices, double sigma, double risk, int episodes, long seed) {
        if (prices.length < WARMUP + 30) {
            throw new IllegalArgumentException("Historique d'entraînement trop court");
        }
        QTable table = new QTable();
        int[] visits = new int[QTable.STATES];
        int[][] updates = new int[QTable.STATES][QTable.ACTIONS];
        SplittableRandom rng = new SplittableRandom(seed);
        MarketFeatures[] features = new MarketFeatures[prices.length];
        for (int t = WARMUP; t < prices.length; t++) features[t] = MarketFeatures.compute(prices, t, sigma);
        double lambda = 0.02 * (1 - risk);
        double cost = Backtester.COST * 100;
        for (int ep = 0; ep < episodes; ep++) {
            double epsilon = Math.max(EPSILON_MIN, 1 - (double) ep / (0.7 * episodes));
            learnEpisode(table, visits, updates, prices, features, epsilon, lambda, cost, rng);
        }
        return table;
    }

    /** Politique courante d'un agent deja entraine (pour la heatmap du frontend). */
    public List<PolicyState> policy(AgentConfig agent) {
        if (!agent.isTrained()) return List.of();
        return policy(QTable.parse(agent.getQTable()), new int[QTable.STATES]);
    }

    private static List<PolicyState> policy(QTable table, int[] visits) {
        List<PolicyState> states = new ArrayList<>();
        for (int s = 0; s < QTable.STATES; s++) {
            int[] d = QTable.decode(s);
            states.add(new PolicyState(s, QTable.describe(s), d[0], d[1], d[2], d[3],
                    AgentAction.values()[table.best(s)], table.values(s).clone(), visits[s]));
        }
        return states;
    }

    /** Test hors echantillon : trajectoires jamais vues pendant l'entrainement (autre graine). */
    private static Evaluation evaluate(QTable table, AssetDynamics dynamics, PriceModel model, double risk, long seed) {
        QLearningPolicy policy = new QLearningPolicy(table);
        Backtester.Result[] results = new Backtester.Result[EVALUATION_PATHS];
        double[][] bench = new double[EVALUATION_PATHS][];
        IntStream.range(0, EVALUATION_PATHS).parallel().forEach(i -> {
            SplittableRandom rng = new SplittableRandom((seed + 7_919) * 0x9E3779B97F4A7C15L + i);
            double[] p = PathGenerator.generate(100, WARMUP + EPISODE_DAYS, dynamics, model, rng);
            results[i] = Backtester.run(p, WARMUP, policy, risk, dynamics.sigma(), 10_000);
            double[] be = new double[EPISODE_DAYS + 1];
            for (int t = 0; t <= EPISODE_DAYS; t++) be[t] = p[WARMUP + t] / p[WARMUP];
            bench[i] = be;
        });

        double[] ret = new double[EVALUATION_PATHS];
        double[] benchRet = new double[EVALUATION_PATHS];
        double[] sharpe = new double[EVALUATION_PATHS];
        double[] benchSharpe = new double[EVALUATION_PATHS];
        double[] dd = new double[EVALUATION_PATHS];
        double[] benchDd = new double[EVALUATION_PATHS];
        int beats = 0;
        for (int i = 0; i < EVALUATION_PATHS; i++) {
            ret[i] = results[i].totalReturn();
            benchRet[i] = results[i].benchmarkReturn();
            sharpe[i] = results[i].sharpe();
            dd[i] = results[i].maxDrawdown();
            benchSharpe[i] = Stats.sharpe(bench[i]);
            benchDd[i] = Stats.maxDrawdown(bench[i]);
            if (ret[i] > benchRet[i]) beats++;
        }
        return new Evaluation(EVALUATION_PATHS, Stats.mean(ret), Stats.mean(benchRet),
                (double) beats / EVALUATION_PATHS, Stats.mean(sharpe), Stats.mean(benchSharpe),
                Stats.mean(dd), Stats.mean(benchDd));
    }
}
