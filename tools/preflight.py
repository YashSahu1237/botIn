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
    for f in glob.glob(str(ROOT / "src/main/java/in/yesmadam/botin/facts/*.java")):
        src = read(f)
        for concern_match in re.finditer(
                r'concernCode\(\)\s*\{\s*return\s*"([A-Z0-9_]+)"', src):
            concern = concern_match.group(1)
            after = src[concern_match.end():concern_match.end() + 1200]
            set_of = re.search(r"factKeys\(\)\s*\{\s*return\s*(?:\w+\.)?"
                               r"(?:classifierKeys\(\)|Set\.of\(([^;]*?)\))\s*;", after, re.S)
            if not set_of:
                continue
            if set_of.group(1):
                keys[concern] = set(re.findall(r'"(\w+)"', set_of.group(1)))
            else:
                helper = re.search(r"classifierKeys\(\)\s*\{\s*return\s*Set\.of\(([^;]*?)\)\s*;",
                                   src, re.S)
                if helper:
                    keys[concern] = set(re.findall(r'"(\w+)"', helper.group(1)))
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
            ("src/test/java/in/yesmadam/botin/facts/ProviderContractTest.java",
             r'"([A-Z0-9_]+)",\s*Set\.of\(((?:[^()]|\([^()]*\))*?)\)'),
            ("src/test/java/in/yesmadam/botin/decision/DecisionTableTest.java",
             r'"([a-z0-9-]+-decision)",\s*List\.of\(((?:[^()]|\([^()]*\))*?)\)')):
        src = read(ROOT / test_file)
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
        for row in re.finditer(
                r"\(\s*'([A-Z0-9_]+)',\s*'[A-Z0-9_]+',[^,]*,[^,]*,\s*\d+,\s*(TRUE|FALSE),"
                r"\s*(?:TRUE|FALSE),\s*(NULL|'[a-z0-9-]+'),\s*(NULL|'[A-Z0-9_]+'),"
                r"\s*(NULL|'[a-z0-9-]+')", sql):
            state[row.group(1)] = {
                "active": row.group(2) == "TRUE",
                "process": row.group(3).strip("'") if row.group(3) != "NULL" else None,
                "dmn": row.group(5).strip("'") if row.group(5) != "NULL" else None,
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

    print(f"providers found: {', '.join(sorted(fact_keys))}\n")
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
