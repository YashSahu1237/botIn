package in.yesmadam.botin.facts;

/**
 * One column a fact provider depends on, named in full.
 *
 * WHY THIS TYPE EXISTS. The empapi entities do not carry @Column on every field, and
 * the repository has no persistence config in it — it is externalised — so the PHYSICAL
 * name of a camelCase field cannot be confirmed by reading the source. `arrivedAt300M`
 * is probably `arrived_at300_m` under Spring Boot's default naming strategy, but
 * "probably" is not good enough for a query that decides whether a partner gets paid.
 *
 * A wrong column name in a SELECT is not a silent null — it is a SQL error, which is
 * survivable. The unsurvivable version is a query that runs and returns nothing because
 * we matched on the wrong thing. So every dependency is declared here and checked
 * against information_schema at startup by UatSchemaProbe.
 *
 * @param confirmed true when the name was read from an explicit @Column in empapi or
 *                  verified by query; false when it is inferred from a field name
 */
public record UatColumn(String catalog, String table, String column, boolean confirmed) {

    public static UatColumn confirmed(String catalog, String table, String column) {
        return new UatColumn(catalog, table, column, true);
    }

    /** Inferred from an entity field name. Treat as a guess until the probe says otherwise. */
    public static UatColumn inferred(String catalog, String table, String column) {
        return new UatColumn(catalog, table, column, false);
    }

    public String qualified() { return catalog + "." + table + "." + column; }
}
