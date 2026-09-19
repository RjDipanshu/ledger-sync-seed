package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages, filters out non-financial messages,
 * deduplicates overlapping messages, classifies transactions, and saves
 * normalized transactions to the ledger store.
 */
public final class IngestService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        // Group multiple messages describing the same underlying transaction
        Map<TxnKey, List<ParsedTxn>> groups = new LinkedHashMap<>();
        int skipped = 0;

        for (RawMessage m : messages) {
            if (isNonTransaction(m)) {
                skipped++;
                continue;
            }

            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }

            ParsedTxn txn = p.get();
            TxnKey key = new TxnKey(
                    txn.accountLast4(),
                    txn.occurredAt().withSecond(0).withNano(0),
                    txn.direction(),
                    txn.amount()
            );

            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(txn);
        }

        // Existing transactions in the store (for idempotency)
        Set<TxnKey> existing = store.all().stream()
                .map(t -> new TxnKey(
                        t.accountLast4(),
                        t.occurredAt().withSecond(0).withNano(0),
                        t.direction(),
                        t.amount()
                ))
                .collect(Collectors.toSet());

        int written = 0;
        for (Map.Entry<TxnKey, List<ParsedTxn>> entry : groups.entrySet()) {
            TxnKey key = entry.getKey();
            if (existing.contains(key)) {
                // Already present in ledger store - preserve idempotency
                continue;
            }

            NormalizedTxn normalized = toNormalizedTxn(entry.getValue());
            store.save(normalized);
            existing.add(key);
            written++;
        }

        return new Stats(messages.size(), written, skipped);
    }

    private static boolean isNonTransaction(RawMessage m) {
        String sender = m.sender() != null ? m.sender() : "";
        String body = m.body() != null ? m.body() : "";

        // Non-bank senders (delivery, tracking, phishing)
        if (sender.equals("BP-DELHVY") || sender.equals("AX-SWGGYX") || sender.equals("VK-ICICIB")) {
            return true;
        }

        // Promotional messages
        if (body.contains("Personal Loan") || body.contains("pre-approved")) {
            return true;
        }

        // OTP messages
        if (body.contains("is your OTP") || body.contains("OTP for txn")) {
            return true;
        }

        // Standalone balance inquiry SMS
        if (body.startsWith("Avl Bal in a/c")) {
            return true;
        }

        return false;
    }

    private static NormalizedTxn toNormalizedTxn(List<ParsedTxn> parsedList) {
        ParsedTxn first = parsedList.get(0);
        String accountLast4 = first.accountLast4();
        OffsetDateTime occurredAt = first.occurredAt();
        Direction direction = first.direction();
        BigDecimal amount = first.amount();

        // Collect all distinct source message IDs in sorted order
        Set<String> messageIds = new TreeSet<>();
        String bestMerchant = first.merchant();
        for (ParsedTxn p : parsedList) {
            if (p.sourceMessageId() != null && !p.sourceMessageId().isBlank()) {
                messageIds.add(p.sourceMessageId());
            }
            if (p.merchant() != null && p.merchant().length() > bestMerchant.length()) {
                bestMerchant = p.merchant();
            }
        }

        Category category = determineCategory(direction, amount, bestMerchant);

        return new NormalizedTxn(
                accountLast4,
                occurredAt,
                direction,
                amount,
                category,
                bestMerchant,
                new ArrayList<>(messageIds)
        );
    }

    private static Category determineCategory(Direction dir, BigDecimal amount, String merchant) {
        String m = merchant != null ? merchant.toUpperCase() : "";

        // Internal transfers between own accounts (e.g. Parag Kapoor)
        if (m.contains("PARAG KAPOOR") || m.contains("SELF TRANSFER") || m.contains("OWN A/C")) {
            return Category.TRANSFER;
        }

        if (dir == Direction.DEBIT) {
            // A UPI debit of ₹100 or less is MICRO
            boolean isUpi = m.contains("UPI") || m.contains("VPA") || m.startsWith("UPI/");
            if (amount.compareTo(HUNDRED) <= 0 && isUpi) {
                return Category.MICRO;
            }
            return Category.SPEND;
        }

        return Category.INCOME;
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}

    private record TxnKey(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount) {}
}
