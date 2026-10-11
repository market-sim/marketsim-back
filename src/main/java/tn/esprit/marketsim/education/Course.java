package tn.esprit.marketsim.education;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Module 4 : cours (gere par l'admin, consulte par les utilisateurs). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Course {

    public enum Level { BEGINNER, INTERMEDIATE, ADVANCED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String title;

    @Column(length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    private Level level = Level.BEGINNER;

    private Integer lessons;

    private Integer durationMinutes;

    /** Fabrique utilisee par DataInitializer (pas de constructeur : Jackson l'utiliserait pour le JSON). */
    public static Course of(String title, String description, Level level, int lessons, int durationMinutes) {
        Course c = new Course();
        c.title = title;
        c.description = description;
        c.level = level;
        c.lessons = lessons;
        c.durationMinutes = durationMinutes;
        return c;
    }
}
