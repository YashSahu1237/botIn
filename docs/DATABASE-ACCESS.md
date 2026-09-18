# Seeing the data live — the 7 tables and Flowable's

Two databases exist and they are not the same thing.

| | What it holds | Who writes it |
|---|---|---|
| **BOTIn's own** | the 7 tables + ~25 Flowable `ACT_*` tables | this service |
| **UAT** | `ysmdm_admin`, `ysmdm_users`, `ysmdm_employees` | the existing platform. **BOTIn only ever reads it** |

This document is about the first one.

---

## Which database is running depends on how you started it

| Command | Database | Can a client connect? |
|---|---|---|
| `-Dspring-boot.run.profiles=demo,console -Dspring-boot.run.useTestClasspath=true` | **H2, in memory** | **Not with psql.** Use the browser console below |
| the same, with `BOTIN_DB_URL` set to a Postgres | **Postgres** | Yes — psql, DBeaver, anything |

The in-memory default exists so the demo starts with nothing installed. It has no port and
no file, and it is gone when the JVM stops.

---

## A. The demo, as it stands — the browser SQL console

Start it as usual, then open:

```
http://localhost:8080/h2-console
```

| Field | Value |
|---|---|
| JDBC URL | `jdbc:h2:mem:botin-demo` |
| User Name | `sa` |
| Password | *(blank)* |

Press **Connect** and every table is in the left-hand tree — the 7 of ours and all of
Flowable's. Run a query, run a case in the console, run the query again: the rows appear
while you watch.

**This is on the `demo` profile and nowhere else.** An unauthenticated SQL console must not
exist beside real data, and it cannot here: the demo profile refuses to start when a UAT
datasource is configured.

## B. Against Postgres, when you want the data to survive a restart

```bash
export BOTIN_DB_URL=jdbc:postgresql://localhost:5432/botin
export BOTIN_DB_USER=botin
export BOTIN_DB_PASSWORD=botin
mvn spring-boot:run -Dspring-boot.run.profiles=demo,console
```

No `useTestClasspath` — that flag exists only to borrow test-scoped H2.

```bash
psql -h localhost -U botin -d botin
\dt
```

Flyway creates the 7 tables and Flowable creates its own on first boot.

---

## The 7 tables BOTIn owns

| Table | What one row is |
|---|---|
| `concern_catalogue` | one concern. **The wiring**: which fact provider, which decision table, which kill switch, active or not |
| `help_session` | one conversation. Its L1/L2, the reference given, the answer as JSON, its status |
| `ticket` | **Gate 1.** Created only when a concern deserves one — a T0 leaves none |
| `ticket_action` | one attempt to move money or state. Written **before** the external call, updated after |
| `conversation_message` | the turns |
| `escalation_context` | what the agent opens. One per ticket; **the ticket id is the key** |
| `sp_counter` | durable per-partner counters |

## The Flowable tables worth knowing

| Table | What it is |
|---|---|
| `ACT_RU_EXECUTION` | processes **running right now** — including ones asleep at agent-connect |
| `ACT_RU_TASK` | the agent queue. A row here is a case waiting for a person |
| `ACT_RU_VARIABLE` | variables of running processes. **Deleted the moment an instance completes** |
| `ACT_HI_PROCINST` | every process that ever ran. `BUSINESS_KEY_` is the **help session id** |
| `ACT_HI_ACTINST` | every step that ever ran, in order. This is what the console's "what it touched" reads |
| `ACT_HI_VARINST` | **variables, durably** — including `factsJson` |
| `ACT_RE_PROCDEF` / `ACT_RE_DEPLOYMENT` | the deployed BPMN and DMN definitions |

`ACT_RU_*` is *runtime*, `ACT_HI_*` is *history*, `ACT_RE_*` is *repository*. The runtime
rows for an instance disappear when it finishes; the history rows stay.

---

## Queries that show something

**1. Gate 1, as a number.** Raise a FORGET_MPIN case, then:

```sql
SELECT s.id, s.sp_id, s.l2_concern, s.status,
       (SELECT count(*) FROM ticket t WHERE t.help_session_id = s.id) AS tickets
FROM help_session s
ORDER BY s.started_at DESC
LIMIT 5;
```

A deflection shows `tickets = 0`. Not a closed ticket — none.

**2. The money trail.** Every attempt, including the ones that failed:

```sql
SELECT a.action_code, a.status, a.amount_paise, a.external_reference,
       a.attempted_at, a.completed_at
FROM ticket_action a
ORDER BY a.attempted_at DESC
LIMIT 10;
```

A row with `attempted_at` set and `completed_at` null is a call whose outcome we never
learned — which is exactly the case that must never be retried.

**3. The facts the decision actually saw.**

```sql
SELECT p.business_key_ AS session_id, v.text_ AS facts_json
FROM act_hi_varinst  v
JOIN act_hi_procinst p ON p.proc_inst_id_ = v.proc_inst_id_
WHERE v.name_ = 'factsJson'
ORDER BY v.create_time_ DESC
LIMIT 5;
```

**4. The path a case took, from the engine's own record.**

```sql
SELECT a.act_id_, a.act_name_, a.start_time_, a.duration_
FROM act_hi_actinst  a
JOIN act_hi_procinst p ON p.proc_inst_id_ = a.proc_inst_id_
WHERE p.business_key_ = '<sessionId>'
  AND a.act_type_ <> 'sequenceFlow'
ORDER BY a.start_time_;
```

**5. A process asleep waiting for a person.** Run the agent-connect case and stop before
completing it:

```sql
SELECT e.id_, e.act_id_, e.proc_def_id_ FROM act_ru_execution e;
SELECT t.id_, t.name_, t.assignee_       FROM act_ru_task t;
```

Restart the service and run them again — **the rows are still there.** That is the
durability claim, as two queries rather than an assertion.

**6. What is switched on.**

```sql
SELECT l2_code, active, process_key, fact_provider, dmn_key, togglz_flag
FROM concern_catalogue
ORDER BY l1_code, display_order;
```

---

## Two things to avoid

**Do not write to these tables by hand while the service is running.** Flowable holds
optimistic locks on its rows; an outside `UPDATE` produces a stale-state exception in a
place unrelated to what you changed.

**`/demo/reset` deletes ticket and ticket_action rows.** That is deliberate and confined to
the demo profile, but it means a run you were studying can be cleared by the next run.
