package tn.esprit.marketsim.agent.tournament;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentRunRepository extends JpaRepository<TournamentRun, Long> {

    List<TournamentRun> findTop20ByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);
}
