# Discussion pointers

Things that need an answer from somebody other than engineering, with enough context that
the conversation can happen without re-deriving anything. Each says what is blocked and what
a usable answer looks like.

---

## 1. The reversible window (NFR-07) — direction agreed, detail open

**Agreed:** auto-payments get a defined window before they are final. Recorded as **D-4**.

**To confirm with Product:**

| | Question | Why it changes the build |
|---|---|---|
| a | **How long is the window?** | Minutes, hours or days changes whether finalisation is an in-process timer, a scheduled job, or a batch |
| b | **Does it vary?** By amount, by concern, by SP medal? | A fixed number is a config value. A varying one is a rule, and rules belong in a decision table |
| c | **Who can reverse inside it?** The bot on new evidence, an agent, finance, or nobody — it is only a settling period | Decides whether a reversal API and an agent surface are needed, or only a finaliser |
| d | **Does the partner see the money during the window?** | "Credited" and "credited, reversible until tomorrow" are different sentences. It changes the response template and probably the wallet integration |
| e | **What happens to CSAT inside the window?** | Today CSAT is asked on resolution. If the money is not final, "did this help?" is being asked about something that might be undone |
| f | **Is the window reported to finance as settled or pending?** | NFR-06 targets 100% reconciliation accuracy. Reconciling a pending payment against finance records needs a shared definition of when it counts |

**What this blocks:** the schema port (D-2). `ticket_action` should be created once on MySQL
with the states it will actually need, rather than migrated again later.

**A usable answer** is (a) plus (c) plus (d). The rest can follow.

---

## 2. Which concern taxonomy is current

**Status:** PRD and concern sheet to be attached for a side-by-side.

The PRD describes **five L1 paths**. The seeded catalogue has **six L1 codes**, and they do
not line up:

| PRD's five | Catalogue |
|---|---|
| Realtime Job se jude huye issues | *nothing built* |
| Leave sambandidh issues | `LEAVES_RELATED` |
| Amount related | `AMOUNT_RELATED` |
| Fine Related | `FINE_RELATED` |
| Other Related | `OTHER_ISSUES` |
| | `VIOLATIONS` — not an L1 path in the PRD |
| | `PRODUCT_ISSUES` — **absent from the PRD entirely** |

**The three things to settle:**

- **`PROD_DELIVERY_DELAY`** is built, tested and carries 2,306 monthly cases, and has nowhere
  to live in the PRD. Did Product Issues move under another path, or leave scope?
- **`VIOLATIONS`** — strike appeals sit under *Other Related* in the PRD and fines under *Fine
  Related*. The R1–R13 sub-reason structure does not appear. Already out of POC scope; the
  question is whether it returns, and under which path.
- **"Realtime Job"** is a whole L1 path with eleven L2 concerns, nothing built, and most of
  the new capabilities R-01…R-10 live inside it.

**What this blocks:** the catalogue is the system's *configuration* — it decides which fact
provider runs, which decision table is consulted, and which kill switch applies. If it
describes the wrong world that is one migration now, or a surprise per concern later.

---

## 3. The PRD's own open questions (§13)

Two are blocking, four are not.

**Blocking:**

- **Agent-2 outbound SLA after a T3 trigger fires (NFR-03).** Unspecified. It decides whether
  the agent queue needs priority handling and what the alert threshold is.
- **The reversible window** — item 1 above.

**Not blocking, but worth closing before the numbers are quoted:**

- Standard-band share is **90.5% in prose and 92.2% from the table** (46,229 / 50,130). The
  premium-band combined share moves with it.
- The ₹100 parked-order auto-credit (R-08) — fixed policy amount, or configurable per
  city/category? If it varies it is a decision-table row, not a constant.
- Regional languages beyond Hindi/English/Hinglish for the audio-note L1 selection.
- Phase 2 is described as "54% of volume" — worth checking against the §7.2 tier breakdown
  before it becomes a phase-gate target.

---

## 4. Still outstanding from the POC, unchanged

These have been open since mid-September and none of them needs a meeting — they need one
person with database access and two commands.

| | What | What a wrong answer costs |
|---|---|---|
| **N2** | which `order_status_code` values mean NR / CR / by-agent | NR auto-credits and CR denies. A wrong mapping **pays the wrong partners** |
| **N3** | what links a wallet credit to a PayU transaction | a wrong "not credited" **pays twice** |
| **Q6** | the job's lat/long behind `cs_address` | two transport rules stay dead |
| — | `transport_charges` — paise or rupees? | if rupees, the ₹300 cap compares against a number 100× too large and **can never fire** |
| — | the real names of `arrived_at300_m` and `order_type` | MySQL 1054 kills the whole `SELECT`: Transport does not degrade, it **stops** |

`tools/uat-probe-2.sql` and `tools/uat-probe-3.sql` answer all five. They are read-only and
already written.
