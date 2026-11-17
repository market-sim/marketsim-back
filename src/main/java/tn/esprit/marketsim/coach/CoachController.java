package tn.esprit.marketsim.coach;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatResponse;
import tn.esprit.marketsim.coach.CoachDtos.CoachAnalysis;
import tn.esprit.marketsim.coach.CoachDtos.CoachChatRequest;
import tn.esprit.marketsim.user.User;

/** Coach de trading present sur tous les ecrans. Le symbole est un parametre de requete (il peut contenir « / »). */
@RestController
@RequestMapping("/api/coach")
@RequiredArgsConstructor
public class CoachController {

    private final CoachService coach;

    /** Analyse de la courbe reelle : signal, niveaux, plan acheter / garder / vendre, texte a lire a voix haute. */
    @GetMapping("/analysis")
    public CoachAnalysis analysis(@RequestParam String symbol, @RequestParam(required = false) Long agentId,
                                  @AuthenticationPrincipal User user) {
        return coach.analyze(user, symbol, agentId, true);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody CoachChatRequest req, @AuthenticationPrincipal User user) {
        return coach.chat(user, req);
    }
}
