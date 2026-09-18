# BOTIn — POC service

**This is the production seed, not a spike.** Migrations, transaction boundaries,
package structure and tests are built to keep. When the POC succeeds this codebase
moves into `empapi`; roughly four things get swapped and everything else carries over.

The throwaway R&D project is gone. What it taught is in the Component Notes.

## The two datasources — read this before running anything

| | Purpose | Writes? |
|---|---|---|
| **Primary** | Our 7 tables + Flowable's ~25 `ACT_*` tables | Yes — everything BOTIn owns |
| **UAT** | Fact providers read production-shaped tables | **Never** |

Nothing BOTIn creates goes anywhere near UAT. That is enforced structurally, not
by discipline — see `DataSourceConfig` for the four independent reasons DDL cannot
reach it. The fourth is the one that holds if the other three are ever wrong:
**the UAT user must be SELECT-only by GRANT.**

## Run it

```bash
createdb botin                      # local Postgres, yours, disposable

export BOTIN_DB_URL=jdbc:postgresql://localhost:5432/botin
export BOTIN_DB_USER=botin
export BOTIN_DB_PASSWORD=botin

export UAT_DB_URL=jdbc:postgresql://<uat-host>:5432/<db>
export UAT_DB_USER=botin_readonly   # SELECT only
export UAT_DB_PASSWORD=...

mvn spring-boot:run
```

Without UAT credentials, `UAT_ENABLED=false` runs everything else. Fact providers
then return no facts and say so in the log.

```bash
mvn test        # H2, UAT switched off entirely
```

## Phase 1 checkpoint

- app boots on an empty database
- our **7** tables appear, created by Flyway
- Flowable's **~25** `ACT_*` tables appear alongside, in the same datasource
- the catalogue holds **38** concerns, **8** active
- `mvn test` passes

## What is deliberately here already

**`TicketActionRecorder`** — `REQUIRES_NEW`, with the spike's rollback test promoted
into the real suite. It carries a `TODO` to re-run once UAT is wired, because a
second datasource changes which transaction manager Spring picks and a wrongly
wired one breaks this silently.

**`FeatureNameValidator`** — fails startup if any `concern_catalogue.togglz_flag`
is not a declared `BotinFeature`. Not tidiness: the R&D ladder proved Togglz
resolves an unknown flag name to `false` **silently**, so one typo would route a
whole concern to agents with no error anywhere.

**`FactProviderRegistry`** — fails startup on a missing or duplicate provider, for
the same reason. A null lookup at runtime becomes a table full of nulls, which
falls to the catch-all and looks like a business outcome rather than a bug.

**`sp_counter`** — the seventh table, not in the original design. The concern
mapping forces it: the pooled emergency cap, the monthly period-leave count and
the 25-job cycle allowances are **state this engine owns**, not facts it can read.

**All 38 concerns seeded, 8 active.** Seeding the full taxonomy makes the L1 menu
real and forces the "concern not available" path to be hit constantly rather than
discovered late.

## What needs you

**Every query in `TransportFactProvider` is marked `TODO(schema)`** and uses
placeholder table and column names. Send me the real UAT names — or point me at the
schema — and I will replace them. Nothing else in the service changes when they do;
that is the whole reason for reading a production-shaped database rather than a
synthetic one.

`transportPath` is **derived from an assumption**, in one method, flagged in the
code. The mapping labels rules Path 1/2/3 but never says what assigns a claim to a
path. When the real rule arrives it is one method and one column.

## Package layout

```
config/      two datasources, Flowable pinned to the primary
api/         REST controllers                       (Phase 2)
session/     HelpSession, Ticket — Gate 1 lives here
catalogue/   the single source of the taxonomy
process/     Flowable delegates                     (Phase 3)
decision/    DMN binding                            (Phase 5)
facts/       ConcernFactProvider + registry
action/      *ActionService — mocks only in the POC (Phase 6)
safety/      idempotency, REQUIRES_NEW, kill switch
agent/       escalation context + agent API         (Phase 9)
```

Split by layer from day one. Renaming a package later is cheap; discovering the
boundaries later is not.
