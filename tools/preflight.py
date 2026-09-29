#!/usr/bin/env python3
"""PRE-FLIGHT. Everything the test suite checks that does not need a JVM.

WHY THIS EXISTS. This project cannot be compiled in the environment its code is written
in: Maven Central is refused at the org proxy, so `mvn test` only ever runs on the
developer's machine. That makes the feedback loop a round trip through a person, and it
puts a premium on not spending one of those trips on a mistake a text scan would catch.

Several of the suite's guards are pure text and structure checks — the XML parses, the
DMN namespace is the one Flowable reads, a declared fact is documented, the two
statements of the fact contract agree. All of those can run here, in a second, before a
single file is handed over.

WHAT IT CANNOT DO: compile Java, or catch a logic error. Those still need the real run.
The point is to stop wasting real runs on the ones it can.

    python3 tools/preflight.py
"""

import glob
import html
import os
import re
import sys
import xml.dom.minidom
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FAILURES: list[str] = []


def fail(check: str, detail: str) -> None:
    FAILURES.append(f"{check}: {detail}")


def read(path) -> str:
    return Path(path).read_text(encoding="utf-8")



def source(basename: str) -> Path:
    """Locate a .java file by NAME, anywhere under src/.

    Hardcoded package paths were the one thing in this script the L1/L2 restructure broke.
    A path is a guess about where a class lives; a name is what the class IS. Fails loudly
    on missing or ambiguous rather than silently skipping a check.
    """
    hits = [Path(f) for f in glob.glob(str(ROOT / "src/**" / basename), recursive=True)]
    if len(hits) != 1:
        raise SystemExit(f"preflight: expected exactly one {basename} under src/, found {len(hits)}")
    return hits[0]


# --------------------------------------------------------------- deployable XML
def check_deployable_xml() -> None:
    """Mirrors DeployableXmlTest. One bad character here kills the whole context."""
    files = glob.glob(str(ROOT / "src/main/resources/processes/*.xml")) + \
            glob.glob(str(ROOT / "src/main/resources/dmn/*.dmn"))
    if not files:
        fail("deployable-xml", "no process or dmn files found at all")

    for f in files:
        name = os.path.basename(f)
        try:
            xml.dom.minidom.parse(f)
        except Exception as e:  # noqa: BLE001 — any parse failure is the same problem
            fail("deployable-xml", f"{name} does not parse: {e}")
            continue

        for match in re.finditer(r"<!--(.*?)-->", read(f), re.S):
            if "--" in match.group(1):
                fail("deployable-xml",
                     f"{name} has '--' inside an XML comment — illegal, and fatal to the engine")


# ------------------------------------------------------------- the DMN namespace
def check_dmn_namespace() -> None:
    """Mirrors DmnNamespaceTest. DMN 1.5 stops the engine and takes the context with it."""
    supported = "https://www.omg.org/spec/DMN/20191111/MODEL/"
    for f in glob.glob(str(ROOT / "src/main/resources/dmn/*.dmn")):
        content = read(f)
        name = os.path.basename(f)
        if supported not in content:
            fail("dmn-namespace", f"{name} is not DMN 1.3 — Flowable 7.0.1 cannot parse it")
        if "DMNDI" in content:
            fail("dmn-namespace", f"{name} still has a modeller diagram block")


