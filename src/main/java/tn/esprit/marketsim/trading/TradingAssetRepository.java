package tn.esprit.marketsim.trading;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import tn.esprit.marketsim.market.Asset;

/** Lecture seule des actifs du module 5, depuis le module 2 (sans modifier le module 5). */
public interface TradingAssetRepository extends Repository<Asset, Long> {

    Optional<Asset> findFirstBySymbolIgnoreCase(String symbol);
}
