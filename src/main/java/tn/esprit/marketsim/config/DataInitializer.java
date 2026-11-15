package tn.esprit.marketsim.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.education.Course;
import tn.esprit.marketsim.education.CourseRepository;
import tn.esprit.marketsim.market.Asset;
import tn.esprit.marketsim.market.AssetRepository;
import tn.esprit.marketsim.market.MarketNews;
import tn.esprit.marketsim.market.MarketNewsRepository;
import tn.esprit.marketsim.market.PredictionService;
import tn.esprit.marketsim.market.SentimentAnalyzer;
import tn.esprit.marketsim.stress.StressScenario;
import tn.esprit.marketsim.stress.StressScenarioRepository;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;

/** Remplit la base avec un admin et quelques donnees de demo au premier lancement. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final MarketNewsRepository marketNewsRepository;
    private final PredictionService predictionService;
    private final CourseRepository courseRepository;
    private final StressScenarioRepository scenarioRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (!userRepository.existsByEmail(adminEmail)) {
            userRepository.save(User.builder()
                    .firstName("Admin")
                    .lastName("MarketSim")
                    .email(adminEmail)
                    .password(passwordEncoder.encode(adminPassword))
                    .role(Role.ADMIN)
                    .enabled(true)
                    .build());
            log.info("Compte admin cree : {}", adminEmail);
        }

        if (assetRepository.count() == 0) {
            assetRepository.saveAll(List.of(
                    Asset.of("AAPL", "Apple Inc.", Asset.Type.STOCK, "192.45", 1.24),
                    Asset.of("TSLA", "Tesla Inc.", Asset.Type.STOCK, "248.30", -0.82),
                    Asset.of("EUR/USD", "Euro / US Dollar", Asset.Type.FOREX, "1.0842", 0.31),
                    Asset.of("BTC/USD", "Bitcoin", Asset.Type.CRYPTO, "67240", 2.14),
                    Asset.of("GOLD", "Gold Spot", Asset.Type.COMMODITY, "2341.60", 0.54),
                    Asset.of("WTI", "Crude Oil WTI", Asset.Type.COMMODITY, "78.90", -1.12)));
        }

        if (marketNewsRepository.count() == 0) {
            // Données de démonstration — sentiment calculé par SentimentAnalyzer
            Instant now = Instant.now();
            marketNewsRepository.saveAll(List.of(
                    MarketNews.of(
                            "Apple beats earnings expectations, raises full-year guidance",
                            "Bloomberg", "https://bloomberg.com", "AAPL",
                            now.minus(4, ChronoUnit.HOURS), 1.0, SentimentAnalyzer.Label.POSITIVE),
                    MarketNews.of(
                            "Bitcoin ETF inflows hit record on institutional demand surge",
                            "Financial Times", "https://ft.com", "BTC/USD",
                            now.minus(6, ChronoUnit.HOURS), 1.0, SentimentAnalyzer.Label.POSITIVE),
                    MarketNews.of(
                            "Oil inventories rise unexpectedly, prices under pressure",
                            "Reuters", "https://reuters.com", "WTI",
                            now.minus(5, ChronoUnit.HOURS), -1.0, SentimentAnalyzer.Label.NEGATIVE),
                    MarketNews.of(
                            "Fed signals pause in rate hikes as inflation cools",
                            "CNBC", "https://cnbc.com", "EUR/USD",
                            now.minus(2, ChronoUnit.HOURS), 0.0, SentimentAnalyzer.Label.NEUTRAL)));
        }

        if (predictionService.findAll().isEmpty()) {
            for (Asset asset : assetRepository.findAll()) {
                try {
                    predictionService.generateFor(asset.getSymbol());
                } catch (Exception e) {
                    log.warn("Prédiction non générée pour {} : {}", asset.getSymbol(), e.getMessage());
                }
            }
            log.info("Prédictions initiales générées pour {} actifs", assetRepository.count());
        }

        if (courseRepository.count() == 0) {
            courseRepository.saveAll(List.of(
                    Course.of("Foundations of Trading", "Les bases du trading", Course.Level.BEGINNER, 12, 200),
                    Course.of("Technical Analysis", "Indicateurs et figures", Course.Level.INTERMEDIATE, 18, 310),
                    Course.of("Risk Management", "Stop-loss, sizing, exposition", Course.Level.INTERMEDIATE, 15, 240),
                    Course.of("Algorithmic Strategies", "Strategies automatisees", Course.Level.ADVANCED, 20, 390)));
        }

        if (scenarioRepository.count() == 0) {
            scenarioRepository.saveAll(List.of(
                    StressScenario.of("2008 Financial Crisis", StressScenario.Severity.EXTREME, -5.2, 3.5, -40),
                    StressScenario.of("COVID-19 Shock (2020)", StressScenario.Severity.SEVERE, -3.8, -1.5, -25),
                    StressScenario.of("Dot-com Bust (2001)", StressScenario.Severity.HIGH, -2.1, -2.0, -18),
                    StressScenario.of("Sovereign Debt Crisis", StressScenario.Severity.MODERATE, -1.5, 1.2, -12)));
        }
    }
}
