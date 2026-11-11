package tn.esprit.marketsim.user.level.dto;

import java.time.Instant;
import java.util.List;

import tn.esprit.marketsim.user.UserLevel;

public final class UserLevelDtos {

    private UserLevelDtos() {
    }

    public record UserLevelResponse(
            Long userId,
            UserLevel level,
            Integer score,
            boolean insufficientEvidence,
            int successfulLoginCount,
            List<String> reasonCodes,
            String modelVersion,
            Instant computedAt) {
    }
}
