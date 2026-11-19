package tn.esprit.marketsim.analysis.fundamental;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Module 3 : lit le nombre d'actions dans l'« instance XBRL » d'un 10-K (fichier {@code *_htm.xml} du depot),
 * fonctions pures testables sans reseau.
 *
 * <h2>Pourquoi ce second fichier</h2>
 * Le fichier « companyfacts » de la SEC ne garde que les faits sans dimension. Une societe a plusieurs categories
 * d'actions publie son nombre d'actions categorie par categorie (axe {@code StatementClassOfStockAxis}) : Alphabet
 * (classes A, B et C) et Meta (A et B) n'y ont donc aucun nombre d'actions en page de garde. L'instance du depot,
 * elle, contient tous les faits, avec leur contexte (date et dimensions).
 *
 * <h2>Total d'un nombre d'actions a une date</h2>
 * <ol>
 *   <li>le fait sans dimension, s'il existe (il donne deja le total) ;</li>
 *   <li>sinon le total du tableau de variation des capitaux propres (seule dimension
 *       {@code StatementEquityComponentsAxis = CommonStockMember}) ;</li>
 *   <li>sinon la somme des categories (seule dimension {@code StatementClassOfStockAxis}, une valeur par categorie).</li>
 * </ol>
 */
public final class XbrlInstanceParser {

    static final String INSTANCE_NS = "http://www.xbrl.org/2003/instance";
    static final String DIMENSION_NS = "http://xbrl.org/2006/xbrldi";
    static final String DEI_NS_PREFIX = "http://xbrl.sec.gov/dei/";
    static final String US_GAAP_NS_PREFIX = "http://fasb.org/us-gaap/";

    static final String CLASS_AXIS = "StatementClassOfStockAxis";
    static final String EQUITY_AXIS = "StatementEquityComponentsAxis";
    static final String COMMON_STOCK_MEMBER = "CommonStockMember";

    private XbrlInstanceParser() {
    }

    /**
     * Un total et la facon dont il a ete obtenu.
     *
     * @param how vide (fait sans dimension), « somme de 3 catégories » ou « tableau des capitaux propres »
     */
    public record Total(double value, String how) {

        /** Precision a ajouter au libelle XBRL, ex. « (somme de 3 catégories) ». */
        public String labelSuffix() {
            return how.isEmpty() ? "" : " (" + how + ")";
        }
    }

    /**
     * Nombres d'actions lus dans l'instance.
     *
     * @param coverDate date du nombre d'actions de la page de garde (quelques semaines apres la cloture), ou null
     * @param cover     total de la page de garde ({@code dei:EntityCommonStockSharesOutstanding}), ou null
     * @param yearEnd   total en circulation a chaque date de bilan ({@code us-gaap:CommonStockSharesOutstanding})
     */
    public record InstanceShares(LocalDate coverDate, Total cover, Map<LocalDate, Total> yearEnd) {
    }

    /** Contexte XBRL : date (instant ou fin de periode) et dimensions (axe -> membre, noms sans prefixe). */
    record Context(LocalDate date, Map<String, String> dimensions) {
    }

    /** Fait lu : page de garde ou bilan, contexte, valeur. */
    record Fact(boolean cover, String contextRef, double value) {
    }

    /**
     * @throws IllegalArgumentException si le fichier n'est pas un XML lisible
     */
    public static InstanceShares parse(String xml) {
        Map<String, Context> contexts = new HashMap<>();
        List<Fact> facts = new ArrayList<>();
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            // fichier venu d'Internet : ni DTD ni entite externe (protection contre les attaques XXE)
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = factory.createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String ns = r.getNamespaceURI() == null ? "" : r.getNamespaceURI();
                String name = r.getLocalName();
                if (INSTANCE_NS.equals(ns) && "context".equals(name)) {
                    String id = r.getAttributeValue(null, "id");
                    contexts.put(id, readContext(r));
                } else if (ns.startsWith(DEI_NS_PREFIX) && "EntityCommonStockSharesOutstanding".equals(name)) {
                    readFact(r, true).ifPresent(facts::add);
                } else if (ns.startsWith(US_GAAP_NS_PREFIX) && "CommonStockSharesOutstanding".equals(name)) {
                    readFact(r, false).ifPresent(facts::add);
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw new IllegalArgumentException("Instance XBRL illisible : " + e.getMessage(), e);
        }
        return summarize(contexts, facts);
    }

