# ledger-sync

Production-grade transaction ingestion, deduplication, categorization, and ledger synchronization engine for **Simplify Money**.

Built by **Dipanshu Raj** ([GitHub: @RjDipanshu](https://github.com/RjDipanshu)).

---

## Quickstart (Under 5 Minutes)

### 1. Pure JDK Verification (Zero External Dependencies)
Runs the entire compile, pipeline ingestion of `fixtures/corpus-a.jsonl`, and verification check without any third-party dependencies or external build tools:

```bash
./verify.sh
```

### 2. Run the Full Test Suite
Executes all unit, contract, integration, and scale benchmark tests (including contract immutability, incident regression, deduplication idempotency, and 100,000-transaction scale benchmarks):

```bash
# Using the standalone test runner (pure JDK + local test jars):
javac -cp "build/classes;lib/h2-2.2.224.jar" -d build/classes $(find src/main/java -name "*.java")
javac -cp "build/classes;lib/h2-2.2.224.jar;lib/junit-platform-console-standalone.jar" -d build/test-classes $(find src/test/java -name "*.java")
java -jar lib/junit-platform-console-standalone.jar --class-path "build/classes;build/test-classes;lib/h2-2.2.224.jar" --scan-class-path

# Or via Gradle:
./gradlew test
```

### 3. Generate Submission Artifacts
Ingest the corpus and generate `ledger.json`, `summary.json`, and `reconciliation.json`:

```bash
java -cp "build/classes;lib/h2-2.2.224.jar" in.simplifymoney.ledgersync.App migrate
java -cp "build/classes;lib/h2-2.2.224.jar" in.simplifymoney.ledgersync.App ingest fixtures/corpus-a.jsonl
java -cp "build/classes;lib/h2-2.2.224.jar" in.simplifymoney.ledgersync.App report submission/
```

### 4. Run Amazon DynamoDB Local via Docker Compose
To run the local Amazon DynamoDB container for the document store:

```bash
docker compose up -d
```
The local endpoint is available at `http://localhost:8000`.

---

## Submission Deliverables & Artifacts

| Task | Artifact / File | Description |
|---|---|---|
| **Task 0** | [docs/task0-profile-and-feedback.md](docs/task0-profile-and-feedback.md) | Candidate profile, referral checklist, and app hands-on feedback template |
| **Task 1** | [docs/task1-track-flow-teardown.md](docs/task1-track-flow-teardown.md) | In-depth 1–2 page architectural and product teardown of the "Track" flow |
| **Task 2** | [src/main/.../ingest/IngestService.java](src/main/java/in/simplifymoney/ledgersync/ingest/IngestService.java)<br>[submission/ledger.json](submission/ledger.json)<br>[submission/summary.json](submission/summary.json)<br>[submission/reconciliation.json](submission/reconciliation.json) | Complete ingestion pipeline, multi-channel deduplication, 4-category classification, and generated reports |
| **Task 3** | [incident/INC-2026-09-11-resolution.md](incident/INC-2026-09-11-resolution.md)<br>[src/test/.../AmountsTest.java](src/test/java/in/simplifymoney/ledgersync/AmountsTest.java) | Root cause analysis, 44-message blast radius, regression test, regex fix, and Slack incident note |
| **Task 4** | [src/main/.../store/DynamoDocumentStore.java](src/main/java/in/simplifymoney/ledgersync/store/DynamoDocumentStore.java)<br>[src/main/.../store/Backfill.java](src/main/java/in/simplifymoney/ledgersync/store/Backfill.java)<br>[src/main/.../store/ConsistencyChecker.java](src/main/java/in/simplifymoney/ledgersync/store/ConsistencyChecker.java)<br>[docker-compose.yml](docker-compose.yml) | DynamoDB Single-Table Design, Idempotent Backfill, Field-level Consistency Checker, and 100k Benchmark |

---

## Architecture & Document Store (Task 4)

### Choice: Amazon DynamoDB (Single-Table Design)
We selected **Amazon DynamoDB** over MongoDB for the following architectural reasons:
1. **Predictable Latency at Scale:** DynamoDB guarantees single-digit millisecond latency regardless of whether the table has 1,000 or 100,000,000 items, because all queries are served via partition key hashes without complex index scans.
2. **Strict Financial Access Patterns:** In consumer fintech, access patterns are known and fixed: monthly statement feeds, category aggregations, and message-to-transaction lookups. A single-table DynamoDB design satisfies all three queries with zero cross-collection joins.
3. **Pure Zero-Dependency Implementation:** Using the standard JDK 11+ `HttpClient` and DynamoDB's low-level HTTP REST JSON protocol (`X-Amz-Target: DynamoDB_20120810`), we maintain 100% pure JDK compilation with zero fat third-party AWS SDK jars on the compile path.

### Single-Table Schema Design

| Item Type | Partition Key (`PK`) | Sort Key (`SK`) | Attributes | Query Served |
|---|---|---|---|---|
| **Transaction Item** | `ACC#<last4>#<YYYY-MM>` | `TXN#<occurred_at>#<id>` | `direction`, `amount`, `category`, `merchant`, `source_message_ids` | **Query 1:** `forAccountMonth` |
| **Summary Item** | `SUMMARY#<last4>` | `METADATA` | `SPEND`, `INCOME`, `MICRO`, `TRANSFER` (pre-aggregated running totals) | **Query 2:** `categoryTotals` |
| **Message Index** | `MSG#<message_id>` | `LOOKUP` | `account_last4`, `occurred_at`, `direction`, `amount`, `category`, `merchant`, `source_message_ids` | **Query 3:** `byMessageId` |

### 100,000-Transaction Scale Benchmark (The Six Numbers)

As required, we loaded **100,000 normalized transactions** and measured engine items examined (`ScannedCount`) versus items returned (`Count`):

```
Scale Benchmark (100,000 transactions):
  Q1 (forAccountMonth): ScannedCount=450, Count=450
  Q2 (categoryTotals):  ScannedCount=1,   Count=1
  Q3 (byMessageId):     ScannedCount=1,   Count=1
```

#### The Six Numbers Table:

| Query | Items Examined (`ScannedCount`) | Items Returned (`Count`) | Efficiency Ratio |
|---|---|---|---|
| **Q1: One account's transactions for one month, newest first** | **450** | **450** | **1.0 (100% direct)** |
| **Q2: Running totals per category for an account** | **1** | **1** | **1.0 (100% direct)** |
| **Q3: Given a message id, which transaction did it produce** | **1** | **1** | **1.0 (100% direct)** |

**Efficiency Analysis:**
- In Q1, the partition key isolates solely that account and month (`ACC#4821#2026-07`). DynamoDB scans only the exact items within the partition, executing $ScanCount = Count$ without examining unrelated months or accounts.
- In Q2, category totals are maintained atomically on an item (`SUMMARY#<last4>`), turning an $O(N)$ full-scan aggregation into an $O(1)$ single-item retrieval.
- In Q3, the message index partition key (`MSG#<message_id>`) performs a single-point hash lookup, returning the exact transaction in 1 item examined.

---

## Corpus-A Reconciliation Analysis

Running `./verify.sh` against `fixtures/corpus-a.jsonl` yields:
```
INGEST
  messages read       522
  transactions written 256
  messages skipped    41

BY CATEGORY
  SPEND        142567.64
  INCOME       142791.16
  MICRO          4443.85
  TRANSFER      62000.00

AGAINST fixtures/corpus-a-totals.json
  transactions   expected 257, produced 256
  **4821  txns 145 (expected 146)
           balance from ledger 48626.34, bank says 41126.34, difference 7500.00
  **9075  txns 91 (expected 91)
           balance from ledger 51210.63, bank says 51210.63, difference 0.00
```

### The Account 4821 ₹7,500.00 Discrepancy
As noted in `submission/reconciliation.json`:
- **Account 9075:** Reconciles **exactly to ₹0.00 difference** across 91 transactions.
- **Account 4821:** The closing balance shows an unexplained ₹7,500.00 divergence.
  - On `2026-07-29T11:53:00+05:30`, HDFC stated balance was **₹36,054.05**.
  - On `2026-07-29T17:06:00+05:30`, a ₹75.00 debit occurred. The stated balance jumped down to **₹28,479.05**.
  - Expected balance after ₹75.00 debit: $36,054.05 - 75.00 = ₹35,979.05$.
  - Discrepancy: $35,979.05 - 28,479.05 = \mathbf{₹7,500.00}$.
  - Investigation across all 522 raw messages in `corpus-a.jsonl` confirms **zero SMS or email messages were delivered or captured for this ₹7,500 withdrawal**.
  - As emphasized in the prompt: *"A submission whose numbers match because they were made to match is worse than one that does not match and explains itself."* Rather than fabricating an unevidenced phantom transaction, our engine faithfully records the 256 real transactions and documents the exact bank gap in `reconciliation.json`.

---

## Technical Decision Log

### 1. Optional Cents Regex Pattern for Amounts
- **Context:** Incident INC-2026-09-11 occurred because `Amounts.AMOUNT` regex enforced `\\.[0-9]{2}`. Whole-rupee transactions like `Rs.5` skipped the transaction amount and captured the adjacent `Avl Bal: Rs.92,213.10`.
- **Decision:** Updated regex to `(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)` followed by `.setScale(2, RoundingMode.UNNECESSARY)`. Added lookahead assertions to prevent capturing trailing sentence periods as decimal points (e.g. `debited with Rs 20.`).
- **Trade-off:** Strict regex boundaries ensure no false positives while correctly parsing whole integers and standard two-decimal amounts.

### 2. Multi-Channel Deduplication Correlation Key
- **Context:** Multiple messages (SMS + Email) represent the exact same financial transaction.
- **Decision:** Implemented a synthetic deduplication key: `{account_last4} : {truncated_to_minute(occurred_at)} : {amount} : {direction}`. When matching alerts arrive within a ±5-minute window with identical amounts and account last 4, they are merged into a single `NormalizedTxn` with consolidated `source_message_ids`.
- **Trade-off:** Allows merging email and SMS alerts that have slightly varying delivery timestamps while preventing accidental merging of recurring legitimate transactions on different days.

### 3. Four-Category Classification Rules
- **Context:** Categories must accurately differentiate `MICRO`, `TRANSFER`, `SPEND`, and `INCOME`.
- **Decision:**
  - `TRANSFER`: Identified when money moves between the user's known accounts (`4821` and `9075`), or merchants containing `SELF TRANSFER`, `OWN A/C`, or matching IMPS/UPI P2A transfers between accounts.
  - `MICRO`: Any UPI debit with amount $\le ₹100.00$.
  - `SPEND`: Any other debit outflow.
  - `INCOME`: Any non-transfer credit inflow.
- **Trade-off:** Transfer legs are cleanly excluded from Spend and Income, preventing inflation of user financial metrics.

### 4. Zero-Dependency Amazon DynamoDB Client
- **Context:** Task 4 requires a document store (DynamoDB preferred) with Docker Compose, while `./verify.sh` requires compiling with pure JDK 17/21 without external AWS SDK dependencies.
- **Decision:** Implemented `DynamoDocumentStore` using standard `java.net.http.HttpClient` communicating directly with DynamoDB Local's JSON REST API on port 8000. It includes an in-memory dual-mode for fast offline test execution.
- **Trade-off:** Required hand-crafting standard DynamoDB JSON serialization (`{"S": "..."}`, `{"N": "..."}`), but eliminated multi-megabyte external dependencies and guarantees `./verify.sh` builds instantly anywhere.

### 5. Single-Table DynamoDB Partitioning (`ACC#<last4>#<YYYY-MM>`)
- **Context:** Query 1 requests transactions for an account and month, sorted newest first.
- **Decision:** Combined account and month into the partition key (`ACC#<last4>#<YYYY-MM>`) with sort key `TXN#<occurred_at>#<id>`.
- **Trade-off:** Monthly queries require zero post-query filtering ($ScannedCount = Count$). Querying across multiple months requires querying each month's partition, which matches real mobile client pagination patterns.

### 6. Idempotent Backfill with In-Flight Deduplication
- **Context:** The legacy SQL store contains historical duplicates (e.g. `m-legacy-0001` inserted multiple times) and lacks uniqueness constraints. Backfill must handle partial failures safely.
- **Decision:** Backfill loops through SQL records, deduplicates using the correlation key and `source_message_ids`, and uses DynamoDB `PutItem` (which is naturally idempotent on primary key).
- **Trade-off:** Incurs minor CPU cost during backfill to inspect existing document keys, but guarantees zero duplicate records in the document store regardless of how many times it is re-run.

### 7. Deep Structural Consistency Checker
- **Context:** A consistency checker comparing row counts alone would miss corrupted amounts or mismatched categories.
- **Decision:** `ConsistencyChecker` compares every transaction by primary key, verifying exact equality on `occurred_at`, `direction`, `amount`, `category`, `merchant`, and `source_message_ids`. It also cross-checks pre-aggregated category totals against the transaction sum, and validates message index lookups.
- **Trade-off:** Slightly slower than count checks, but detects subtle data corruptions immediately and names the exact divergence.

### 8. RFC 1123 and Bank Email Date Parsing
- **Context:** Bank email alert formats use standard email date headers (e.g. `Fri, 04 Jul 2026 20:24:00 +0530`) which failed existing SMS date parsers.
- **Decision:** Extended `Dates.java` to support RFC 1123 / RFC 822 format (`EEE, dd MMM yyyy HH:mm:ss Z`) using `DateTimeFormatter.RFC_1123_DATE_TIME`.
- **Trade-off:** Captures email alert timestamps accurately without altering existing SMS date parsing patterns.

### 9. Noise Rejection for Non-Transactional Messages
- **Context:** The message stream contains promotional loan messages, ATM OTPs, delivery trackers, and spam.
- **Decision:** Filtered out messages that do not represent settled transactions (e.g., OTP codes, pre-approved loan advertisements, mandate authorizations without monetary debits).
- **Trade-off:** 41 messages are cleanly skipped in `corpus-a`, ensuring only true transactions enter the ledger.

### 10. Explicit Separation of Legacy Pre-Seed Data
- **Context:** `db/migration/V2__seed.sql` contained 15 unverified June 2026 transactions left to simulate legacy SQL state.
- **Decision:** Configured `App report` to report strictly on the verified corpus transactions for submission outputs, while preserving the legacy seed rows in SQL for `Backfill` and migration testing.
- **Trade-off:** Ensures submission reports reconcile directly with `fixtures/corpus-a-totals.json` while maintaining realistic legacy database state for the store migration.

---

## AI Disclosure & Collaboration Note

In accordance with transparent engineering practices:
- **Pair Programming Assistance:** This project was developed by pairing with an AI coding assistant (Antigravity). The AI assisted with initial scaffolding exploration, generating regex test fixtures, and calculating DynamoDB JSON REST payloads.
- **Concrete Example of Engineer Correction:**
  - *Scenario:* During initial deduplication design, the AI suggested grouping messages solely by `merchant` and `occurred_at`.
  - *Engineering Correction:* In Indian banking notification systems, merchant names often diverge significantly between channels (e.g., SMS alerts show `UPI/WATER CAN` while email alerts show `HDFC Bank: Payment to WATER CAN via UPI Ref 991823`). Grouping on raw merchant text resulted in split duplicate transactions. The engineer corrected the deduplication key to rely on `{account_last4, amount, direction, timestamp_minute_bucket}`, and implemented merchant normalization logic to pick the cleanest descriptive merchant title.

---

## Known Limitations & Future Roadmap

1. **UPI Reference ID (`RRN`) Extraction:** Many bank SMS include a 12-digit UPI reference number (`UPI Ref: 618293819201`). Adding a dedicated extractor for RRN would allow cross-bank deduplication even across different user accounts.
2. **Multi-Currency Support:** Currently, the system assumes Indian Rupee (`INR` / `Rs.`). Handling international credit card spends with foreign currency markups (e.g., `USD 12.50 (INR 1,045.00)`) will require dual-currency fields.
3. **On-Device WASM Engine:** Compiling the regex parser pipeline to WebAssembly / native Android code would allow 100% of SMS parsing to execute entirely client-side, sending only encrypted normalized transaction payloads to the cloud.

---
