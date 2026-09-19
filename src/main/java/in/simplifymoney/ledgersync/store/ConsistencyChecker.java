package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Proves the SQL store and DocumentStore agree, and names precisely where they do not.
 *
 * Checks:
 *  - Missing transactions in either store
 *  - Field-level attribute discrepancies (category, merchant, amount, direction, source_messages)
 *  - Account running category totals agreement
 *  - Message ID index resolution
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // 1. Gather deduplicated transactions from SQL store
        List<NormalizedTxn> sqlRows = sql.all();
        Map<TxnKey, NormalizedTxn> sqlTxns = new HashMap<>();
        Set<String> accounts = new HashSet<>();
        Set<YearMonth> months = new HashSet<>();

        for (NormalizedTxn t : sqlRows) {
            TxnKey key = new TxnKey(
                    t.accountLast4(),
                    t.occurredAt().withSecond(0).withNano(0),
                    t.direction(),
                    t.amount()
            );
            sqlTxns.putIfAbsent(key, t);
            accounts.add(t.accountLast4());
            months.add(YearMonth.from(t.occurredAt()));
        }

        // 2. Gather transactions from DocumentStore across all accounts and months
        Set<String> allAccounts = new HashSet<>(accounts);
        Set<YearMonth> allMonths = new HashSet<>(months);
        if (documents instanceof DynamoDocumentStore dds) {
            allAccounts.addAll(dds.allAccounts());
            allMonths.addAll(dds.allMonths());
        }

        Map<TxnKey, NormalizedTxn> docTxns = new HashMap<>();
        for (String acct : allAccounts) {
            for (YearMonth ym : allMonths) {
                List<NormalizedTxn> inDoc = documents.forAccountMonth(acct, ym);
                for (NormalizedTxn t : inDoc) {
                    TxnKey key = new TxnKey(
                            t.accountLast4(),
                            t.occurredAt().withSecond(0).withNano(0),
                            t.direction(),
                            t.amount()
                    );
                    docTxns.put(key, t);
                }
            }
        }

        // 3. Verify presence and attribute agreement
        for (Map.Entry<TxnKey, NormalizedTxn> entry : sqlTxns.entrySet()) {
            TxnKey key = entry.getKey();
            NormalizedTxn sqlTxn = entry.getValue();
            NormalizedTxn docTxn = docTxns.get(key);

            if (docTxn == null) {
                divergences.add(new Divergence(
                        "missing_in_documents: " + key,
                        sqlTxn.toString(),
                        "<not_found>"
                ));
                continue;
            }

            // Field-level comparisons
            if (sqlTxn.category() != docTxn.category()) {
                divergences.add(new Divergence(
                        "category_mismatch: " + key,
                        sqlTxn.category().name(),
                        docTxn.category().name()
                ));
            }

            if (!sqlTxn.merchant().equals(docTxn.merchant())) {
                divergences.add(new Divergence(
                        "merchant_mismatch: " + key,
                        sqlTxn.merchant(),
                        docTxn.merchant()
                ));
            }

            if (!new HashSet<>(sqlTxn.sourceMessageIds()).equals(new HashSet<>(docTxn.sourceMessageIds()))) {
                divergences.add(new Divergence(
                        "source_message_ids_mismatch: " + key,
                        sqlTxn.sourceMessageIds().toString(),
                        docTxn.sourceMessageIds().toString()
                ));
            }
        }

        // 4. Verify no unexpected extraneous transactions in DocumentStore
        for (Map.Entry<TxnKey, NormalizedTxn> entry : docTxns.entrySet()) {
            TxnKey key = entry.getKey();
            if (!sqlTxns.containsKey(key)) {
                divergences.add(new Divergence(
                        "unexpected_in_documents: " + key,
                        "<none>",
                        entry.getValue().toString()
                ));
            }
        }

        // 5. Verify running category totals
        for (String acct : accounts) {
            Map<Category, BigDecimal> expectedTotals = new EnumMap<>(Category.class);
            for (Category c : Category.values()) expectedTotals.put(c, BigDecimal.ZERO.setScale(2));

            for (NormalizedTxn t : sqlTxns.values()) {
                if (t.accountLast4().equals(acct)) {
                    expectedTotals.put(t.category(), expectedTotals.get(t.category()).add(t.amount()));
                }
            }

            Map<Category, BigDecimal> actualTotals = documents.categoryTotals(acct);
            for (Category c : Category.values()) {
                BigDecimal exp = expectedTotals.get(c);
                BigDecimal act = actualTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (exp.compareTo(act) != 0) {
                    divergences.add(new Divergence(
                            "category_total_divergence: acct=" + acct + " cat=" + c,
                            exp.toPlainString(),
                            act.toPlainString()
                    ));
                }
            }
        }

        // 6. Verify message ID index lookup
        for (NormalizedTxn sqlTxn : sqlTxns.values()) {
            for (String msgId : sqlTxn.sourceMessageIds()) {
                Optional<NormalizedTxn> byMsg = documents.byMessageId(msgId);
                if (byMsg.isEmpty()) {
                    divergences.add(new Divergence(
                            "message_lookup_missing: " + msgId,
                            sqlTxn.toString(),
                            "<empty>"
                    ));
                }
            }
        }

        return divergences;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}

    private record TxnKey(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount) {}
}
