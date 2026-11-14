package tn.esprit.marketsim.notification;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Notification affichee sous la cloche (niveau, portefeuille, securite du compte, pilote automatique). */
@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notifications_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    public enum Type { LEVEL_UP, LEVEL_DOWN, LOW_BALANCE, BANKRUPT, NEW_DEVICE, PASSWORD_CHANGED, TWO_FACTOR, AUTOPILOT, INFO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 500)
    private String message;

    /** Page a ouvrir au clic (ex. /progression, /security). */
    @Column(length = 120)
    private String link;

    private Instant createdAt;
    private Instant readAt;
}
