"""Prove the move, without a compiler.

Three checks, each exact rather than heuristic. They are sound because the project has
no duplicate simple type names apart from one nested `Recharge`, which each file declares
for itself.
"""
import re, os, sys, glob
sys.path.insert(0, 'tools')
from java_symbols import MAIN, TEST, strip_code, scan

symbols, owners, dupes = scan([TEST, MAIN])
problems = []
ambiguous: list[str] = []

def fail(kind, msg): problems.append(f'{kind}: {msg}')

def pkg_of_path(path):
    """The package a file MUST declare, derived the way javac derives it.

    Was: root package + '.' + dirname, with a special case for the top-level file whose
    dirname is ''. The special case tested for '.' and the value was '', so BotinApplication
    was told to declare `in.yesmadam.botin.` — a trailing dot, and a compile error on line 1.

    The check passed anyway, because it compared the file's package against a value produced
    by THIS SAME function. Both sides were wrong in the same way, so they agreed. A check that
    derives the expected value the way the thing under test derives it is not a check.

    Now taken straight from the path under src/{main,test}/java, which is the actual rule,
    with no special case to get wrong.
    """
    base = 'src/main/java' if path.startswith(MAIN) else 'src/test/java'
    pkg = os.path.dirname(os.path.relpath(path, base)).replace(os.sep, '.')
    if not pkg or pkg.startswith('.') or pkg.endswith('.') or '..' in pkg:
        raise SystemExit(f'structure_check: cannot derive a package for {path} (got {pkg!r})')
    return pkg


# every declared FQN, top-level and nested
declared_fqns = set()
for f, (pkg, outer, names) in owners.items():
    for n in names:
        declared_fqns.add(f'{pkg}.{outer}' if n == outer else f'{pkg}.{outer}.{n}')

WORD = re.compile(r'\b[A-Z]\w*\b')

for f in sorted(owners):
    src = open(f).read()
    pkg, outer, names = owners[f]

    # --- 1. the package line must equal the directory ---------------------------
    want = pkg_of_path(f)
    if pkg != want:
        fail('package', f'{f}\n        declares {pkg}\n        but sits in {want}')

    # --- 2. every botin import must point at a type that exists -----------------
    imports = re.findall(r'^import (?:static )?(in\.yesmadam\.botin[\w.]*)\s*;', src, re.M)
    for imp in imports:
        if imp.endswith('.*'):
            fail('import', f'{f}: wildcard {imp} survived the move')
        elif imp not in declared_fqns:
            fail('import', f'{f}: imports {imp}, which no file declares')

    # --- 3. every project name the file uses must be reachable ------------------
    #
    # A capitalised token is NOT proof that a project type is meant. Two cases broke this:
    #
    #   @Table on six JPA entities is jakarta.persistence.Table, but the project also has a
    #   nested DecisionTableReader.Table — the mover imported the project one over it and
    #   the entities stopped compiling.
    #
    #   DecisionService.Explained needs no import; it is already qualified by its outer name.
    #
    # So: a nested type counts as used only when it appears UNQUALIFIED, and a file carrying
    # a non-project wildcard import can legitimately get a name from there, which no check
    # without a classpath can rule out. Those are counted and reported rather than assumed.
    imported_simple = {i.rsplit('.', 1)[1] for i in imports}
    foreign_wildcard = bool(re.search(r'^import (?!in\.yesmadam\.botin)[\w.]+\.\*\s*;', src, re.M))
    body = strip_code(src)
    body = re.sub(r'^package [^\n]*\n|^import [^\n]*\n', '', body, flags=re.M)

    for n in set(WORD.findall(body)):
        if n not in symbols or n in names or n in imported_simple:
            continue
        # Written only as Outer.Nested, or only as a full package path: already resolved.
        if not re.search(r'(?<![\w.])' + n + r'\b', body):
            continue
        fqn = symbols[n]
        owner, _, last = fqn.rpartition('.')
        top_level = not owner.rsplit('.', 1)[-1][:1].isupper()
        if top_level and owner == pkg:
            continue                                     # same package, no import needed
        if foreign_wildcard:
            ambiguous.append(f'{f}: {n}')                # the wildcard could supply it
            continue
        fail('unreachable', f'{f}\n        uses {n} ({fqn}) with no import')

# --- 4. no reference anywhere to a package that no longer exists -----------------
live = {pkg for pkg, _, _ in owners.values()}
for f in sorted(glob.glob('src/**/*.java', recursive=True)) + ['src/main/resources/application.yml',
                                                              'src/main/resources/application-demo.yml']:
    for m in re.finditer(r'in\.yesmadam\.botin(?:\.[a-z][\w]*)+', open(f).read()):
        ref = m.group(0)
        if ref in live or any(p.startswith(ref + '.') for p in live) or ref == 'in.yesmadam.botin':
            continue
        fail('stale package', f'{f}: {ref}')

print(f'files checked: {len(owners)}   types: {len(symbols)}')
print(f'names left unchecked (a non-project wildcard import could supply them): {len(ambiguous)}')
if problems:
    print(f'\nMOVE CHECK FAILED — {len(problems)} problem(s):\n')
    for p in problems[:80]:
        print('  ' + p)
    if len(problems) > 80: print(f'  ... and {len(problems)-80} more')
    sys.exit(1)
print('\nMOVE CHECK PASSED — packages match directories, every import resolves, '
      'every used type is reachable, no stale package names remain.')
