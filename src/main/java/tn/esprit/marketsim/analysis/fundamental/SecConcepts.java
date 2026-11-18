package tn.esprit.marketsim.analysis.fundamental;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Module 3 : pour chaque donnee, la liste ORDONNEE des regles XBRL a essayer (la premiere qui s'applique gagne).
 *
 * <p>Les societes ne publient pas toutes une meme donnee sous le meme libelle : Apple publie son chiffre d'affaires
 * sous {@code RevenueFromContractWithCustomerExcludingAssessedTax} depuis 2018, et sous {@code SalesRevenueNet}
 * avant. Le libelle retenu est garde avec chaque valeur, pour pouvoir verifier d'ou elle vient.
 *
 * <h2>Dette financiere totale</h2>
 * C'est la donnee la moins standardisee. Definition retenue : emprunts et obligations, part a moins d'un an comprise,
 * plus les billets de tresorerie et emprunts a court terme ; les contrats de location (leasing) sont exclus.
 * <ul>
 *   <li>Apple : {@code LongTermDebtCurrent} + {@code LongTermDebtNoncurrent} + {@code CommercialPaper}.</li>
 *   <li>Tesla : {@code DebtCurrent} + {@code LongTermDebt}. Chez Tesla, {@code LongTermDebt} ne contient que la part a
 *       plus d'un an (verifie sur son bilan 10-K), alors que la taxonomie le definit part courante comprise : cette
 *       regle passe donc APRES celles qui utilisent les libelles sans ambiguite.</li>
 *   <li>En dernier recours, {@code LongTermDebt} seul (part courante comprise, selon la taxonomie).</li>
 * </ul>
 */
public final class SecConcepts {

    private SecConcepts() {
    }

    public static final Map<FundamentalItem, List<XbrlRule>> RULES = new EnumMap<>(FundamentalItem.class);

    static {
        RULES.put(FundamentalItem.REVENUE, List.of(
                XbrlRule.of("us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax"),
                XbrlRule.of("us-gaap:Revenues"),
                XbrlRule.of("us-gaap:SalesRevenueNet"),
                XbrlRule.of("us-gaap:RevenueFromContractWithCustomerIncludingAssessedTax"),
                XbrlRule.of("us-gaap:SalesRevenueGoodsNet")));
        // NetIncomeLoss = part du groupe. ProfitLoss inclut les minoritaires : dernier recours seulement. Une societe qui
        // a des minoritaires doit publier les deux, donc ProfitLoss ne sert qu'a celles qui n'en ont pas (meme valeur).
        RULES.put(FundamentalItem.NET_INCOME, List.of(
                XbrlRule.of("us-gaap:NetIncomeLoss"),
                XbrlRule.of("us-gaap:NetIncomeLossAvailableToCommonStockholdersBasic"),
                XbrlRule.of("us-gaap:ProfitLoss")));
        RULES.put(FundamentalItem.EPS_DILUTED, List.of(
                XbrlRule.of("us-gaap:EarningsPerShareDiluted"),
                XbrlRule.of("us-gaap:EarningsPerShareBasicAndDiluted")));
        RULES.put(FundamentalItem.EQUITY, List.of(
                XbrlRule.of("us-gaap:StockholdersEquity"),
                XbrlRule.of("us-gaap:StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest")));
        RULES.put(FundamentalItem.TOTAL_DEBT, List.of(
                XbrlRule.of("us-gaap:DebtLongtermAndShorttermCombinedAmount"),
                XbrlRule.sum("us-gaap:LongTermDebtCurrent", "us-gaap:LongTermDebtNoncurrent")
                        .plusIfPresent("us-gaap:CommercialPaper", "us-gaap:ShortTermBorrowings"),
                XbrlRule.sum("us-gaap:DebtCurrent", "us-gaap:LongTermDebtNoncurrent"),
                XbrlRule.sum("us-gaap:DebtCurrent", "us-gaap:LongTermDebt"),
                XbrlRule.of("us-gaap:LongTermDebt")
                        .plusIfPresent("us-gaap:CommercialPaper", "us-gaap:ShortTermBorrowings")));
        RULES.put(FundamentalItem.SHARES_OUTSTANDING, List.of(
                // page de garde du 10-K : nombre d'actions a une date proche du depot (le plus recent)
                XbrlRule.of("dei:EntityCommonStockSharesOutstanding"),
                XbrlRule.of("us-gaap:CommonStockSharesOutstanding"),
                XbrlRule.of("us-gaap:WeightedAverageNumberOfDilutedSharesOutstanding")));
        RULES.put(FundamentalItem.DIVIDEND_PER_SHARE, List.of(
                XbrlRule.of("us-gaap:CommonStockDividendsPerShareDeclared"),
                XbrlRule.of("us-gaap:CommonStockDividendsPerShareCashPaid")));
    }
}
