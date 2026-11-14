package tn.esprit.marketsim.user.profile;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, Long> {

    Optional<UserAvatar> findByAvatarKey(String avatarKey);
}
