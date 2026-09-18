# BOTIn Mod — what is needed to unblock the build

Everything buildable without input has been built: 29 of 98 steps, 70 tests green.
Phase 4 (fact providers) cannot start without item 1.

Items are ordered by how much they block. Item 1 is the only hard blocker.

---

## 1. UAT database access and schema  — BLOCKS PHASE 4

### 1a. Access

- The read-only UAT user and password.
- **Written confirmation that this user is `SELECT`-only by GRANT**, not by convention.

  The code is structurally incapable of writing to UAT — it has no JPA, no Flyway and no
  Flowable attached, and the connection is opened read-only. That stops our code. A GRANT
  stops anything. Both are wanted.

  ```sql
  -- what is being asked for
  REVOKE ALL ON ALL TABLES IN SCHEMA public FROM <botin_readonly>;
  GRANT  CONNECT ON DATABASE <uat_db> TO <botin_readonly>;
  GRANT  USAGE   ON SCHEMA public     TO <botin_readonly>;
  GRANT  SELECT  ON ALL TABLES IN SCHEMA public TO <botin_readonly>;
  ```

### 1b. The schema, structure only — no data needed

```bash
pg_dump --schema-only --no-owner --no-privileges -d <uat_db> > uat_schema.sql
```

Structure only. No rows, so nothing sensitive leaves the box.

If a full dump is not acceptable, the specific tables are listed in 1c.

### 1c. What each fact needs, by concern

These are the exact input names the decision tables read. They are listed in
`TABLE_INPUTS` in `DecisionTableTest` and that list is the contract a fact provider has to
satisfy. Placeholder table names currently in `TransportFactProvider` are marked
`TODO(schema)` and are guesses.

#### TRANSPORT_NOT_RECEIVED — the anchor concern, 5,615/month

| Fact the table reads | What it means | Placeholder in use |
|---|---|---|
| `alreadyCredited` | has transport already been credited for this order | `sp_wallet_txn` |
| `customerTransportCharged` | did the customer pay a transport charge on this booking | `booking_charge` |
| `arrivedAt300metre` | did the partner reach within 300 m of the job | `booking_event` |
| `cancellationStatus` | who cancelled, and how | `booking.cancellation_type` |
| `lastMinCashbackCredited` | was last-minute cancellation cashback paid | not yet guessed |
| `distanceBeyondRadiusKm` | job distance beyond the hub radius | needs hub + job lat/long |

#### RECHARGE_DEBIT_NO_CREDIT — 883/month

| Fact | What it means |
|---|---|
| `payuStatus` | the PayU transaction outcome for this recharge |
| `alreadyCredited` | has the wallet already been credited against that PayU transaction id |

(`spSatisfied` comes from the conversation, not from UAT.)

#### PROD_DELIVERY_DELAY — 2,306/month

*(VIOL_R9_NO_PRODUCT shared these facts. Violations are out of POC scope — see
`docs/SCOPE-VIOLATIONS-OUT.md`. The facts below are still needed, for the Product concern.)*

| Fact | What it means |
|---|---|
| `orderPlaced` | did the partner order the product |
| `pastDeliveryTat` / `pastElevenPmDeadline` | is the order past its Express/Standard TAT |

The shared TAT function stays shared. It was built so that Product delay and a future
violations phase can never disagree about what "late" means, and step 66 still tests that.

#### VIOL_R5_PERIODS — OUT OF POC SCOPE, nothing needed

*Kept for a later violations phase only. Do not chase this.*


`priorPeriodLeavesThisMonth` is held in our own `sp_counter` table, but the definition has
to come from somewhere: where period leaves are recorded today, so the counter can be
seeded rather than starting everyone at zero.

### 1d. Questions a schema dump will not answer

1. **`sp_id`** — what identifies a Service Partner in UAT, and is it the same value the app
   will send us?
2. **Cancellation values** — what does the cancellation column actually contain? The rules
   need three distinguishable cases: cancelled no-response, cancelled by agent, cancelled by
   customer.
3. **Arrival within 300 m** — is this a stored flag, an event row, or something to be
   computed from coordinates?
4. **Hub radius** — where are the hub centre coordinates and the radius per hub held?
5. **Last-minute cashback** — which table records it, and how is it tied to a booking?
6. **PayU status values** — the exact strings for pending, success and failed.
7. **Express vs Standard** — which column marks a product order's delivery type?
8. **The 25-job cycle** — where is a partner's job count held, so a cycle boundary can be
   computed?

---

## 2. Three confirmations  — small, but they change behaviour

### 2a. A decision row I added that is not in the concern mapping

**Recharge: PayU `SUCCESS` and the wallet was already credited.**

The mapping defines only *"Success and NOT already credited"*. A re-raise after a
successful credit is undefined, so it would fall to the catch-all and reach an agent. The
concern's own note asks for a duplicate guard on the PayU transaction id, so the row was
written:

> `SUCCESS` + already credited → `INFORM_ALREADY_CREDITED`, tier T1, **no money moves**

**Confirm or correct before this concern goes live.**

### 2b. Two row-order corrections against the mapping

Both were found by running the rules. Both are in money rules. Neither changes what a rule
says, only where it sits — but under a FIRST hit policy, position is the rule.

1. **The Rs300 cap.** The mapping lists it last (Transport rule 9). A Path 3 claim
   computing Rs450 matches rule 8 — pay distance × Rs50 — which sits above it. Rule 8 fires,
   money moves, the cap is never reached, and nothing is logged. **The cap is now row 2**,
   above every row that can pay.
2. **Recharge.** The mapping lists *"Failed"* before *"Failed and SP not satisfied"*, so the
   general row swallows the specific one and a dissatisfied partner is told to recharge
   again instead of getting a ticket. **The specific row now goes first.**

Flagging rather than asking: both are corrections of an ordering defect, not changes of
intent. Say so if either reading is wrong.

### 2c. The Transport path selector is still an assumption

The mapping labels Transport rules 2–8 as Path 1 / 2 / 3 but never says what assigns a
claim to a path. Without a selector the paths overlap: a claim that was cancelled by an
agent **and** lies outside the hub radius matches both rule 4 and rule 8, which pay
different amounts, and FIRST silently takes whichever sits higher.

The reading currently implemented, in `TransportPathSelector`:

```
PATH_1   an uncredited customer transport charge exists
PATH_2   the booking was cancelled
PATH_3   neither — ordinary travel, judged against the hub radius
```

**Needed: the real rule, from whoever owns the Transport policy.** It is one method with
its own test, so replacing it is a single edit.

---

## 3. D-1 — hosting

The Decision Brief lists hosting as an **open decision**. The Engineering Design states
`empapi` as **settled**. One of the two documents is wrong.

Asked four times, never answered. It does not block the POC. It does decide what Phase 13
writes down.

---

## 4. Your own work, blocking nobody but the LLM track

Plan steps 7–9. No API key needed, no code, no other person:

- **Step 7** — the task spec: closed-set classification of short Hinglish text, two variants
- **Step 8** — the non-functional requirements: latency, cost per call, accuracy floor
- **Step 9** — **the PII position, settled before talking to any vendor**

Step 9 is the one that should not be left until a vendor conversation is already underway.
