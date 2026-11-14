package tn.esprit.marketsim.user;

/**
 * Regle des mots de passe choisis par les utilisateurs (inscription, changement, reinitialisation, creation
 * par l'admin) : au moins 8 caracteres, dont au moins une lettre et un chiffre. Les mots de passe existants
 * restent valides ; la regle s'applique au prochain changement.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final String RULE = "8 caractères minimum, avec au moins une lettre et un chiffre";

    private PasswordPolicy() {
    }

    /** Message d'erreur si le mot de passe ne respecte pas la regle, sinon null. */
    public static String problem(String password) {
        if (password == null || password.length() < MIN_LENGTH) return "Mot de passe trop court : " + RULE + ".";
        if (password.chars().noneMatch(Character::isLetter)) return "Ajoute au moins une lettre : " + RULE + ".";
        if (password.chars().noneMatch(Character::isDigit)) return "Ajoute au moins un chiffre : " + RULE + ".";
        return null;
    }

    public static void check(String password) {
        String p = problem(password);
        if (p != null) throw new IllegalArgumentException(p);
    }
}
