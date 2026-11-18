package tn.esprit.marketsim.analysis.fundamental;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyFundamentalsRepository extends JpaRepository<CompanyFundamentals, Long> {

    Optional<CompanyFundamentals> findBySymbol(String symbol);
}
