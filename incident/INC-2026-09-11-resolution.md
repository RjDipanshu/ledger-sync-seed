# Incident INC-2026-09-11 Resolution Note

## Five-Line Channel Note
1. **What broke:** The amount regex in `Amounts.java:18` required two decimal places, skipping whole-rupee amounts like `Rs.5` and erroneously matching subsequent bank balance figures (`Avl Bal: Rs.92,213.10`).
2. **How it was found:** Investigated `incident/app.log` trace for `m-00004-9c11ae` and reproduced the regex skip where `Rs.5` failed pattern matching and captured the adjacent balance.
3. **Who was affected:** Any user with whole-rupee transactions omitting paise formatting across HDFC and ICICI (44 messages in `corpus-a.jsonl`, heavily distorting ledger balances).
4. **Root cause & Fix:** Updated the regex pattern in `Amounts.java` to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)`, accepting optional decimals and standardizing to 2 decimal places with `setScale(2)`.
5. **Why it cannot recur:** Added regression tests in `AmountsTest` testing integer rupee debits and credits alongside stated balance figures; tests now verify correct amount extraction before any balance parsing.