# ------------------------------------------------- facts: code vs register vs tables
def declared_fact_keys() -> dict[str, set[str]]:
    """Every provider's factKeys(), read out of the Java source.

    Text-scraped rather than reflected, which is crude and good enough: this only has to
    agree with what a human reading the file would conclude.
    """
    keys: dict[str, set[str]] = {}
    sources = {f: read(f) for f in glob.glob(str(ROOT / "src/main/java/**/*.java"), recursive=True)}

    # RESOLVED ACROSS ALL SOURCES, NOT WITHIN ONE FILE. Two providers return a shared
    # helper's keys rather than a literal. While the helper and the providers lived in one
    # class, searching that file worked. The L1/L2 split moved the helper to
    # shared/classification and the providers to two concern folders — and this check did
    # not fail, it just stopped finding those two concerns. The count in the banner was the
    # only symptom. A name that does not exist behaves exactly like a value that is false.
    shared_keys: set[str] = set()
    for src in sources.values():
        helper = re.search(r"classifierKeys\(\)\s*\{\s*return\s*Set\.of\(([^;]*?)\)\s*;", src, re.S)
        if helper:
            shared_keys = set(re.findall(r'"(\w+)"', helper.group(1)))
            break

    for f, src in sources.items():
        for concern_match in re.finditer(
                r'concernCode\(\)\s*\{\s*return\s*"([A-Z0-9_]+)"', src):
            concern = concern_match.group(1)
            after = src[concern_match.end():concern_match.end() + 1200]
            set_of = re.search(r"factKeys\(\)\s*\{\s*return\s*(?:\w+\.)?"
                               r"(?:classifierKeys\(\)|Set\.of\(([^;]*?)\))\s*;", after, re.S)
            # LOUD, not `continue`. A provider whose keys cannot be read is a provider this
            # script silently stops checking — which is how the two above went missing.
            if not set_of:
                fail("facts", f"{Path(f).name} declares {concern} but its factKeys() could not "
                              f"be read — every facts check below now skips this concern")
                continue
            if set_of.group(1):
                keys[concern] = set(re.findall(r'"(\w+)"', set_of.group(1)))
            elif shared_keys:
                keys[concern] = set(shared_keys)
            else:
                fail("facts", f"{Path(f).name}: {concern} returns the shared classifierKeys(), "
                              f"but no file declares it")
    return keys


def check_signal_register(fact_keys: dict[str, set[str]]) -> None:
    """Mirrors SignalRegisterTest. A fact nobody documented is a fact nobody decided about."""
    register = read(ROOT / "docs/SIGNAL-REGISTER.md")
    for concern, keys in sorted(fact_keys.items()):
        if concern not in register:
            fail("signal-register", f"{concern} has a provider but no entry")
        for key in sorted(keys):
            if f"`{key}`" not in register:
                fail("signal-register", f"{concern}.{key} is not documented")


def check_table_contract(fact_keys: dict[str, set[str]]) -> None:
    """Mirrors ProviderContractTest: the two independent statements must agree.

    They are restated in two test files on purpose, so this checks the provider against
    BOTH rather than against one shared truth.
    """
    for test_file, pattern in (
            ("ProviderContractTest.java",
             r'"([A-Z0-9_]+)",\s*Set\.of\(((?:[^()]|\([^()]*\))*?)\)'),
            ("DecisionTableTest.java",
             r'"([a-z0-9-]+-decision)",\s*List\.of\(((?:[^()]|\([^()]*\))*?)\)')):
        src = read(source(test_file))
        declared = {m.group(1): set(re.findall(r'"(\w+)"', m.group(2)))
                    for m in re.finditer(pattern, src, re.S)}

        for concern, keys in fact_keys.items():
            # DecisionTableTest is keyed by dmn key, not concern code.
            candidates = [v for k, v in declared.items()
                          if k == concern or k == concern.lower().replace("_", "-") + "-decision"]
            if not candidates:
                continue
            if candidates[0] != keys:
                fail("fact-contract",
                     f"{concern} in {os.path.basename(test_file)}: provider has {sorted(keys)}, "
                     f"the table contract says {sorted(candidates[0])}")


