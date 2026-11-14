package tn.esprit.marketsim.support;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;

/** Messagerie de support : /api/support pour le trader (et la lecture/reponse admin), /api/admin/support pour l'administration. */
@RestController
@RequiredArgsConstructor
public class SupportController {

    private final SupportService service;

    public record StatusRequest(String status) {
    }

    @GetMapping("/api/support/tickets")
    public List<SupportService.TicketView> mine(@AuthenticationPrincipal User user) {
        return service.mine(user);
    }

    @PostMapping("/api/support/tickets")
    public SupportService.Thread create(@RequestBody SupportService.CreateRequest req, @AuthenticationPrincipal User user) {
        return service.create(user, req);
    }

    @GetMapping("/api/support/tickets/{id}")
    public SupportService.Thread open(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.open(user, id);
    }

    @PostMapping("/api/support/tickets/{id}/messages")
    public SupportService.Thread reply(@PathVariable Long id, @RequestBody SupportService.ReplyRequest req, @AuthenticationPrincipal User user) {
        return service.reply(user, id, req);
    }

    @GetMapping("/api/support/unread")
    public Map<String, Long> unread(@AuthenticationPrincipal User user) {
        return Map.of("count", service.unreadCount(user));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/support/tickets")
    public List<SupportService.TicketView> all(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
                                               @AuthenticationPrincipal User admin) {
        return service.all(admin, status, category);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/support/tickets/{id}/status")
    public SupportService.Thread status(@PathVariable Long id, @RequestBody StatusRequest req, @AuthenticationPrincipal User admin) {
        return service.setStatus(admin, id, req.status());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/support/tickets/{id}/decision")
    public SupportService.Thread decide(@PathVariable Long id, @RequestBody SupportService.DecisionRequest req, @AuthenticationPrincipal User admin) {
        return service.decide(admin, id, req);
    }
}
