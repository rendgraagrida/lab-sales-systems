package dev.labtrial.salestools.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class SchemaState implements ApplicationRunner {
    public static final int VERSION = 4;
    private static final List<String> MIGRATIONS = List.of(
        "V1__identity.sql", "V2__masters.sql", "V3__finance.sql", "V4__procurement_inventory_ap.sql");
    private static final List<Integer> CHECKSUMS = MIGRATIONS.stream().map(SchemaState::checksum).toList();
    private final JdbcTemplate db;
    private final String revision;
    public SchemaState(JdbcTemplate db, @Value("${lab.revision}") String revision) {
        this.db = db;
        if (!revision.matches("(?:[0-9a-f]{40}|[0-9a-f]{64}|content:[0-9a-f]{64})")) {
            throw new IllegalStateException("An exact candidate revision is required.");
        }
        this.revision = revision;
    }
    @Override public void run(ApplicationArguments ignored) { verify(); }
    public Map<String, Object> verify() {
        List<Map<String, Object>> applied = db.queryForList("""
            select version, script, checksum from app.flyway_schema_history
            where success and type='SQL' order by installed_rank
            """);
        Integer unexpected = db.queryForObject("""
            select count(*) from app.flyway_schema_history
            where not success or type not in ('SQL','SCHEMA')
            """, Integer.class);
        Boolean privileged = db.queryForObject("""
            select r.rolsuper or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls
               or has_database_privilege(current_user,current_database(),'CREATE')
               or has_database_privilege(current_user,current_database(),'TEMP')
               or has_schema_privilege(current_user,'app','CREATE')
               or has_schema_privilege(current_user,'public','CREATE')
               or exists(select 1 from pg_roles p where p.oid<>r.oid and pg_has_role(r.oid,p.oid,'MEMBER'))
            from pg_roles r where r.rolname=current_user
            """, Boolean.class);
        if (!matchesMigrations(applied) || unexpected == null || unexpected != 0
                || !Boolean.FALSE.equals(privileged)) {
            throw new IllegalStateException("Expected M2 schema and non-owner runtime identity are required.");
        }
        Integer catalogue = db.queryForObject("select count(*) from app.roles where code in ('INSIDE_SALES','SALES_MANAGER','OPERATIONS','FINANCE','SYSTEM_ADMIN')", Integer.class);
        Integer routines = db.queryForObject("""
            select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace
            where n.nspname='app' and p.pronargs=0 and not p.prosecdef
              and p.proname in ('reject_audit_mutation','protect_approved_price_book',
                'protect_approved_price_lines','journal_entry_immutable','journal_line_immutable',
                'journal_balance_guard','legal_entity_currency_guard','m2_document_guard','m2_child_guard')
              and has_function_privilege(current_user,p.oid,'EXECUTE')
            """, Integer.class);
        if (catalogue == null || catalogue != 5 || routines == null || routines != 9) {
            throw new IllegalStateException("Expected M2 role catalogue and restricted invoker functions are required.");
        }
        return Map.of("status", "UP", "application", "lab-sales-systems", "schemaVersion", VERSION,
                      "revision", revision, "mode", "LOCAL_SIMULATION");
    }

    private boolean matchesMigrations(List<Map<String, Object>> applied) {
        if (applied.size() != MIGRATIONS.size()) return false;
        for (int i = 0; i < MIGRATIONS.size(); i++) {
            Map<String, Object> row = applied.get(i);
            String name = MIGRATIONS.get(i);
            if (!Integer.toString(i + 1).equals(row.get("version")) || !name.equals(row.get("script"))
                    || !(row.get("checksum") instanceof Number checksum) || checksum.intValue() != CHECKSUMS.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static int checksum(String name) {
        InputStream stream = SchemaState.class.getResourceAsStream("/db/foundation/" + name);
        if (stream == null) throw new IllegalStateException("Embedded migration is missing: " + name);
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && line.startsWith("\uFEFF")) line = line.substring(1);
                first = false;
                crc.update(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Embedded migration cannot be read: " + name, e);
        }
        return (int) crc.getValue();
    }
}
