package tn.esprit.marketsim.agent.assistant;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatRequest;
import tn.esprit.marketsim.agent.assistant.AssistantService.ChatResponse;
import tn.esprit.marketsim.agent.assistant.AssistantService.Status;
import tn.esprit.marketsim.user.User;

/** Module 6 : assistant IA conversationnel (briefings, explications, actualites des marches). */
@RestController
@RequestMapping("/api/agents/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    @GetMapping("/status")
    public Status status() {
        return assistantService.status();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, @AuthenticationPrincipal User user) {
        return assistantService.chat(user, req);
    }
}
