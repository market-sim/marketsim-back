package tn.esprit.marketsim.analysis.indicator;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.IndicatorConfigRequest;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.IndicatorConfigView;
import tn.esprit.marketsim.analysis.indicator.IndicatorConfigDtos.PeriodsInput;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : configurations d'indicateurs de l'utilisateur (creer, lister, lire, modifier, supprimer).
 *
 * <p>Validation : les regles de l'endpoint des indicateurs ({@link IndicatorRules}), plus deux regles propres
 * a une configuration enregistree : SMA courte plus courte que la SMA longue, et periodes d'au plus
 * {@value IndicatorRules#MAX_SAVED_PERIOD} seances (limite des strategies). Une {@link IllegalArgumentException}
 * devient un HTTP 400, une {@link NotFoundException} un 404, une {@link DuplicateConfigNameException} un 409.
 */
@Service
@RequiredArgsConstructor
public class IndicatorConfigService {

    static final int NAME_MAX = 80;
    static final int DESCRIPTION_MAX = 500;

    private final IndicatorConfigRepository repository;

    // ===================== Lire =====================

    /** Mes configurations, par ordre alphabetique. */
    @Transactional(readOnly = true)
    public List<IndicatorConfigView> findMine(User user) {
        return repository.findByOwnerIdOrderByNameAsc(user.getId()).stream().map(IndicatorConfigView::of).toList();
    }

    @Transactional(readOnly = true)
    public IndicatorConfigView findOne(Long id, User user) {
        return IndicatorConfigView.of(mine(id, user));
    }

    // ===================== Ecrire =====================

    @Transactional
    public IndicatorConfigView create(IndicatorConfigRequest req, User user) {
        Valid v = validate(req);
        if (repository.existsByOwnerIdAndNameIgnoreCase(user.getId(), v.name())) {
            throw new DuplicateConfigNameException(v.name());
        }
        IndicatorConfig c = new IndicatorConfig();
        c.setOwner(user);
        apply(c, v);
        return IndicatorConfigView.of(saveChecked(c, v.name()));
    }

    /** Remplace toute la configuration (sert a « Mettre a jour » comme a « Renommer »). */
    @Transactional
    public IndicatorConfigView update(Long id, IndicatorConfigRequest req, User user) {
        Valid v = validate(req);
        IndicatorConfig c = mine(id, user);
        if (repository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(user.getId(), v.name(), c.getId())) {
            throw new DuplicateConfigNameException(v.name());
        }
        apply(c, v);
        return IndicatorConfigView.of(saveChecked(c, v.name()));
    }

    @Transactional
    public void delete(Long id, User user) {
        repository.delete(mine(id, user));
    }

    // ===================== Validation =====================

    /** Saisie validee : nom et description nettoyes, periodes et affichage complets. */
    record Valid(String name, String description, IndicatorPeriods periods, IndicatorVisibility show) {
    }

    /** Controle toute la saisie ; le premier champ en erreur est nomme dans le message. */
    static Valid validate(IndicatorConfigRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("La configuration est obligatoire");
        }
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name : le nom de la configuration est obligatoire");
        }
        if (name.length() > NAME_MAX) {
            throw new IllegalArgumentException("name : " + NAME_MAX + " caractères au maximum");
        }
        String description = req.description() == null || req.description().isBlank() ? null : req.description().strip();
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new IllegalArgumentException("description : " + DESCRIPTION_MAX + " caractères au maximum");
        }
        return new Valid(name, description, periods(req.periods()), show(req.show()));
    }

    private static IndicatorPeriods periods(PeriodsInput p) {
        if (p == null) {
            throw new IllegalArgumentException("periods : les périodes des indicateurs sont obligatoires");
        }
        int smaFast = savedPeriod("periods.smaFast", p.smaFast());
        int smaSlow = savedPeriod("periods.smaSlow", p.smaSlow());
        int ema = savedPeriod("periods.ema", p.ema());
        int rsi = savedPeriod("periods.rsi", p.rsi());
        int macdFast = savedPeriod("periods.macdFast", p.macdFast());
        int macdSlow = savedPeriod("periods.macdSlow", p.macdSlow());
        int macdSignal = savedPeriod("periods.macdSignal", p.macdSignal());
        int bbPeriod = savedPeriod("periods.bbPeriod", p.bbPeriod());
        // l'endpoint recoit une liste de SMA ; ici les deux ont un role : la courte doit preceder la longue
        IndicatorRules.fastBeforeSlow("sma", smaFast, smaSlow);
        IndicatorRules.fastBeforeSlow("macd", macdFast, macdSlow);
        double bbK = IndicatorRules.bandK("periods.bbK", required("periods.bbK", p.bbK()));
        return new IndicatorPeriods(smaFast, smaSlow, ema, rsi, macdFast, macdSlow, macdSignal, bbPeriod, bbK);
    }

    /** Periode obligatoire : regle commune ({@link IndicatorRules#period}), puis la limite des strategies. */
    private static int savedPeriod(String name, Double value) {
        int n = IndicatorRules.period(name, required(name, value));
        if (n > IndicatorRules.MAX_SAVED_PERIOD) {
            throw new IllegalArgumentException(name + " : " + IndicatorRules.MAX_SAVED_PERIOD
                    + " au maximum (limite des stratégies)");
        }
        return n;
    }

    private static double required(String name, Number value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " : valeur obligatoire");
        }
        return value.doubleValue();
    }

    private static IndicatorVisibility show(IndicatorVisibility s) {
        if (s == null) {
            throw new IllegalArgumentException("show : les indicateurs affichés sont obligatoires");
        }
        return new IndicatorVisibility(flag("show.smaFast", s.getSmaFast()), flag("show.smaSlow", s.getSmaSlow()),
                flag("show.ema", s.getEma()), flag("show.bollinger", s.getBollinger()), flag("show.rsi", s.getRsi()),
                flag("show.macd", s.getMacd()));
    }

    private static boolean flag(String name, Boolean value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " : valeur obligatoire (true ou false)");
        }
        return value;
    }

    // ===================== Outils =====================

    /** Un utilisateur ne voit que ses propres configurations : celle d'un autre est "introuvable". */
    private IndicatorConfig mine(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Configuration introuvable"));
    }

    private static void apply(IndicatorConfig c, Valid v) {
        c.setName(v.name());
        c.setDescription(v.description());
        c.setPeriods(v.periods());
        c.setShow(v.show());
    }

    /**
     * Enregistre tout de suite (saveAndFlush) : si deux enregistrements simultanes prennent le meme nom,
     * la contrainte d'unicite en base refuse le second ici, et l'erreur devient le meme 409.
     */
    private IndicatorConfig saveChecked(IndicatorConfig c, String name) {
        try {
            return repository.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateConfigNameException(name);
        }
    }
}
