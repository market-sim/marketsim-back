package tn.esprit.marketsim.analysis.fundamental;

import java.util.List;

/**
 * Module 3 : une facon de lire une donnee dans les faits XBRL d'une societe.
 *
 * <p>Le plus souvent, un seul libelle suffit (ex. {@code us-gaap:NetIncomeLoss}). Certaines donnees sont reparties
 * sur plusieurs libelles qu'il faut additionner : la dette, par exemple, est souvent publiee en deux morceaux
 * (part a moins d'un an + part a plus d'un an). La regle ne s'applique que si TOUS les libelles {@code required}
 * sont presents ; les libelles {@code optional} sont ajoutes quand ils existent (ex. billets de tresorerie).
 *
 * @param required libelles indispensables, sous la forme {@code espace:Nom} ({@code us-gaap:...} ou {@code dei:...})
 * @param optional libelles ajoutes s'ils sont publies
 */
public record XbrlRule(List<String> required, List<String> optional) {

    /** Regle a un seul libelle. */
    public static XbrlRule of(String concept) {
        return new XbrlRule(List.of(concept), List.of());
    }

    /** Somme de plusieurs libelles, tous indispensables. */
    public static XbrlRule sum(String... concepts) {
        return new XbrlRule(List.of(concepts), List.of());
    }

    /** Meme regle, en ajoutant des libelles facultatifs. */
    public XbrlRule plusIfPresent(String... concepts) {
        return new XbrlRule(required, List.of(concepts));
    }
}