# ------------------------------------------------------- catalogue pointers resolve
def split_sql_tuple(text: str) -> list[str | None]:
    """Split one `( ... )` VALUES tuple into fields, respecting quotes.

    Returns unquoted strings, with SQL NULL as Python None. Written because a label in the
    seed contains commas and a comma-counting regex silently dropped that whole row.
    """
    fields: list[str | None] = []
    cur, in_quote, i = [], False, 0
    body = text.strip()[1:-1]
    while i < len(body):
        c = body[i]
        if in_quote:
            if c == "'":
                if i + 1 < len(body) and body[i + 1] == "'":   # '' is an escaped quote
                    cur.append("'")
                    i += 1
                else:
                    in_quote = False
            else:
                cur.append(c)
        elif c == "'":
            in_quote = True
        elif c == ",":
            fields.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    fields.append("".join(cur).strip())
    return [None if f.upper() == "NULL" else f for f in fields]



def catalogue_final_state() -> dict[str, dict]:
    """Replay the migrations in order to get the catalogue as it will actually be.

    The seed is not the answer: V4 deactivates two concerns and V5 repoints five at the
    generic process. Checking the INSERT alone reports five failures that are not real —
    which is worse than not checking, because a noisy guard gets ignored.
    """
    state: dict[str, dict] = {}

    for path in sorted(glob.glob(str(ROOT / "src/main/resources/db/migration/*.sql"))):
        sql = read(path)

        # Seed rows: ('CODE', 'l1', 'label', 'label', n, active, mandatory, 'proc', ...)
        # PARSED FIELD BY FIELD, NOT BY A POSITIONAL REGEX.
        #
        # The regex that was here counted columns with `[^,]*` and therefore assumed no
        # label contains a comma. One does: 'Recharge kiya, pese cut gaye, aaye nahi'. That
        # single row — an ACTIVE concern — matched nothing, so it was absent from the
        # catalogue state and every check built on it quietly skipped RECHARGE_DEBIT_NO_CREDIT.
        # Nothing failed. The concern was simply not being checked.
        for tuple_src in re.finditer(r"\(\s*'[A-Z0-9_]+',.*?\)(?=\s*[,;])", sql, re.S):
            cols = split_sql_tuple(tuple_src.group(0))
            if len(cols) != 15:
                continue
            state[cols[0]] = {
                "l1": cols[1],
                "active": cols[5].upper() == "TRUE",
                "process": cols[7],
                "dmn": cols[9],
            }

        # UPDATE ... SET <col> = <value> WHERE l2_code = 'X'  |  IN ('X', 'Y', ...)
        for upd in re.finditer(
                r"UPDATE\s+concern_catalogue\s+SET\s+(\w+)\s*=\s*([^\s]+?)\s+WHERE\s+l2_code\s+"
                r"(?:=\s*'([A-Z0-9_]+)'|IN\s*\(([^)]*)\))", sql, re.S | re.I):
            column, value = upd.group(1), upd.group(2).strip().rstrip(";")
            codes = [upd.group(3)] if upd.group(3) else re.findall(r"'([A-Z0-9_]+)'", upd.group(4))
            for code in codes:
                if code not in state:
                    continue
                if column == "active":
                    state[code]["active"] = value.upper().startswith("TRUE")
                elif column == "process_key":
                    state[code]["process"] = value.strip("'")
                elif column == "dmn_key":
                    state[code]["dmn"] = value.strip("'")
    return state


def check_catalogue_pointers() -> None:
    """Mirrors the two pointer guards. A non-null pointer is not a working one."""
    processes = {Path(f).name.split(".")[0]
                 for f in glob.glob(str(ROOT / "src/main/resources/processes/*.xml"))}
    decisions = {Path(f).stem for f in glob.glob(str(ROOT / "src/main/resources/dmn/*.dmn"))}

    state = catalogue_final_state()
    if not state:
        fail("catalogue-pointers", "could not read any concern rows from the migrations")
        return

    active = {code: row for code, row in state.items() if row["active"]}
    if not active:
        fail("catalogue-pointers", "no active concerns after replaying the migrations")

    for code, row in sorted(active.items()):
        if not row["process"]:
            fail("catalogue-pointers", f"{code} is active with no process_key")
        elif row["process"] not in processes:
            fail("catalogue-pointers",
                 f"{code} is active and points at process '{row['process']}', "
                 f"which is not a file in processes/")

        if row["dmn"] and row["dmn"] not in decisions:
            fail("catalogue-pointers",
                 f"{code} points at decision '{row['dmn']}', which is not a file in dmn/")


