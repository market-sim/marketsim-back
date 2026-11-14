package tn.esprit.marketsim.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.notification.NotificationService.NotificationList;
import tn.esprit.marketsim.user.User;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public NotificationList list(@AuthenticationPrincipal User user) {
        return service.list(user.getId());
    }

    /** Page « Toutes les notifications » : 200 dernieres, filtre ALL / UNREAD / SECURITY / PROGRESS / INFO. */
    @GetMapping("/history")
    public NotificationList history(@RequestParam(defaultValue = "ALL") String filter, @AuthenticationPrincipal User user) {
        return service.history(user.getId(), filter);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(user.getId(), id);
    }

    @DeleteMapping("/read")
    public java.util.Map<String, Integer> deleteRead(@AuthenticationPrincipal User user) {
        return java.util.Map.of("deleted", service.deleteRead(user.getId()));
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.markRead(user.getId(), id);
    }

    @PostMapping("/read-all")
    public void readAll(@AuthenticationPrincipal User user) {
        service.markAllRead(user.getId());
    }
}
