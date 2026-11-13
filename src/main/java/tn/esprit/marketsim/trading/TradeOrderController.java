package tn.esprit.marketsim.trading;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.CreateOrderRequest;
import tn.esprit.marketsim.trading.TradingDtos.OrderResponse;
import tn.esprit.marketsim.trading.TradingDtos.RejectedOrderResponse;
import tn.esprit.marketsim.trading.TradingDtos.UpdateOrderRequest;
import tn.esprit.marketsim.user.User;

/** Module 2 : ordres de l'utilisateur connecte (le proprietaire vient toujours du JWT). */
@RestController
@RequestMapping("/api/trading/orders")
@RequiredArgsConstructor
public class TradeOrderController {

    private final TradeOrderService service;

    /** Toujours un tableau (compatible avec le front), filtres optionnels. */
    @GetMapping
    public List<OrderResponse> list(@AuthenticationPrincipal User user,
                                    @RequestParam(required = false) Status status,
                                    @RequestParam(required = false) String symbol,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.findMine(user, status, symbol, from, to);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.findOneDto(id, user);
    }

    /**
     * Le prix, le statut et le proprietaire ne sont jamais lus dans le corps. Ordre refuse (solde insuffisant,
     * risque bloquant) : 400 avec le message et le ticket REJECTED enregistre.
     */
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateOrderRequest request, @AuthenticationPrincipal User user) {
        OrderResponse order = service.create(request, user);
        if (order.status() == Status.REJECTED) {
            return ResponseEntity.badRequest().body(new RejectedOrderResponse(400, order.rejectReason(), order));
        }
        return ResponseEntity.ok(order);
    }

    /** Cloture d'une position OPEN au prix courant (closeReason = MANUAL). */
    @PostMapping("/{id}/close")
    public OrderResponse close(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.close(id, user);
    }

    /** Mise a jour partielle du stop-loss / take-profit uniquement. */
    @PutMapping("/{id}")
    public OrderResponse update(@PathVariable Long id, @Valid @RequestBody UpdateOrderRequest request,
                                @AuthenticationPrincipal User user) {
        return service.update(id, request, user);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.cancel(id, user);
    }

    /** Ne supprime plus rien : annule un ordre PENDING ; 409 sur une position OPEN (utiliser /close). */
    @DeleteMapping("/{id}")
    public OrderResponse delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return service.cancel(id, user);
    }
}
