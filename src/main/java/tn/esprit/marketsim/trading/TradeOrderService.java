package tn.esprit.marketsim.trading;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;

@Service
@RequiredArgsConstructor
public class TradeOrderService {

    private final TradeOrderRepository repository;

    public List<TradeOrder> findMine(User user) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(user.getId());
    }

    public TradeOrder findOne(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Ordre introuvable"));
    }

    public TradeOrder create(TradeOrder order, User user) {
        order.setId(null);
        order.setOwner(user);
        // TODO (IA module 2) : appeler le modele de prediction du risque avant execution
        return repository.save(order);
    }

    public TradeOrder update(Long id, TradeOrder data, User user) {
        TradeOrder order = findOne(id, user);
        order.setStopLoss(data.getStopLoss());
        order.setTakeProfit(data.getTakeProfit());
        if (data.getStatus() != null) {
            order.setStatus(data.getStatus());
        }
        return repository.save(order);
    }

    public void delete(Long id, User user) {
        repository.delete(findOne(id, user));
    }
}
