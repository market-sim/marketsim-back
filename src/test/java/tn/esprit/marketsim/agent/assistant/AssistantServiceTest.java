package tn.esprit.marketsim.agent.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tn.esprit.marketsim.agent.assistant.AssistantService.ChatMessage;

class AssistantServiceTest {

    @Test
    void sanitizeEnforcesAlternationAndUserFirst() {
        List<ChatMessage> out = AssistantService.sanitize(List.of(
                new ChatMessage("assistant", "Bonjour, je suis Atlas"), // retire : doit commencer par user
                new ChatMessage("user", "Salut"),
                new ChatMessage("user", "quoi de neuf ?"),             // fusionne avec le precedent
                new ChatMessage("assistant", "Voici..."),
                new ChatMessage("user", "  "),                         // vide : ignore
                new ChatMessage("user", "et la bourse ?")));
        assertEquals(3, out.size());
        assertEquals("user", out.get(0).role());
        assertEquals("Salut\n\nquoi de neuf ?", out.get(0).content());
        assertEquals("et la bourse ?", out.get(2).content());
    }

    @Test
    void sanitizeRejectsConversationEndingWithAssistant() {
        assertTrue(AssistantService.sanitize(List.of(
                new ChatMessage("user", "Salut"), new ChatMessage("assistant", "Bonjour"))).isEmpty());
    }

    @Test
    void sanitizeKeepsOnlyRecentHistory() {
        List<ChatMessage> long_ = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long_.add(new ChatMessage("user", "q" + i));
            long_.add(new ChatMessage("assistant", "r" + i));
        }
        long_.add(new ChatMessage("user", "derniere"));
        List<ChatMessage> out = AssistantService.sanitize(long_);
        assertTrue(out.size() <= AssistantService.MAX_HISTORY);
        assertEquals("user", out.get(0).role());
        assertEquals("derniere", out.get(out.size() - 1).content());
    }
}
