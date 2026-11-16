package tn.esprit.marketsim.user.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.progression.ProgressionService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserLevel;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.level.dto.UserLevelDtos.UserLevelResponse;

@ExtendWith(MockitoExtension.class)
class UserLevelServiceTest {

    @Mock
    private UserLevelAssessmentRepository assessmentRepository;
    @Mock
    private UserLevelFeatureExtractor featureExtractor;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProgressionService progressionService;
    @InjectMocks
    private UserLevelService service;

    @Test
    void insufficientActivityDoesNotInventALevel() {
        User user = user(UserLevel.EXPERT);
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));
        when(assessmentRepository.findByUserId(3L)).thenReturn(Optional.empty());
        when(featureExtractor.extract(any(), any(), any())).thenReturn(quiet());

        UserLevelResponse first = service.recomputeOwn(3L);
        UserLevelResponse second = service.recomputeOwn(3L);

        assertTrue(first.insufficientEvidence());
        assertNull(first.score());
        assertNull(first.level());
        assertEquals(UserLevel.DEBUTANT, user.getLevel());
        assertEquals(Role.USER, user.getRole());
        assertEquals(first.score(), second.score());
        assertEquals(first.level(), second.level());
        assertEquals(first.insufficientEvidence(), second.insufficientEvidence());
    }

    @Test
    void enoughLoginsProduceAStableLearnedLevel() {
        User user = user(UserLevel.DEBUTANT);
        when(userRepository.findById(4L)).thenReturn(Optional.of(user));
        when(assessmentRepository.findByUserId(4L)).thenReturn(Optional.empty());
        when(featureExtractor.extract(any(), any(), any())).thenReturn(
                new UserLevelFeatures(0.9, 0.8, 0.9, 0.85, 0.8, 0.8, 0.8, 0.8, 12));

        UserLevelResponse level = service.recomputeOwn(4L);
        assertTrue(level.score() >= 0 && level.score() <= 100);
        assertEquals(UserLevelModel.levelForScore(level.score()), level.level());
        assertEquals(level.level(), user.getLevel());
        assertEquals(UserLevelModel.VERSION, level.modelVersion());
        assertEquals(false, level.insufficientEvidence());
    }

    @Test
    void missingUserWithoutStoredLevelIsNotFound() {
        when(userRepository.existsById(99L)).thenReturn(false);
        when(assessmentRepository.findByUserId(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.recomputeForAdmin(99L));
        verify(featureExtractor, never()).extract(any(), any(), any());
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void deletedUserKeepsStoredLevelEvenWhenActivitySurvives() {
        UserLevelAssessment stored = UserLevelAssessment.builder()
                .userId(8L)
                .score(80)
                .level(UserLevel.EXPERT)
                .insufficientEvidence(false)
                .successfulLoginCount(12)
                .reasonCodes("TRADING_ACTIVITY")
                .modelVersion(UserLevelModel.VERSION)
                .computedAt(Instant.parse("2026-10-19T12:00:00Z"))
                .build();
        when(userRepository.existsById(8L)).thenReturn(false);
        when(assessmentRepository.findByUserId(8L)).thenReturn(Optional.of(stored));

        UserLevelResponse level = service.recomputeForAdmin(8L);
        assertEquals(UserLevel.EXPERT, level.level());
        assertEquals(80, level.score());
        assertEquals(Instant.parse("2026-10-19T12:00:00Z"), level.computedAt());
        verify(featureExtractor, never()).extract(any(), any(), any());
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void lesDeuxNiveauxSontCalculesIndependammentEtLeNiveauDuJeuEstAffiche() {
        User user = user(UserLevel.DEBUTANT);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(assessmentRepository.findByUserId(5L)).thenReturn(Optional.empty());
        // activite tres faible pour le modele IA...
        when(featureExtractor.extract(any(), any(), any())).thenReturn(
                new UserLevelFeatures(0.1, 0.05, 0.1, 0.05, 0.05, 0.05, 0.05, 0.05, 12));
        // ... mais Expert dans le jeu avec un score de competence de 88
        when(progressionService.tradingAssessment(5L))
                .thenReturn(Optional.of(new ProgressionService.TradingAssessment(UserLevel.EXPERT, 88)));

        UserLevelResponse r = service.recomputeOwn(5L);
        // le niveau IA reste visible, avec SON score
        assertEquals(UserLevelModel.levelForScore(r.activityScore()), r.activityLevel());
        assertTrue(r.activityScore() < 50);
        // le niveau du jeu aussi
        assertEquals(UserLevel.EXPERT, r.tradingLevel());
        assertEquals(88, r.tradingScore());
        // regle d'affichage : le jeu, et le score qui va avec (plus de « score 30 / Expert »)
        assertEquals("TRADING", r.levelSource());
        assertEquals(UserLevel.EXPERT, r.level());
        assertEquals(88, r.score());
        assertEquals(UserLevel.EXPERT, user.getLevel());
        assertEquals(UserLevelService.DISPLAY_RULE, r.displayRule());
        assertEquals("TRADING_PROGRESSION", r.reasonCodes().get(0));
    }

    @Test
    void sansProfilDeTraderLeNiveauIAEstAfficheAvecSonScore() {
        User user = user(UserLevel.DEBUTANT);
        when(userRepository.findById(6L)).thenReturn(Optional.of(user));
        when(assessmentRepository.findByUserId(6L)).thenReturn(Optional.empty());
        when(featureExtractor.extract(any(), any(), any())).thenReturn(
                new UserLevelFeatures(0.9, 0.8, 0.9, 0.85, 0.8, 0.8, 0.8, 0.8, 12));
        when(progressionService.tradingAssessment(6L)).thenReturn(Optional.empty());

        UserLevelResponse r = service.recomputeOwn(6L);
        assertEquals("ACTIVITY", r.levelSource());
        assertNull(r.tradingLevel());
        assertEquals(r.activityLevel(), r.level());
        assertEquals(r.activityScore(), r.score());
        assertEquals(UserLevelModel.levelForScore(r.score()), r.level());
    }

    @Test
    void profilDeTraderSansActiviteSuffisante() {
        User user = user(UserLevel.DEBUTANT);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(assessmentRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(featureExtractor.extract(any(), any(), any())).thenReturn(quiet());
        when(progressionService.tradingAssessment(7L))
                .thenReturn(Optional.of(new ProgressionService.TradingAssessment(UserLevel.INTERMEDIAIRE, 41)));

        UserLevelResponse r = service.recomputeOwn(7L);
        assertNull(r.activityLevel());
        assertNull(r.activityScore());
        assertEquals(UserLevel.INTERMEDIAIRE, r.level());
        assertEquals(41, r.score());
        assertEquals(false, r.insufficientEvidence());
        assertEquals(UserLevel.INTERMEDIAIRE, user.getLevel());
    }

    private static User user(UserLevel level) {
        return User.builder()
                .id(1L)
                .firstName("A")
                .lastName("B")
                .email("a@b.c")
                .password("hash")
                .role(Role.USER)
                .level(level)
                .enabled(true)
                .build();
    }

    private static UserLevelFeatures quiet() {
        return new UserLevelFeatures(0.05, 0, 0, 0, 0, 0, 0, 0, 2);
    }
}