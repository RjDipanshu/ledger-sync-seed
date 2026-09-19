package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DynamoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentStoreTest {

    private Path tempDbDir;
    private SqlLedgerStore sqlStore;

    @BeforeEach
    void setUp() throws Exception {
        tempDbDir = Files.createTempDirectory("test-ledger-db");
        sqlStore = new SqlLedgerStore(tempDbDir.resolve("test-ledger"));
        sqlStore.migrate(Path.of("db", "migration"));
    }

    @AfterEach
    void tearDown() {
        if (sqlStore != null) {
            sqlStore.close();
        }
    }

    @Test
    void servesThreeAccessPatternsDirectly() {
        DynamoDocumentStore store = new DynamoDocumentStore();

        NormalizedTxn t1 = new NormalizedTxn(
                "4821",
                Dates.ist("04-07-26 10:00"),
                Direction.DEBIT,
                new BigDecimal("150.00"),
                Category.SPEND,
                "AMAZON PAY",
                List.of("m-001", "m-002")
        );

        NormalizedTxn t2 = new NormalizedTxn(
                "4821",
                Dates.ist("05-07-26 12:00"),
                Direction.DEBIT,
                new BigDecimal("25.00"),
                Category.MICRO,
                "UPI/CHAI",
                List.of("m-003")
        );

        NormalizedTxn t3 = new NormalizedTxn(
                "4821",
                Dates.ist("01-08-26 09:00"),
                Direction.CREDIT,
                new BigDecimal("5000.00"),
                Category.INCOME,
                "SALARY",
                List.of("m-004")
        );

        store.save(t1);
        store.save(t2);
        store.save(t3);

        // Q1: forAccountMonth, newest first
        List<NormalizedTxn> july = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july.size());
        assertEquals(t2.occurredAt(), july.get(0).occurredAt(), "Newest must be first");
        assertEquals(t1.occurredAt(), july.get(1).occurredAt());

        List<NormalizedTxn> august = store.forAccountMonth("4821", YearMonth.of(2026, 8));
        assertEquals(1, august.size());
        assertEquals(t3.occurredAt(), august.get(0).occurredAt());

        // Q2: categoryTotals
        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("150.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("25.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("5000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("0.00"), totals.get(Category.TRANSFER));

        // Q3: byMessageId
        Optional<NormalizedTxn> found = store.byMessageId("m-002");
        assertTrue(found.isPresent());
        assertEquals("AMAZON PAY", found.get().merchant());

        Optional<NormalizedTxn> notFound = store.byMessageId("m-999");
        assertFalse(notFound.isPresent());
    }

    @Test
    void backfillDeduplicatesLegacySqlAndIsIdempotent() {
        DynamoDocumentStore docStore = new DynamoDocumentStore();
        Backfill backfill = new Backfill(sqlStore, docStore);

        // First run
        Backfill.Result result1 = backfill.run();
        assertTrue(result1.read() >= 15, "Should read legacy rows from seed");
        assertTrue(result1.skipped() > 0, "Should skip legacy duplicate rows from V2__seed.sql");
        assertTrue(result1.written() > 0, "Should write distinct transactions");

        // Second run must be completely idempotent (0 written, all skipped)
        Backfill.Result result2 = backfill.run();
        assertEquals(result1.read(), result2.read());
        assertEquals(0, result2.written(), "Repeated backfill must not re-write existing items");
        assertEquals(result2.read(), result2.skipped());
    }

    @Test
    void consistencyCheckerDetectsAgreementAndNamedDivergences() {
        DynamoDocumentStore docStore = new DynamoDocumentStore();
        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        // 1. Agreeing stores produce 0 divergences
        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();
        assertEquals(0, divergences.size(), "Cleanly backfilled store must have zero divergences");

        // 2. Deliberately alter the document store and verify divergence is caught
        NormalizedTxn altered = new NormalizedTxn(
                "4821",
                Dates.ist("04-07-26 15:30"),
                Direction.DEBIT,
                new BigDecimal("999.00"),
                Category.SPEND,
                "HOSTILE INJECTION",
                List.of("m-altered-001")
        );
        docStore.save(altered);

        List<ConsistencyChecker.Divergence> detected = checker.check();
        assertTrue(detected.size() > 0, "Checker must detect injected divergence");
        assertTrue(detected.stream().anyMatch(d -> d.what().contains("unexpected_in_documents")));
    }

    @Test
    void measuresExaminedVersusReturnedAtScale() {
        DynamoDocumentStore store = new DynamoDocumentStore();

        // Populate 100,000 transactions across 10 accounts and 24 months
        int total = 100_000;
        java.time.OffsetDateTime base = Dates.ist("01-01-25 00:00");
        String targetMsgId = "m-bench-target";

        for (int i = 0; i < total; i++) {
            String acct = String.format("%04d", (i % 10));
            int dayOffset = (i / 10) % 700;
            java.time.OffsetDateTime at = base.plusDays(dayOffset).plusMinutes(i % 1440);
            Direction dir = ((i / 10) % 2 == 0) ? Direction.DEBIT : Direction.CREDIT;
            Category cat = (dir == Direction.DEBIT) ? Category.SPEND : Category.INCOME;
            String mid = (i == 42) ? targetMsgId : ("m-scale-" + i);

            store.save(new NormalizedTxn(
                    acct,
                    at,
                    dir,
                    new BigDecimal("100.00"),
                    cat,
                    "MERCHANT-" + (i % 50),
                    List.of(mid)
            ));
        }

        // Q1: forAccountMonth at scale
        List<NormalizedTxn> q1Result = store.forAccountMonth("0001", YearMonth.of(2025, 6));
        assertTrue(q1Result.size() > 0);

        // Q2: categoryTotals at scale
        Map<Category, BigDecimal> q2Result = store.categoryTotals("0001");
        assertTrue(q2Result.get(Category.SPEND).compareTo(BigDecimal.ZERO) > 0);

        // Q3: byMessageId at scale
        Optional<NormalizedTxn> q3Result = store.byMessageId(targetMsgId);
        assertTrue(q3Result.isPresent());

        DynamoDocumentStore.QueryMetrics metrics = store.getMetrics();
        System.out.printf("Scale Benchmark (100,000 transactions):%n");
        System.out.printf("  Q1 (forAccountMonth): ScannedCount=%d, Count=%d%n",
                metrics.q1ScannedCount(), metrics.q1Count());
        System.out.printf("  Q2 (categoryTotals):  ScannedCount=%d, Count=%d%n",
                metrics.q2ScannedCount(), metrics.q2Count());
        System.out.printf("  Q3 (byMessageId):     ScannedCount=%d, Count=%d%n",
                metrics.q3ScannedCount(), metrics.q3Count());

        assertEquals(metrics.q1ScannedCount(), metrics.q1Count(), "Q1: ScannedCount must equal Count");
        assertEquals(1, metrics.q2ScannedCount(), "Q2: ScannedCount must be 1 for pre-aggregated summary");
        assertEquals(1, metrics.q2Count(), "Q2: Count must be 1");
        assertEquals(1, metrics.q3ScannedCount(), "Q3: ScannedCount must be 1 for index lookup");
        assertEquals(1, metrics.q3Count(), "Q3: Count must be 1");
    }
}
