---
name: metabase-firebird-driver
description: >
  Use whenever the working directory is the metabase-firebird-driver repo, or the user asks
  about the Metabase Firebird driver, or mentions Firebird/Jaybird in a Metabase context. Covers
  ALL work in this repo: editing the standard driver (`src/metabase/driver/firebird.clj`) or the
  legacy driver (`legacy/src/metabase/driver/firebird_legacy.clj`), building JARs, bumping
  versions, cutting GitHub releases, updating the README, triaging and responding to GitHub
  issues, running the docker test env, and diagnosing HoneySQL/schema-sync/temporal-grouping
  bugs. Also trigger on generic-sounding requests inside this repo like "check issues", "make a
  release", "rebuild", "update the readme" — they always mean the Firebird-driver workflows here.
---

# Metabase Firebird Driver Maintainer

You maintain a Metabase plugin driver that lets Metabase talk to FirebirdSQL databases. Two
drivers ship from this repo and are kept in **lock-step feature parity** except where noted:

- **Standard** (`:firebird`) — Jaybird 6.0.3, Firebird 2.5 – 5.0, versioned `1.6.x`.
- **Legacy** (`:firebird-legacy`) — Jaybird 2.2.15, Firebird 1.5 – 2.5, versioned `1.7.x-legacy`.

Both are Clojure, both extend `:sql-jdbc`, both use HoneySQL 2. The legacy driver exists purely
because Jaybird 6 dropped wire-protocol 10/11 support; users on Firebird 1.5 – 2.0 have no other
option. **A fix in one driver almost always needs to land in the other** — parity is the default.

## Working reflexes

- **📦 Two drivers, one fix.** Almost every bugfix or feature change belongs in **both**
  `src/metabase/driver/firebird.clj` and `legacy/src/metabase/driver/firebird_legacy.clj`. Search
  for the analogous code in the other driver before you commit. Skip only when the fix genuinely
  can't apply (e.g. Firebird 1.5 lacks BOOLEAN, so any BOOLEAN-specific code is main-only).
- **🎯 Ground the fix in a Metabase source reference.** The sibling `../metabase/` checkout is
  the source of truth for what the HoneySQL / QP contract actually is at HEAD. When you can't
  explain WHY something behaves as it does, `grep` `../metabase/src/metabase/driver/sql/` — most
  driver bugs are contract mismatches (a schema tightened, an alias renamed, a method dispatched
  differently). Never invent a fix from Firebird knowledge alone.
- **🚦 Release order is legacy-first, standard-last.** GitHub shows the most recently published
  release as "Latest" at the top of the download list. Users on Firebird 3+/4+ (the majority) get
  the standard driver, so **the standard release must be published LAST** so it wins the "Latest"
  slot. Cutting legacy last would hide the current driver behind the legacy one.
- **🧾 Verify the installed version by log format, not the user's word.** When a reporter says
  "I'm on v1.6.5 and X is still broken", first check whether their debug log matches that
  version's log format. Every version this project ships changes at least one log line (either
  by adding a new field or renaming a message); a mismatch means the JAR they think they deployed
  isn't the one Metabase actually loaded. Ask them to run
  `unzip -p plugins/firebird.metabase-driver.jar metabase-plugin.yaml | grep version` and to
  restart Metabase (plugins are loaded once at startup).
- **🐳 Reproduce in Docker before shipping a "fix" that's more than a one-liner.** `test/docker-compose.yml`
  spins Firebird 3+/4+ on `:3054`, Firebird 1.5 on `:3055`, and Metabase on `:3001`, with the
  freshly-built JAR mounted from `../target/`. If you can't reproduce the bug there, don't ship a
  speculative fix — ask the reporter for the DDL / server log first.
- **📣 Verbose background builds.** Both build scripts are long-running (~30s cached, ~3.5min
  cold). Kick them off with `run_in_background: true` and let the notification fire. Never sleep
  or poll. If a build hangs, that's a real signal — investigate rather than retry.

## Directory layout

```
.
├── build.sh                                       # main driver build
├── deps.edn                                       # main deps
├── metabase-plugin.yaml                           # main version (root — the one build.sh reads)
├── resources/metabase-plugin.yaml                 # main version (duplicated — keep in sync)
├── src/metabase/driver/firebird.clj               # main driver (~640 lines)
├── target/firebird.metabase-driver.jar            # build output
│
├── legacy/build.sh                                # legacy driver build
├── legacy/deps.edn                                # legacy deps (Jaybird 2.2.15)
├── legacy/resources/metabase-plugin.yaml          # legacy version
├── legacy/src/metabase/driver/firebird_legacy.clj # legacy driver
├── legacy/target/firebird-legacy.metabase-driver.jar
│
├── test/docker-compose.yml                        # Firebird 4 + Firebird 1.5 + Metabase
├── test/init-test-data.sql                        # covers all Firebird data types
├── test/metabase/driver/firebird_test.clj         # unit tests (37/92 passing per v1.6.4)
├── test/metabase/test/data/firebird.clj           # test-data driver spec
│
└── README.md                                      # public download links + release notes
```

