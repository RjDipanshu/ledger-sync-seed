package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles:
 *  - Legacy duplicate rows in the SQL store without a uniqueness guarantee
 *  - Idempotent repeated execution
 *  - Safe resumption after partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = 0;
        long written = 0;
        long skipped = 0;

        Set<TxnIdentity> seenInSql = new HashSet<>();

        for (NormalizedTxn txn : sqlRows) {
            read++;
            TxnIdentity identity = new TxnIdentity(
                    txn.accountLast4(),
                    txn.occurredAt().withSecond(0).withNano(0),
                    txn.direction(),
                    txn.amount()
            );

            // If already encountered duplicate within SQL store, skip
            if (!seenInSql.add(identity)) {
                skipped++;
                continue;
            }

            // If target already contains this transaction (idempotency check), skip
            YearMonth ym = YearMonth.from(txn.occurredAt());
            List<NormalizedTxn> inTarget = target.forAccountMonth(txn.accountLast4(), ym);
            boolean alreadyInTarget = inTarget.stream().anyMatch(t ->
                    t.occurredAt().withSecond(0).withNano(0).equals(identity.occurredAt)
                            && t.direction() == identity.direction
                            && t.amount().compareTo(identity.amount) == 0);

            if (alreadyInTarget) {
                skipped++;
                continue;
            }

            target.save(txn);
            written++;
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}

    private record TxnIdentity(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount) {}
}
