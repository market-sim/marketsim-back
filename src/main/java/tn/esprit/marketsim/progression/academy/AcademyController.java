package tn.esprit.marketsim.progression.academy;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.assistant.AssistantService;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatMessage;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatResponse;
import tn.esprit.marketsim.progression.ProgressionService;
import tn.esprit.marketsim.progression.academy.AcademyAdvisor.Advice;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;

/** Academie du trading : parcours personnalise (selon les vraies parties) et mentor IA « Max ». */
@RestController
@RequestMapping("/api/academy")
@RequiredArgsConstructor
public class AcademyController {

    private static final Pattern REVISION = Pattern.compile(
            "r[ée]vis|recommand|conseill|faible|lacune|travailler|prochaine?|par o[uù]|quoi faire|mes erreurs|progresser",
            Pattern.CASE_INSENSITIVE);

    private final ProgressionService progression;
    private final AssistantService assistant;

    /**
     * @param academyContext progression dans le jeu (stations terminees, erreurs au quiz, station en cours),
     *                       envoyee par la page ; simple information pour le mentor
     */
    public record MentorRequest(@NotEmpty List<@Valid ChatMessage> messages, @Size(max = 4000) String academyContext) {
    }

    @GetMapping("/advice")
    public Advice advice(@AuthenticationPrincipal User user) {
        return adviceFor(user);
    }

    @PostMapping("/mentor")
    public ChatResponse mentor(@Valid @RequestBody MentorRequest req, @AuthenticationPrincipal User user) {
        Advice advice = adviceFor(user);
        String context = (req.academyContext() == null ? "" : req.academyContext().trim() + "\n") + describe(advice);
        return assistant.chatMentor(user, req.messages(), context, question -> offline(question, advice));
    }

    /** L'administrateur n'a pas de partie : parcours neutre au lieu d'une erreur 403. */
    Advice adviceFor(User user) {
        if (user.getRole() == Role.ADMIN) {
            return AcademyAdvisor.advise(null, List.of());
        }
        try {
            return AcademyAdvisor.advise(progression.peek(user).orElse(null), progression.history(user));
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) return AcademyAdvisor.advise(null, List.of());
            throw e;
        }
    }

    static String describe(Advice a) {
        StringBuilder sb = new StringBuilder("Parcours personnalisé (calculé sur les vraies parties du Jeu de trading) : ");
        sb.append(a.games()).append(" partie(s) jouée(s)");
        if (a.gamesVsAgent() > 0) {
            sb.append(", agent IA battu ").append(a.beatAgent()).append(" fois sur ").append(a.gamesVsAgent());
        }
        sb.append(a.secretUnlocked() ? ", Salle des Experts débloquée.\n" : ".\n");
        if (a.recommendations().isEmpty()) {
            sb.append("Aucune faiblesse détectée.");
        }
        for (AcademyAdvisor.Recommendation r : a.recommendations()) {
            sb.append("- ").append(AcademyAdvisor.STATION_NAMES.getOrDefault(r.stationId(), r.stationId()))
                    .append(" (priorité ").append(r.priority()).append(") : ").append(String.join(" ", r.reasons())).append('\n');
        }
        return sb.toString();
    }

    /** Sans cle API : Max repond lui-meme aux questions « quoi reviser ? » ; le reste passe a l'assistant local. */
    static Optional<String> offline(String question, Advice a) {
        if (question == null || !REVISION.matcher(question).find()) return Optional.empty();
        if (a.games() == 0) {
            return Optional.of("Tu n'as pas encore joué de partie, donc je ne connais pas encore tes points faibles. "
                    + "Suis le losange doré pour terminer les stations dans l'ordre, puis fais une partie dans le Jeu de trading : "
                    + "je te dirai ensuite quoi réviser. Prêt pour la Place du Marché ?");
        }
        if (a.recommendations().isEmpty()) {
            return Optional.of("Bonne nouvelle : je ne vois pas de point faible dans tes " + a.games() + " parties. "
                    + "Continue à appliquer ton plan, et vise le Volcan du Krach pour t'entraîner au pire scénario. "
                    + "Tu sais combien il faut gagner pour revenir après une perte de 50 % ?");
        }
        StringBuilder sb = new StringBuilder("J'ai regardé tes " + a.games() + " parties. Voilà ce que je te conseille :");
        a.recommendations().stream().limit(3).forEach(r -> sb.append("\n- ")
                .append(AcademyAdvisor.STATION_NAMES.getOrDefault(r.stationId(), r.stationId())).append(" : ")
                .append(r.reasons().get(0)));
        sb.append("\nLes bâtiments conseillés clignotent en rouge sur la mini-map. Tu commences par lequel ?");
        return Optional.of(sb.toString());
    }
}