# ---------------------------------------------------------- delegates actually exist
def check_delegate_beans() -> None:
    """Every ${someDelegate} in a BPMN must be a @Component with that bean name.

    NOT in the Java test suite, and it should be: an unresolvable delegate expression
    fails at RUNTIME, on the first partner to reach that step, not at startup.
    """
    beans = set()
    for f in glob.glob(str(ROOT / "src/main/java/in/yesmadam/botin/**/*.java"), recursive=True):
        beans.update(re.findall(r'@Component\("(\w+)"\)', read(f)))

    for f in glob.glob(str(ROOT / "src/main/resources/processes/*.xml")):
        for expr in re.findall(r"delegateExpression=\"\$\{(\w+)\}\"", read(f)):
            if expr not in beans:
                fail("delegate-beans",
                     f"{os.path.basename(f)} calls ${{{expr}}}, which is not a named @Component")


def check_conditional_beans() -> None:
    """Two beans guarded by conditions on the SAME property.

    WHY THIS RULE EXISTS. Two clients were guarded like this:

        @ConditionalOnProperty(name = "botin.classifier.url")
        @ConditionalOnProperty(name = "botin.classifier.url", havingValue = "", matchIfMissing = true)

    Exclusive on "missing" versus "present", and correct until a config line made the
    property PRESENT BUT EMPTY — a third state neither was written for, and one that
    satisfies both. Two beans, one injection point, and the whole context failed to
    start. Nothing about either annotation hints that the other exists, and the change
    that broke it was in a different file again.

    So: any property guarding more than one bean is reported, and the author has to
    satisfy themselves that missing, empty and set are each handled once. Usually the
    better answer is a @Bean factory with an if statement, where all three are visible.
    """
    guards: dict[str, list[str]] = {}
    for f in glob.glob(str(ROOT / "src/main/java/**/*.java"), recursive=True):
        for prop in re.findall(r'@ConditionalOnProperty\([^)]*name\s*=\s*"([^"]+)"', read(f)):
            guards.setdefault(prop, []).append(os.path.basename(f))

    for prop, files in sorted(guards.items()):
        if len(files) > 1:
            fail("conditional-beans",
                 f"'{prop}' guards {len(files)} beans ({', '.join(sorted(files))}). "
                 "Check missing / empty / set are each handled exactly once — a "
                 "present-but-empty value has satisfied two 'exclusive' conditions before")


def yaml_keys(path) -> set[str]:
    """Flatten application.yml to dotted keys, by indentation. No PyYAML dependency.

    Deliberately simple, because it only has to understand the file we actually have:
    nested maps, comments and blank lines. It is not a YAML parser and does not pretend
    to be one — if this file ever grows list-valued configuration, this needs revisiting
    rather than trusting.
    """
    keys: set[str] = set()
    stack: list[tuple[int, str]] = []
    for raw in read(path).splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if ":" not in line:
            continue
        name = line.split(":", 1)[0].strip()
        if not name or name.startswith("-"):
            continue
        while stack and stack[-1][0] >= indent:
            stack.pop()
        dotted = ".".join([p for _, p in stack] + [name])
        keys.add(dotted)
        stack.append((indent, name))
    return keys


