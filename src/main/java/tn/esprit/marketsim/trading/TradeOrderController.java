package tn.esprit.marketsim.trading;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;

@RestController
@RequestMapping("/api/trading/orders")
@RequiredArgsConstructor
public class TradeOrderController {

    private final TradeOrderService service;

    @GetMapping
    public List<TradeOrder> list(@AuthenticationPrincipal User user) {
        return service.findMine(user);
    }

    @GetMapping("/{id}")
    public TradeOrder get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOne(id, user);
    }

    @PostMapping
    public TradeOrder create(@Valid @RequestBody TradeOrder order, @AuthenticationPrincipal User user) {
        return service.create(order, user);
    }

    @PutMapping("/{id}")
    public TradeOrder update(@PathVariable Long id, @RequestBody TradeOrder order, @AuthenticationPrincipal User user) {
        return service.update(id, order, user);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
    }
}
