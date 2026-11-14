package tn.esprit.marketsim.progression;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.GameHistoryItem;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.ProgressionResponse;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.SettleGameRequest;
import tn.esprit.marketsim.progression.dto.ProgressionDtos.SettleGameResponse;
import tn.esprit.marketsim.user.User;

/** Portefeuille du jeu, niveau du trader et difficulte de l'application. */
@RestController
@RequestMapping("/api/progression")
@RequiredArgsConstructor
public class ProgressionController {

    private final ProgressionService service;
    private final LeaderboardService leaderboard;

    /** Classement public des traders (sans e-mail). */
    @GetMapping("/leaderboard")
    public LeaderboardService.Leaderboard leaderboard(@RequestParam(defaultValue = "50") int limit,
                                                      @AuthenticationPrincipal User user) {
        return leaderboard.leaderboard(user, Math.min(limit, 200));
    }

    @GetMapping("/me")
    public ProgressionResponse me(@AuthenticationPrincipal User user) {
        return service.me(user);
    }

    @GetMapping("/history")
    public List<GameHistoryItem> history(@AuthenticationPrincipal User user) {
        return service.history(user);
    }

    @PostMapping("/games")
    public SettleGameResponse settle(@Valid @RequestBody SettleGameRequest req, @AuthenticationPrincipal User user) {
        return service.settle(user, req);
    }

    /** Recommencer a 1 000 $ (force=true : recommencer volontairement meme sans etre ruine). */
    @PostMapping("/restart")
    public ProgressionResponse restart(@RequestParam(defaultValue = "false") boolean force,
                                       @AuthenticationPrincipal User user) {
        return service.restart(user, force);
    }
}