def check_value_placeholders() -> None:
    """Every @Value("${x}") with NO default must name a property application.yml declares.

    WHY THIS RULE EXISTS. A @Value placeholder that resolves to nothing is not a warning
    and not a null — it is an IllegalArgumentException while the context is being built,
    which means the context does not start, which means EVERY @SpringBootTest in the suite
    errors at once. This project has now had that outcome twice from two different causes,
    and the output looks identical each time: a wall of errors that says nothing about the
    one character that caused it.

    A placeholder WITH a default (${x:something}) is skipped — it cannot fail this way, and
    a deliberate empty default is how several things here are switched off.

    Spring relaxed binding means botin.foo-bar and botin.fooBar are the same property, so
    both spellings are accepted before reporting a miss.
    """
    declared = yaml_keys(ROOT / "src/main/resources/application.yml")
    relaxed = {k.replace("-", "").lower() for k in declared}

    for f in sorted(glob.glob(str(ROOT / "src/main/java/**/*.java"), recursive=True)):
        for placeholder in re.findall(r'@Value\(\s*"\$\{([^}"]+)\}"', read(f)):
            if ":" in placeholder:
                continue                      # has a default; cannot fail to resolve
            if placeholder.replace("-", "").lower() in relaxed:
                continue
            fail("value-placeholder",
                 f"{os.path.basename(f)} reads ${{{placeholder}}} with no default, and "
                 "application.yml does not declare it. An unresolvable placeholder fails "
                 "the whole context at startup, not just this bean")


# ------------------------------------------------------- the decision tables, parsed
def decision_tables() -> dict[str, dict]:
    """Every .dmn, as {hitPolicy, outputs, rules[{tier, action, outcomeType, catchAll}]}.

    Shared by the four guards below so the parsing is written once. Cells are matched by
    POSITION, exactly as the engine matches them — the <output> declaration order is what
    binds an outputEntry to a column, and nothing else does.
    """
    tables: dict[str, dict] = {}
    for f in sorted(glob.glob(str(ROOT / "src/main/resources/dmn/*.dmn"))):
        src = read(f)
        outputs = re.findall(r'<output\b[^>]*?\blabel="([^"]*)"', src)
        hit = re.search(r'hitPolicy="([A-Z]+)"', src)
        rules = []
        for block in re.split(r"(?=<rule\b)", src)[1:]:
            # html.unescape FIRST. The cells are XML: a string literal arrives as
            # &quot;PATH_1&quot; and a comparison as &gt; 30000. Stripping quotes before
            # unescaping strips nothing, and every action code then carries &quot; on both
            # ends and matches no template — the guard cries wolf on all of them.
            ins = [html.unescape(m.group(1)).strip() for m in
                   re.finditer(r"<inputEntry\b[^>]*>\s*<text>(.*?)</text>", block, re.S)]
            outs = [html.unescape(m.group(1)).strip().strip('"') for m in
                    re.finditer(r"<outputEntry\b[^>]*>\s*<text>(.*?)</text>", block, re.S)]
            def col(label):
                return outs[outputs.index(label)] if label in outputs and len(outs) > outputs.index(label) else None
            rules.append({"tier": col("tier"), "action": col("action"),
                          "outcomeType": col("outcomeType"),
                          "catchAll": all(c == "" for c in ins) and bool(ins)})
        tables[Path(f).stem] = {"hitPolicy": hit.group(1) if hit else None,
                                "outputs": outputs, "rules": rules}
    return tables


# --------------------------------------------------------------- 1. the catch-all
def check_catch_all(tables: dict[str, dict]) -> None:
    """Every table ends in a rule that matches everything.

    NOT in the Java suite, and it guards the worst failure mode in the system: a table
    that matches nothing returns null, and in spike 2 a null tier reaching a gateway
    produced NO ERROR ANYWHERE. It did not crash. It stopped, silently.

    Also checks the hit policy, because a catch-all only means 'last resort' under FIRST.
    """
    for key, t in tables.items():
        if t["hitPolicy"] != "FIRST":
            fail("catch-all",
                 f"{key} has hitPolicy={t['hitPolicy']} — every table here is written for FIRST, "
                 f"where row order is logic")
        if not t["rules"]:
            fail("catch-all", f"{key} has no rules at all")
            continue
        if not t["rules"][-1]["catchAll"]:
            fail("catch-all",
                 f"{key}: the LAST rule is not a catch-all. A case matching nothing returns "
                 f"null and the gateway reading tier has nowhere to go")
        for i, r in enumerate(t["rules"][:-1]):
            if r["catchAll"]:
                fail("catch-all",
                     f"{key}: rule {i + 1} of {len(t['rules'])} matches everything, so no rule "
                     f"below it can ever fire")