Both drivers depend on a **sibling `../metabase/` checkout** or `METABASE_PATH` env var. The
build fails clearly if it's missing.

## Key hooks in the code

- `[:firebird :field]` — HoneySQL dispatch for `:field` clauses. Must forward `:temporal-unit`
  through `sql.qp/date` (issue #4). Must qualify source-query column refs with `__mb_source`
  (issue #9). Any change here MUST be mirrored in the legacy driver.
- `sql.qp/date [:firebird X]` — one method per temporal unit (`:month`, `:year`, etc.). Each
  returns a HoneySQL form that becomes both the SELECT projection AND the GROUP BY expression.
  Firebird is strictly typed — filter LHS and RHS must have matching types (issue #8), so
  `:year` truncates to a first-of-year `DATE` rather than returning `EXTRACT(YEAR ...)` INTEGER.
- `firebird-format` in `firebird.clj` — post-processing over HoneySQL-generated SQL: unquotes
  DATEADD/EXTRACT unit names, converts HoneySQL's `SUBSTRING(col, FROM x FOR y)` to Firebird's
  `SUBSTRING(col FROM x FOR y)` (issue #7), swaps `LIMIT/OFFSET` for Firebird's `FIRST/SKIP`.
  Regex-level; be careful with new regexes — over-broad matches have caused SQL corruption bugs.
- `describe-table` / `describe-fields-sql` — schema sync. Emits maps that must conform to
  Metabase's `TableMetadataField` Malli schema. Booleans must be actual booleans (not INTEGER
  0/1) — issue #10 uncovered `:database-required` and `:pk?` returning INTEGERs, which passed in
  prod (Malli enforcement is off) but silently broke `NOT NULL` reporting.
- `add-interval-honeysql-form` / `current-datetime-honeysql-form` — used by relative filters
  (`> now`, `< 30 days ago`, etc.). Any Firebird-specific interval arithmetic goes here.

## Release workflow (the canonical steps)

Perform in this order. **Legacy releases first, standard second.**

1. **Fix the bug** in `src/metabase/driver/firebird.clj` AND, if applicable,
   `legacy/src/metabase/driver/firebird_legacy.clj`.
2. **Bump versions** in three yaml files:
   - `metabase-plugin.yaml` (root, canonical for main build)
   - `resources/metabase-plugin.yaml` (duplicate — must be kept in sync so nobody's confused when
     they open it)
   - `legacy/resources/metabase-plugin.yaml`
3. **Build both JARs** in parallel background jobs — they use separate target dirs so there's no
   conflict:
   ```bash
   cd legacy && ./build.sh   # → legacy/target/firebird-legacy.metabase-driver.jar
   ./build.sh                 # → target/firebird.metabase-driver.jar
   ```
4. **Verify JAR versions** — `unzip -p <jar> metabase-plugin.yaml | grep version` on both. The
   file inside the JAR is what Metabase actually reads.
5. **Commit** with a single message covering both drivers. Follow the existing style — describe
   the WHY, list version bumps at the bottom, include the Co-Authored-By trailer.
6. **Push to `develop`** — that's this repo's main branch (not `main`/`master`).
7. **Tag and release LEGACY first** with `--notes-file` (never inline `--notes` — heredoc
   escaping breaks backticks and quotes):
   ```bash
   git tag v1.7.X-legacy && git push origin v1.7.X-legacy
   gh release create v1.7.X-legacy legacy/target/firebird-legacy.metabase-driver.jar \
     --target develop --title "..." --notes-file /tmp/legacy-notes.md
   ```
8. **Tag and release STANDARD second** — same shape, main JAR:
   ```bash
   git tag v1.6.X && git push origin v1.6.X
   gh release create v1.6.X target/firebird.metabase-driver.jar \
     --target develop --title "..." --notes-file /tmp/main-notes.md
   ```
9. **Verify with `gh release list`** — the standard release should be marked `Latest` at the top,
   legacy directly below it.
10. **Update the README** in the same session:
    - Downloads table (line ~10-11): bump both links.
    - Compatibility table: add new rows.
    - Release notes section: add an entry describing the fix and linked issues.
    - `> Note:` line: bump legacy-driver link.
    Commit and push separately from the code commit — the README bump is a distinct concern.
11. **Comment on the fixed issues** with the release links and the specific commit/behaviour
    change so reporters can confirm.

### Release-notes template

Keep them uniform. Use this structure:

```markdown
## Fix
<one-paragraph explanation of the bug and how the fix works>

## Compatibility
- Metabase X.Y+ (if the fix depends on a Metabase-side change)
- Firebird 3.x / 4.x via Jaybird 6.0.3. For Firebird 1.5 – 2.5 use the [legacy driver](link).

## Install
Place `firebird.metabase-driver.jar` into your Metabase `plugins/` directory and restart.

## Issue tracker
- [#N — title](link)
```

Write to `/tmp/<version>-notes.md`, feed via `--notes-file`, `rm` after.

## Issue triage — the standard flow

1. `gh issue list --state open --limit 30` — get open issues with dates.
2. For each, check `updatedAt` and last comment author:
   - **Reporter hasn't replied in 3+ weeks after we asked for info** → close with a polite note
     inviting reopen.
   - **We haven't replied at all** → investigate; do not close.
   - **Fresh activity from reporter** → prioritize; they gave us signal.
3. For any bug report, before touching code:
   - Confirm the reporter's driver version by asking them to inspect the JAR
     (`unzip -p ... | grep version`).
   - Check their log line's format against the version they claim (see the reflex above).
   - If their environment mentions Metabase 0.57+, prefer diagnosing against
     `../metabase/src/metabase/driver/sql/query_processor.clj` at HEAD.

## Known bug patterns

Every one of these has bit us at least once — check first when a new bug arrives.

- **Metabase alias renames.** Metabase core has renamed things in the middle of a driver's life
  (`:foreign-keys` → `:metadata/key-constraints`, `"source"` → `"__mb_source"`). When a bug
  reports "worked in 0.56, broken in 0.57", check the sibling `../metabase/` for a renamed
  symbol/keyword.
- **Firebird strict typing.** Filter LHS and RHS must have the same SQL type. `EXTRACT(YEAR
  FROM col)` returns INTEGER but a relative-year filter's RHS is a DATE — they don't compare
  (issue #8). Truncate to a matching type in `sql.qp/date` instead of returning the raw extract.
- **HoneySQL `typed` metadata handling.** Metabase's HoneySQL wrapper (`metabase.util.honey-sql-2`)
  changes how `:database-type` metadata is expressed between minor versions. `hx/cast` may need
  `[:lift {:database-type ...}]` wrapping in newer versions (see issue #11).
- **Padded CHAR columns in Firebird system tables.** `RDB$RELATION_NAME` etc are CHAR(63) padded
  with spaces. If comparisons fail unexpectedly, `TRIM()` the column in the WHERE.
- **Malli schema mismatches on sync output.** Metabase's `TableMetadataField` requires booleans
  for `:pk?` and `:database-required`. Returning INTEGER 0/1 works in prod (enforcement is off)
  but is technically broken and can bite when Malli is turned on. Coerce with `(= 1 v)`.
- **Clojure truthiness footgun.** In Clojure `(not 0)` is `false` (0 is truthy). Any code that
  does `(not <int-from-jdbc>)` to "negate a flag" is silently buggy — it always returns `false`.
- **Regex over-matching in `firebird-format`.** The post-processing regexes on line 289-296 are
  greedy. Adding a new one? Test it against the SQL for aggregations, subqueries, and JOINs
  before shipping — a bad regex can corrupt arbitrary queries silently.

## Docker test environment

```bash
cd test && docker compose up -d
# Metabase: http://localhost:3001  (SYSDBA/masterkey via docker network)
# Firebird 4: localhost:3054 (SYSDBA/masterkey, metabase.fdb)
# Firebird 1.5: localhost:3055 (for legacy driver testing)
```

Driver JARs are mounted read-only from `../target/`, so **you must build first**. Metabase
picks them up on container restart:

```bash
./build.sh && docker compose restart metabase
```

`test/init-test-data.sql` provisions tables covering every Firebird data type — always the first
thing to check against after a schema-sync fix.

## Metabase source lookup — quick refs

- `../metabase/src/metabase/driver/sql/query_processor.clj` — HoneySQL dispatch,
  `source-query-alias`, `apply-temporal-bucketing`, `[:sql :field]`.
- `../metabase/src/metabase/driver/sql_jdbc/sync/describe_table.clj` — `describe-fields-sql`
  contract, Malli schemas for sync output.
- `../metabase/src/metabase/sync/interface.clj` — `TableMetadataField` schema.
- `../metabase/src/metabase/sync/fetch_metadata.clj` — the sync boundary where our
  `describe-table` output is consumed and validated.
- `../metabase/src/metabase/query_processor/middleware/add_implicit_clauses.clj` — the "Table
  X has no Fields" error is raised here at line ~49.

## Communication style

- **Reply on issues with concrete diagnostic asks**, not vague "please provide more info".
  Give the reporter an exact `unzip` command, or an exact SQL query to run, or three numbered
  outcomes to match against.
- **When commenting from the owner account, link to the release, the fix commit, and the
  specific method that changed.** Reporters trust maintainers who show their work.
- **Never claim a bug is fixed without a build-and-verify.** "Should work now" is not shippable.

## The list of open issues is a to-do list

Whenever the developer says "check for issues" or "any issues to look at?", run
`gh issue list --state open` and report a compact table (number, title, updated-at, whether the
reporter is waiting on us). Old issues where WE asked a question and the reporter never replied
in 3+ weeks are candidates for a polite close-with-reopen-invite (see the triage flow).
