package tn.esprit.marketsim.agent.autopilot;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfigService;
import tn.esprit.marketsim.agent.autopilot.AutopilotService.AutopilotView;
import tn.esprit.marketsim.agent.autopilot.AutopilotService.StartRequest;
import tn.esprit.marketsim.user.User;

/** Module 6 : pilote automatique (l'agent trade seul, avec garde-fous). */
@RestController
@RequestMapping("/api/agents/{id}/autopilot")
@RequiredArgsConstructor
public class AutopilotController {

    private final AgentConfigService agentService;
    private final AutopilotService autopilotService;

    /** Etat de la derniere session (204 si l'agent n'en a jamais eu). */
    @GetMapping
    public ResponseEntity<AutopilotView> view(@PathVariable Long id, @AuthenticationPrincipal User user) {
        AutopilotView view = autopilotService.view(agentService.findOne(id, user));
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    @PostMapping("/start")
    public AutopilotView start(@PathVariable Long id, @Valid @RequestBody StartRequest req, @AuthenticationPrincipal User user) {
        return autopilotService.start(agentService.findOne(id, user), req);
    }

    @PostMapping("/pause")
    public AutopilotView pause(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return autopilotService.pause(agentService.findOne(id, user));
    }

    @PostMapping("/resume")
    public AutopilotView resume(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return autopilotService.resume(agentService.findOne(id, user));
    }

    @PostMapping("/stop")
    public AutopilotView stop(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return autopilotService.stop(agentService.findOne(id, user));
    }

    /** Avance d'un jour immediatement (demonstration). */
    @PostMapping("/step")
    public AutopilotView step(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return autopilotService.stepNow(agentService.findOne(id, user));
    }
}
