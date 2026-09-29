"""Build {simple name -> FQN} for every project type, top-level and nested.

Nested types matter because six files import them through a wildcard
(`in.yesmadam.botin.session.HelpSessionService.*`), and a move that drops the
wildcard has to put back an explicit import for each nested name actually used.
"""
import re, os, glob

MAIN = 'src/main/java/in/yesmadam/botin'
TEST = 'src/test/java/in/yesmadam/botin'

TOP = re.compile(r'^(?:public |abstract |final |sealed |non-sealed )*'
                 r'(?:class|interface|record|enum|@interface) (\w+)', re.M)
# [ \t]+ and NOT \s+ : \s matches a newline, so `^\s+` could start on a blank line,
# swallow the line break and match a TOP-LEVEL declaration as though it were nested.
# Every top-level type then looked like `pkg.Outer.Outer` and the checker reported 64
# phantom problems. Indentation means spaces or tabs, never a newline.
NESTED = re.compile(r'^[ \t]+(?:public |protected |private |static |abstract |final )*'
                    r'(?:class|interface|record|enum) (\w+)', re.M)

def strip_code(s: str) -> str:
    """Remove comments and string literals — a name inside prose is not a reference."""
    s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)
    s = re.sub(r'//[^\n]*', ' ', s)
    s = re.sub(r'"(?:\\.|[^"\\])*"', '""', s)
    s = re.sub(r"'(?:\\.|[^'\\])*'", "''", s)
    return s

def scan(roots):
    """-> {simple: fqn}, {fqn_of_file: (path, package)}, {path: set(simple names declared)}"""
    symbols, owners, dupes = {}, {}, {}
    for root in roots:
        for f in sorted(glob.glob(root + '/**/*.java', recursive=True)):
            src = open(f).read()
            pkg = re.search(r'package ([\w.]+);', src).group(1)
            body = strip_code(src)
            tops = TOP.findall(body)
            if not tops:                       # @interface at column 0 with annotations above
                tops = re.findall(r'^(?:public )?@interface (\w+)', body, re.M)
            outer = tops[0]
            names = {outer: f'{pkg}.{outer}'}
            for n in NESTED.findall(body):
                names[n] = f'{pkg}.{outer}.{n}'
            for n, fqn in names.items():
                if n in symbols:
                    dupes.setdefault(n, [symbols[n]]).append(fqn)
                symbols[n] = fqn
            owners[f] = (pkg, outer, set(names))
    return symbols, owners, dupes

if __name__ == '__main__':
    symbols, owners, dupes = scan([MAIN, TEST])
    print(f'types found: {len(symbols)}  files: {len(owners)}')
    print(f'DUPLICATE simple names: {len(dupes)}')
    for n, v in dupes.items():
        print('  ', n, '->', v)