    /** Lit un contexte jusqu'a sa balise fermante : date et dimensions. */
    private static Context readContext(XMLStreamReader r) throws XMLStreamException {
        LocalDate date = null;
        Map<String, String> dims = new LinkedHashMap<>();
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.END_ELEMENT && "context".equals(r.getLocalName())) break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            String name = r.getLocalName();
            if ("instant".equals(name) || "endDate".equals(name)) {
                date = LocalDate.parse(r.getElementText().trim());
            } else if (DIMENSION_NS.equals(r.getNamespaceURI()) && "explicitMember".equals(name)) {
                String axis = r.getAttributeValue(null, "dimension");
                dims.put(localName(axis), localName(r.getElementText().trim()));
            }
        }
        return new Context(date, dims);
    }

    private static Optional<Fact> readFact(XMLStreamReader r, boolean cover) throws XMLStreamException {
        String contextRef = r.getAttributeValue(null, "contextRef");
        String nil = r.getAttributeValue("http://www.w3.org/2001/XMLSchema-instance", "nil");
        String text = r.getElementText().trim();
        if ("true".equals(nil) || text.isEmpty()) return Optional.empty();
        return Optional.of(new Fact(cover, contextRef, Double.parseDouble(text)));
    }

    /** « us-gaap:CommonClassAMember » -> « CommonClassAMember ». */
    static String localName(String qname) {
        int colon = qname.indexOf(':');
        return colon < 0 ? qname : qname.substring(colon + 1);
    }

    // ===================== Totaux =====================

    static InstanceShares summarize(Map<String, Context> contexts, List<Fact> facts) {
        TreeMap<LocalDate, List<Located>> cover = new TreeMap<>();
        TreeMap<LocalDate, List<Located>> yearEnd = new TreeMap<>();
        for (Fact f : facts) {
            Context c = contexts.get(f.contextRef());
            if (c == null || c.date() == null) continue;
            (f.cover() ? cover : yearEnd).computeIfAbsent(c.date(), d -> new ArrayList<>())
                    .add(new Located(c.dimensions(), f.value()));
        }
        // page de garde : une seule date en pratique ; s'il y en a plusieurs, la plus recente
        LocalDate coverDate = cover.isEmpty() ? null : cover.lastKey();
        Total coverTotal = coverDate == null ? null : total(cover.get(coverDate));

        Map<LocalDate, Total> totals = new TreeMap<>();
        yearEnd.forEach((date, list) -> {
            Total t = total(list);
            if (t != null) totals.put(date, t);
        });
        return new InstanceShares(coverTotal == null ? null : coverDate, coverTotal, totals);
    }

    /** Valeur publiee dans un contexte. */
    record Located(Map<String, String> dimensions, double value) {
    }

    /** Total a une date, selon l'ordre de la documentation de la classe ; null si rien ne s'applique. */
    static Total total(List<Located> facts) {
        for (Located f : facts) {
            if (f.dimensions().isEmpty()) return new Total(f.value(), "");
        }
        for (Located f : facts) {
            if (f.dimensions().size() == 1 && COMMON_STOCK_MEMBER.equals(f.dimensions().get(EQUITY_AXIS))) {
                return new Total(f.value(), "tableau des capitaux propres");
            }
        }
        // une valeur par categorie : un meme fait peut etre publie deux fois (bilan et annexe)
        Map<String, Double> byClass = new LinkedHashMap<>();
        for (Located f : facts) {
            if (f.dimensions().size() == 1 && f.dimensions().containsKey(CLASS_AXIS)) {
                byClass.putIfAbsent(f.dimensions().get(CLASS_AXIS), f.value());
            }
        }
        if (byClass.isEmpty()) return null;
        double sum = byClass.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Total(sum, byClass.size() == 1 ? "une seule catégorie publiée" : "somme de " + byClass.size() + " catégories");
    }
}
