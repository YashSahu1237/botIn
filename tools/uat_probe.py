#!/usr/bin/env python3
"""
Runs tools/uat-probe.sql against UAT and writes a readable report.

WHY THIS EXISTS: the mysql command-line client has no bottle on macOS 14, and
building it from source pulls in openssl. PyMySQL is pure Python — it installs
in seconds and needs no compiler.

READ ONLY. Every statement in the .sql file is a SELECT or a SHOW, and this
runner opens the connection with autocommit off and never commits.

USAGE
    pip3 install --user pymysql
    python3 tools/uat_probe.py                 # reads ~/.botin-uat.cnf
    python3 tools/uat_probe.py --cnf /path/to/other.cnf

The credentials are read from the .cnf file and are never printed, never
written to the output file, and never passed on the command line.
"""
import argparse, configparser, os, sys, pathlib, datetime

DEFAULT_SQL = pathlib.Path(__file__).with_name("uat-probe.sql")


def load_credentials(cnf_path):
    path = pathlib.Path(os.path.expanduser(cnf_path))
    if not path.exists():
        sys.exit(f"no credentials file at {path}\n"
                 f"create it first — see the header of tools/uat-probe.sql")
    parser = configparser.ConfigParser()
    parser.read(path)
    if "client" not in parser:
        sys.exit(f"{path} has no [client] section")
    c = parser["client"]
    return dict(host=c.get("host"), port=int(c.get("port", 3306)),
                user=c.get("user"), password=c.get("password"))


def statements(sql_text):
    """Strip full-line -- comments, then split on ';'. The probe file has no
    semicolons inside its comments, and no string literals containing one."""
    lines = [ln for ln in sql_text.splitlines() if not ln.strip().startswith("--")]
    for raw in "\n".join(lines).split(";"):
        s = raw.strip()
        if s:
            yield s


def render(cursor, out):
    if cursor.description is None:
        out.write("    (no result set)\n")
        return
    headers = [d[0] for d in cursor.description]
    rows = cursor.fetchall()
    if not rows:
        out.write("    (0 rows)\n")
        return
    table = [headers] + [["NULL" if v is None else str(v) for v in r] for r in rows]
    widths = [max(len(r[i]) for r in table) for i in range(len(headers))]
    bar = "  +" + "+".join("-" * (w + 2) for w in widths) + "+"
    out.write(bar + "\n")
    for i, row in enumerate(table):
        out.write("  |" + "|".join(f" {c:<{w}} " for c, w in zip(row, widths)) + "|\n")
        if i == 0:
            out.write(bar + "\n")
    out.write(bar + f"\n    {len(rows)} row(s)\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cnf", default="~/.botin-uat.cnf")
    ap.add_argument("--sql", default=str(DEFAULT_SQL),
                    help="probe file to run (default tools/uat-probe.sql)")
    args = ap.parse_args()
    sql_file = pathlib.Path(args.sql)
    out_file = pathlib.Path.cwd() / (sql_file.stem + "-output.txt")

    try:
        import pymysql
    except ImportError:
        sys.exit("pymysql is not installed. Run:\n    pip3 install --user pymysql")

    creds = load_credentials(args.cnf)
    sql = sql_file.read_text(encoding="utf-8")

    print(f"connecting to {creds['host']}:{creds['port']} as {creds['user']} ...")
    try:
        conn = pymysql.connect(autocommit=False, connect_timeout=15,
                               charset="utf8mb4", **creds)
    except Exception as e:
        sys.exit(f"CONNECT FAILED: {e}\n\n"
                 f"  2003 / timed out  -> not on the office network or VPN\n"
                 f"  1045              -> wrong user or password\n"
                 f"  1044              -> connected, but no rights on that schema")

    ok = failed = 0
    with conn, open(out_file, "w", encoding="utf-8") as out:
        out.write(f"BOTIn UAT read probe\n")
        out.write(f"run at {datetime.datetime.now().isoformat(timespec='seconds')}\n")
        out.write(f"host   {creds['host']}:{creds['port']}\n")
        out.write(f"user   {creds['user']}\n")
        out.write("=" * 78 + "\n\n")
        for stmt in statements(sql):
            out.write(f"---- {stmt.splitlines()[0][:120]}\n")
            try:
                with conn.cursor() as cur:
                    cur.execute(stmt)
                    render(cur, out)
                ok += 1
            except Exception as e:
                out.write(f"    !! REFUSED OR FAILED: {e}\n")
                failed += 1
            out.write("\n")
        out.write("=" * 78 + f"\n{ok} statement(s) ran, {failed} refused or failed.\n")

    print(f"done — {ok} ran, {failed} refused or failed")
    print(f"report written to {out_file}")
    if failed:
        print("failures are recorded in the report; they do not stop the rest.")


if __name__ == "__main__":
    main()
