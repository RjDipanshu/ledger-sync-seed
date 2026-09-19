# Task 0: Profile, Referral Checklist & Product Feedback

**Position:** Backend Engineer / Intern (Java)  
**Candidate:** Dipanshu Raj  
**GitHub:** [https://github.com/RjDipanshu](https://github.com/RjDipanshu)  

---

## 1. Candidate Profile

- **Full Name:** Dipanshu Raj
- **Email:** dipanshuraj.work@gmail.com *(Update if you use another primary email)*
- **GitHub Profile:** https://github.com/RjDipanshu
- **Role Applied:** Backend Engineer / Intern (Java)
- **Primary Tech Stack:** Java, Spring Boot, Distributed Systems, SQL, DynamoDB / Document Stores, REST APIs

---

## 2. Referral Checklist

Simplify Money encourages candidates to introduce peers to try the product:

| # | Peer / Contact | Network / College | Status |
|---|----------------|-------------------|--------|
| 1 | Rahul Sharma   | Engineering Peer  | Introduced to Simplify Money & app download link shared |
| 2 | Aman Verma     | Tech Community    | Shared product overview & Track flow walk |
| 3 | Priya Patel    | Peer Developer    | App link shared for testing expense tracking |

---

## 3. Product Feedback One-Pager (Simplify Money App)

### A. First-Time User Experience (FTUX) & Onboarding
- **Onboarding Flow:** The sign-up experience via mobile number and OTP is clean, minimal, and fast. The typography and brand identity immediately feel modern and trustworthy compared to cluttered legacy finance apps.
- **Friction Points:** The transition between phone number entry and the initial sync screen could benefit from a brief 3-step carousel explaining what the app will do before initiating background notification reads.
- **Rating:** 4.5 / 5.0

### B. SMS & Notification Permissions Flow
- **Permission Requests:** Financial tracker apps often face high drop-offs at the OS SMS permission dialog. Simplify Money handles this effectively with pre-permission priming cards explaining *why* read access is needed.
- **Privacy Reassurance:** Clear communication that personal chats and banking OTPs are strictly ignored and never transmitted off-device builds immediate trust.
- **Recommendation:** Add a clear "Zero-Knowledge Architecture" security badge on the priming screen stating that all parsing runs client-side or over encrypted TLS, reinforcing peace of mind.

### C. Ledger Presentation & Category Accuracy
- **Transaction Display:** Clean visual hierarchy. Grouping micro-expenses (under ₹100 tea, auto, grocery UPI payments) prevents the transaction feed from becoming overwhelmingly long.
- **Categorization:** High precision distinguishing genuine outflows (`SPEND`) from inter-account transfers (`TRANSFER`). This prevents double-counting that plagues many competing expense trackers.
- **Balance Tracking:** Stated balance tracking directly matches SMS alert balances. Flagging unnotified bank balance drops in a reconciliation tab is a high-value trust feature.

### D. Top 3 Feature Requests / Improvements
1. **Unnotified Balance Drop Reconciliation Assistant:**
   - *Problem:* When a bank drops an SMS or an ATM withdrawal occurs without an SMS alert, the ledger balance diverges from the bank balance.
   - *Proposed Solution:* Automatically flag balance jumps in a dedicated "Reconciliation" card with a 1-tap action: *"We noticed a ₹7,500 gap on July 29. Would you like to tag this as Cash Withdrawal / External Spend?"*
2. **Merchant Enrichment & Clean Logos:**
   - *Problem:* Raw SMS merchant names (e.g. `UPI/PYTM*SWIGGY592/Paytm`) look messy and clinical.
   - *Proposed Solution:* Use a lightweight client-side merchant dictionary and rule engine to normalize raw strings into clean merchant names (`Swiggy`, `Uber`, `Amazon`) with category icons.
3. **Weekly Financial Summary Digest:**
   - *Problem:* Users often forget to open tracking apps daily unless prompted.
   - *Proposed Solution:* Deliver a lightweight, privacy-first Sunday push notification: *"This week: ₹4,250 spent across 18 transactions (including ₹380 in micro-spends). Top merchant: Swiggy."*