# ------------------------------------------------- 2. every ending has words to say
def check_response_templates(tables: dict[str, dict]) -> None:
    """An action the bot RESOLVES on must have a partner-facing sentence.

    Scoped deliberately, so the guard cannot cry wolf:
      - only concerns running `concern-generic`, because only that process has a resolve
        step that looks a template up. FORGET_MPIN builds its own deeplink response.
      - not T3, and not REROUTE: neither reaches resolve. A T3 ends at agentHandoff with
        the one fixed AGENT_CONNECTING sentence.

    Today ResolveDelegate logs a warning and the partner gets an unprepared ending. That
    is a defect a text scan can catch before a partner does.
    """
    # Read from the per-concern properties files, which is where the wording lives now.
    # Also checks OWNERSHIP: two files defining the same action code is a startup failure
    # in ResponseTemplates, and catching it here means catching it without a JVM.
    templates: set[str] = set()
    owner: dict[str, str] = {}
    files = sorted(glob.glob(str(ROOT / "src/main/resources/**/templates.properties"),
                             recursive=True))
    for path in files:
        where = str(Path(path).relative_to(ROOT / "src/main/resources"))
        for line in read(path).splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            action = line.split("=", 1)[0].strip()
            if action in owner:
                fail("response-templates",
                     f"'{action}' is defined in BOTH {owner[action]} and {where} — one action "
                     f"code, one owner, or which sentence the partner reads depends on "
                     f"classpath order")
            owner[action] = where
            templates.add(action)

    if not templates:
        fail("response-templates",
             f"no templates.properties found under src/main/resources — every bot resolution "
             f"would reach the partner as 'nothing prepared'")
        return

    state = catalogue_final_state()
    generic = {row["dmn"] for row in state.values()
               if row["active"] and row["process"] == "concern-generic" and row["dmn"]}

    for key in sorted(generic):
        for i, r in enumerate(tables.get(key, {}).get("rules", []), start=1):
            if r["tier"] == "T3" or r["outcomeType"] == "REROUTE" or not r["action"]:
                continue
            if r["action"] not in templates:
                fail("response-templates",
                     f"{key} rule {i} resolves with action '{r['action']}', which has no "
                     f"template — the partner would get an ending nobody wrote")


# ------------------------------------------- 3. an action nothing can perform
def check_actions_performable(tables: dict[str, dict]) -> None:
    """Every T2 action on an ACTIVE concern must have an ActionService that performs it.

    DecideDelegate catches this at RUNTIME and escalates, which is the correct failure —
    but it means a concern can be switched on, decide correctly, and quietly turn every
    case into a ticket. That should be visible at build time, not inferred from a graph.
    """
    codes = set()
    for f in glob.glob(str(ROOT / "src/main/java/**/*.java"), recursive=True):
        codes |= set(re.findall(r'actionCode\(\)\s*\{\s*return\s+"([A-Z0-9_]+)"', read(f)))

    state = catalogue_final_state()
    by_dmn = {row["dmn"]: code for code, row in state.items() if row["active"] and row["dmn"]}

    for key, concern in sorted(by_dmn.items()):
        for i, r in enumerate(tables.get(key, {}).get("rules", []), start=1):
            if r["tier"] != "T2" or not r["action"]:
                continue
            if r["action"] not in codes:
                fail("action-performable",
                     f"{concern} is ACTIVE and rule {i} of {key} decides T2 '{r['action']}', "
                     f"but no ActionService performs it — every such case becomes a ticket")


