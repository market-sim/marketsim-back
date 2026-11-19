package tn.esprit.marketsim.analysis.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.IndicatorConfigRequest;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.IndicatorConfigView;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.PeriodsInput;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

/**
 * Configurations d'indicateurs : creation, lecture, modification, suppression, validation, isolation entre
 * utilisateurs et nom unique. Le depot est une petite base en memoire qui reproduit le filtre par proprietaire
 * et l'unicite du nom (sans tenir compte des majuscules, comme la collation de MySQL).
 */
class IndicatorConfigServiceTest {

    private IndicatorConfigRepository repository;
    private IndicatorConfigService service;
    private User alice;
    private User bob;
    private final Map<Long, IndicatorConfig> db = new LinkedHashMap<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        repository = mock(IndicatorConfigRepository.class);
        service = new IndicatorConfigService(repository);
        alice = user(7L);
        bob = user(8L);
        fakeRepository();
    }

    // ===================== Jeux de donnees =====================

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    /** Reglages par defaut de l'onglet Analyse technique. */
    private static PeriodsInput defaults() {
        return new PeriodsInput(20.0, 50.0, 20.0, 14.0, 12.0, 26.0, 9.0, 20.0, 2.0);
    }

    private static IndicatorVisibility allShown() {
        return new IndicatorVisibility(true, true, true, true, true, true);
    }

    private static IndicatorConfigRequest request(String name) {
        return new IndicatorConfigRequest(name, null, defaults(), allShown());
    }

    private static IndicatorConfigRequest withPeriods(UnaryOperator<PeriodsInput> change) {
        return new IndicatorConfigRequest("Test", null, change.apply(defaults()), allShown());
    }

    private static String messageOf(Runnable call) {
        return assertThrows(IllegalArgumentException.class, call::run).getMessage();
    }

    // ===================== Creer, lire, modifier, supprimer =====================

    @Test
    void creatingStoresEverythingAndTrimsTheText() {
        IndicatorConfigRequest req = new IndicatorConfigRequest("  Court terme  ", "  Pour le day trading  ",
                new PeriodsInput(10.0, 30.0, 9.0, 7.0, 8.0, 21.0, 5.0, 15.0, 2.5),
                new IndicatorVisibility(true, false, true, false, true, false));

        IndicatorConfigView v = service.create(req, alice);

        assertNotNull(v.id());
        assertEquals("Court terme", v.name());
        assertEquals("Pour le day trading", v.description());
        IndicatorPeriods p = v.periods();
        assertEquals(List.of(10, 30, 9, 7, 8, 21, 5, 15), List.of(p.getSmaFast(), p.getSmaSlow(), p.getEma(), p.getRsi(),
                p.getMacdFast(), p.getMacdSlow(), p.getMacdSignal(), p.getBbPeriod()));
        assertEquals(2.5, p.getBbK());
        assertEquals(List.of(true, false, true, false, true, false), List.of(v.show().getSmaFast(), v.show().getSmaSlow(),
                v.show().getEma(), v.show().getBollinger(), v.show().getRsi(), v.show().getMacd()));
        assertNotNull(v.createdAt());
        assertEquals(v.createdAt(), v.updatedAt());
        assertEquals(alice, db.get(v.id()).getOwner());
    }

    @Test
    void aBlankDescriptionIsStoredAsNothing() {
        assertNull(service.create(new IndicatorConfigRequest("Test", "   ", defaults(), allShown()), alice).description());
    }

    @Test
    void theListIsAlphabeticalAndReadingReturnsTheConfiguration() {
        service.create(request("Swing"), alice);
        Long id = service.create(request("Court terme"), alice).id();

        assertEquals(List.of("Court terme", "Swing"), service.findMine(alice).stream().map(IndicatorConfigView::name).toList());
        assertEquals("Court terme", service.findOne(id, alice).name());
    }

    @Test
    void updatingReplacesEverythingAndKeepsTheCreationDate() {
        IndicatorConfigView created = service.create(request("Ancien nom"), alice);
        IndicatorConfig stored = db.get(created.id());
        stored.setCreatedAt(stored.getCreatedAt().minusDays(3));   // creee il y a trois jours

        IndicatorConfigView updated = service.update(created.id(), new IndicatorConfigRequest("Nouveau nom", "Note",
                new PeriodsInput(5.0, 10.0, 30.0, 21.0, 5.0, 35.0, 5.0, 30.0, 3.0),
                new IndicatorVisibility(false, false, false, false, true, true)), alice);

        assertEquals(created.id(), updated.id());
        assertEquals("Nouveau nom", updated.name());
        assertEquals("Note", updated.description());
        assertEquals(5, updated.periods().getSmaFast());
        assertEquals(3.0, updated.periods().getBbK());
        assertFalse(updated.show().getSmaFast());
        assertEquals(stored.getCreatedAt(), updated.createdAt(), "date de creation inchangee");
        assertTrue(updated.updatedAt().isAfter(updated.createdAt()), "date de modification mise a jour");
        assertEquals(1, service.findMine(alice).size());
    }

    @Test
    void deletingRemovesTheConfiguration() {
        Long id = service.create(request("Test"), alice).id();
        service.delete(id, alice);
        assertTrue(service.findMine(alice).isEmpty());
        assertThrows(NotFoundException.class, () -> service.findOne(id, alice));
        assertThrows(NotFoundException.class, () -> service.delete(id, alice));
    }

    @Test
    void anUnknownConfigurationIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.findOne(99L, alice));
        assertThrows(NotFoundException.class, () -> service.update(99L, request("Test"), alice));
        assertThrows(NotFoundException.class, () -> service.delete(99L, alice));
    }

    // ===================== Validation =====================

    @Test
    void theNameIsRequiredAndLimited() {
        assertTrue(messageOf(() -> service.create(request(null), alice)).startsWith("name"));
        assertTrue(messageOf(() -> service.create(request("   "), alice)).startsWith("name"));
        assertTrue(messageOf(() -> service.create(request("x".repeat(81)), alice)).contains("80"));
        assertEquals("x".repeat(80), service.create(request("x".repeat(80)), alice).name());
        assertTrue(messageOf(() -> service.create(new IndicatorConfigRequest("Test", "x".repeat(501), defaults(), allShown()), alice))
                .startsWith("description"));
        assertTrue(messageOf(() -> service.create(null, alice)).contains("obligatoire"));
    }

    @Test
    void periodsFollowTheIndicatorEndpointRules() {
        // regles communes (IndicatorRules) : entier >= 2, rapide < lente, 0 < k <= 10
        assertEquals("periods.ema : la période doit être un entier supérieur ou égal à 2", messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 1.0, 14.0, 12.0, 26.0, 9.0, 20.0, 2.0)), alice)));
        assertTrue(messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 20.5, 14.0, 12.0, 26.0, 9.0, 20.0, 2.0)), alice)).startsWith("periods.ema"),
                "20.5 n'est pas un entier : refuse, pas tronque");
        assertEquals("macd : la période rapide doit être plus courte que la période lente", messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 20.0, 14.0, 26.0, 12.0, 9.0, 20.0, 2.0)), alice)));
        assertEquals("periods.bbK : le multiplicateur doit être entre 0 et 10", messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 20.0, 14.0, 12.0, 26.0, 9.0, 20.0, 0.0)), alice)));
        assertTrue(messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 20.0, 14.0, 12.0, 26.0, 9.0, 20.0, Double.NaN)), alice)).startsWith("periods.bbK"));
    }

    @Test
    void savedConfigurationsHaveTwoMoreRules() {
        // la SMA courte doit preceder la longue, et une periode ne depasse pas la limite des strategies (500)
        assertTrue(messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(50.0, 20.0, 20.0, 14.0, 12.0, 26.0, 9.0, 20.0, 2.0)), alice)).startsWith("sma"));
        assertTrue(messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 20.0, 14.0, 12.0, 26.0, 9.0, 501.0, 2.0)), alice)).contains("500"));
        assertEquals(500, service.create(
                withPeriods(p -> new PeriodsInput(20.0, 500.0, 20.0, 14.0, 12.0, 26.0, 9.0, 20.0, 2.0)), alice).periods().getSmaSlow());
    }

    @Test
    void everyFieldIsRequired() {
        assertTrue(messageOf(() -> service.create(new IndicatorConfigRequest("Test", null, null, allShown()), alice)).startsWith("periods"));
        assertTrue(messageOf(() -> service.create(new IndicatorConfigRequest("Test", null, defaults(), null), alice)).startsWith("show"));
        assertTrue(messageOf(() -> service.create(
                withPeriods(p -> new PeriodsInput(20.0, 50.0, 20.0, null, 12.0, 26.0, 9.0, 20.0, 2.0)), alice)).startsWith("periods.rsi"));
        assertTrue(messageOf(() -> service.create(new IndicatorConfigRequest("Test", null, defaults(),
                new IndicatorVisibility(true, true, true, null, true, true)), alice)).startsWith("show.bollinger"));
        verify(repository, never()).saveAndFlush(any());
    }

    // ===================== Nom unique =====================

    @Test
    void aDuplicateNameIsRefusedEvenWithOtherCaseOrSpaces() {
        service.create(request("Court terme"), alice);

        assertThrows(DuplicateConfigNameException.class, () -> service.create(request("Court terme"), alice));
        DuplicateConfigNameException e = assertThrows(DuplicateConfigNameException.class,
                () -> service.create(request("  COURT TERME "), alice));
        assertTrue(e.getMessage().contains("COURT TERME"), e.getMessage());
        assertEquals(1, service.findMine(alice).size());
    }

    @Test
    void renamingToAnotherConfigurationsNameIsRefusedButKeepingItsOwnNameIsAllowed() {
        service.create(request("Court terme"), alice);
        Long id = service.create(request("Swing"), alice).id();

        assertThrows(DuplicateConfigNameException.class, () -> service.update(id, request("court terme"), alice));
        assertEquals("Swing", service.findOne(id, alice).name(), "inchangee apres le refus");
        assertEquals("SWING", service.update(id, request("SWING"), alice).name(), "changer la casse de son propre nom est permis");
    }

    @Test
    void anotherUserMayUseTheSameName() {
        service.create(request("Court terme"), alice);
        assertEquals("Court terme", service.create(request("Court terme"), bob).name());
    }

    @Test
    void theDatabaseConstraintGivesTheSameErrorIfTwoSavesRace() {
        // le controle du service est passe, mais un autre enregistrement a pris le nom entre-temps
        when(repository.existsByOwnerIdAndNameIgnoreCase(anyLong(), anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(IndicatorConfig.class))).thenThrow(new DataIntegrityViolationException("uk_indicator_config_owner_name"));
        assertThrows(DuplicateConfigNameException.class, () -> service.create(request("Court terme"), alice));
    }

    // ===================== Isolation entre utilisateurs =====================

    @Test
    void eachUserOnlySeesTheirOwnConfigurations() {
        Long id = service.create(request("Config d'Alice"), alice).id();

        assertTrue(service.findMine(bob).isEmpty());
        assertThrows(NotFoundException.class, () -> service.findOne(id, bob));
        assertThrows(NotFoundException.class, () -> service.update(id, request("Pirate"), bob));
        assertThrows(NotFoundException.class, () -> service.delete(id, bob));

        assertEquals("Config d'Alice", service.findOne(id, alice).name(), "rien n'a change pour Alice");
        assertEquals(1, service.findMine(alice).size());
    }

    // ===================== Depot simule =====================

    private void fakeRepository() {
        when(repository.saveAndFlush(any(IndicatorConfig.class))).thenAnswer(call -> {
            IndicatorConfig c = call.getArgument(0);
            // ce que font @PrePersist et @PreUpdate en base
            if (c.getId() == null) {
                c.setId(nextId++);
                c.onCreate();
            } else {
                c.onUpdate();
            }
            db.put(c.getId(), c);
            return c;
        });
        when(repository.findByIdAndOwnerId(anyLong(), anyLong())).thenAnswer(call -> Optional
                .ofNullable(db.get(call.<Long>getArgument(0)))
                .filter(c -> c.getOwner().getId().equals(call.<Long>getArgument(1))));
        when(repository.findByOwnerIdOrderByNameAsc(anyLong())).thenAnswer(call -> db.values().stream()
                .filter(c -> c.getOwner().getId().equals(call.<Long>getArgument(0)))
                .sorted(Comparator.comparing(IndicatorConfig::getName, String.CASE_INSENSITIVE_ORDER))
                .toList());
        when(repository.existsByOwnerIdAndNameIgnoreCase(anyLong(), anyString())).thenAnswer(call -> db.values().stream()
                .anyMatch(c -> c.getOwner().getId().equals(call.<Long>getArgument(0))
                        && c.getName().equalsIgnoreCase(call.getArgument(1))));
        when(repository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(anyLong(), anyString(), anyLong())).thenAnswer(call -> db.values()
                .stream().anyMatch(c -> c.getOwner().getId().equals(call.<Long>getArgument(0))
                        && c.getName().equalsIgnoreCase(call.getArgument(1)) && !c.getId().equals(call.<Long>getArgument(2))));
        doAnswer(call -> db.remove(call.<IndicatorConfig>getArgument(0).getId()))
                .when(repository).delete(any(IndicatorConfig.class));
    }
}
