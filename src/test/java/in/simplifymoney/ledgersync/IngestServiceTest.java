package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    @Test
    void ingestsCorpusWithDeduplicationAndTraceability() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        IngestService.Stats stats = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        assertEquals(522, stats.messagesRead());
        assertEquals(256, stats.transactionsWritten());
        assertEquals(41, stats.messagesSkipped());
        assertEquals(256, store.count());

        // Check that multi-source transactions combined their message IDs
        List<NormalizedTxn> multiSource = store.all().stream()
                .filter(t -> t.sourceMessageIds().size() > 1)
                .toList();
        assertTrue(multiSource.size() > 0, "Overlapping messages should combine into single transactions");

        // Verify categories
        long transferCount = store.all().stream().filter(t -> t.category() == Category.TRANSFER).count();
        assertEquals(10, transferCount); // 5 legs on 4821, 5 legs on 9075

        long microCount9075 = store.all().stream()
                .filter(t -> "9075".equals(t.accountLast4()) && t.category() == Category.MICRO)
                .count();
        assertEquals(45, microCount9075);

        long microCount4821 = store.all().stream()
                .filter(t -> "4821".equals(t.accountLast4()) && t.category() == Category.MICRO)
                .count();
        assertEquals(52, microCount4821);
    }

    @Test
    void ingestionIsIdempotentOnRepeatedIngest() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        IngestService.Stats firstRun = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        assertEquals(256, firstRun.transactionsWritten());
        assertEquals(256, store.count());

        // Ingesting the same corpus a second time must write 0 new transactions
        IngestService.Stats secondRun = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        assertEquals(0, secondRun.transactionsWritten());
        assertEquals(256, store.count());
    }
}