# ------------------------------------------------- 4. an active concern nobody tests
def check_every_concern_tested(tables: dict[str, dict]) -> None:
    """An active concern must be exercised by a decision-table test or an acceptance case.

    A concern with rules and no test is a concern whose behaviour is an assumption.
    """
    table_test = read(source("DecisionTableTest.java"))
    scenarios = read(source("DemoScenarios.java"))

    for code, row in sorted(catalogue_final_state().items()):
        if not row["active"] or not row["dmn"]:
            continue
        if row["dmn"] not in table_test and f'"{code}"' not in scenarios:
            fail("concern-tested",
                 f"{code} is active but neither DecisionTableTest nor DemoScenarios mentions "
                 f"it — its behaviour is an assumption")


# ------------------------------------------- 5. the folder a concern lives in tells the truth
def check_concern_folders(catalogue: dict) -> None:
    """`concern/<l1>/<l2>/` must agree with the catalogue, and every provider must be in one.

    Deliberately NOT a name table mapping `amount` to `AMOUNT_RELATED`: a table like that is
    a third place the truth lives, and it would need editing for every new L1. This checks
    CONSISTENCY instead, which is the failure that actually happens — the same L1 ending up
    with two folders because the second person to add a concern guessed a different name.

    The catalogue row stays the identity. The folder is documentation, and this is what keeps
    the documentation honest.
    """
    by_folder: dict[str, set[str]] = {}
    by_l1: dict[str, set[str]] = {}

    for f in glob.glob(str(ROOT / "src/main/java/**/*.java"), recursive=True):
        src = read(f)
        for m in re.finditer(r'concernCode\(\)\s*\{\s*return\s*"([A-Z0-9_]+)"', src):
            code = m.group(1)
            rel = Path(f).relative_to(ROOT / "src/main/java/in/yesmadam/botin")
            parts = rel.parts
            if parts[0] != "concern" or len(parts) != 4:
                fail("concern-folder",
                     f"{code} is declared in {rel}, which is not concern/<l1>/<l2>/ — a "
                     f"developer looking for this concern has nowhere to look")
                continue
            l1_folder, l2_folder = parts[1], parts[2]
            row = catalogue.get(code)
            if row is None:
                fail("concern-folder",
                     f"{code} has a provider in concern/{l1_folder}/{l2_folder}/ but no "
                     f"catalogue row — the row is the identity, the folder is only a label")
                continue
            by_folder.setdefault(l1_folder, set()).add(row["l1"])
            by_l1.setdefault(row["l1"], set()).add(l1_folder)

    for folder, l1s in sorted(by_folder.items()):
        if len(l1s) > 1:
            fail("concern-folder",
                 f"concern/{folder}/ holds concerns from more than one L1: {sorted(l1s)}")
    for l1, folders in sorted(by_l1.items()):
        if len(folders) > 1:
            fail("concern-folder",
                 f"L1 {l1} is spread across {sorted(folders)} — one L1, one folder, or the "
                 f"tree stops being a map of the catalogue")



def main() -> int:
    fact_keys = declared_fact_keys()

    check_deployable_xml()
    check_dmn_namespace()
    check_signal_register(fact_keys)
    check_table_contract(fact_keys)
    check_catalogue_pointers()
    check_delegate_beans()
    check_conditional_beans()
    check_value_placeholders()

    tables = decision_tables()
    check_catch_all(tables)
    check_response_templates(tables)
    check_actions_performable(tables)
    check_every_concern_tested(tables)
    check_concern_folders(catalogue_final_state())

    print(f"providers found: {', '.join(sorted(fact_keys))}")
    print(f"decision tables parsed: {len(tables)}\n")
    if FAILURES:
        print(f"PRE-FLIGHT FAILED — {len(FAILURES)} problem(s):\n")
        for f in FAILURES:
            print(f"  {f}")
        print("\nFix these before running mvn test; none of them need a JVM to find.")
        return 1

    print("pre-flight passed — everything checkable without a compiler is consistent.")
    print("This does NOT mean it compiles or that the logic is right. Run mvn test.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
