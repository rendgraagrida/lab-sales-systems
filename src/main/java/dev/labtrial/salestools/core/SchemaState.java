package dev.labtrial.salestools.core;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class SchemaState implements ApplicationRunner {
    public static final int VERSION = 3;
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
        Integer applied = db.queryForObject("""
            select count(*) from app.flyway_schema_history
            where success and type='SQL' and version in ('1','2','3')
            """, Integer.class);
        Integer unexpected = db.queryForObject("""
            select count(*) from app.flyway_schema_history
            where not success or (type='SQL' and version not in ('1','2','3'))
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
        if (applied == null || applied != VERSION || unexpected == null || unexpected != 0
                || !Boolean.FALSE.equals(privileged)) {
            throw new IllegalStateException("Expected M1 schema and non-owner runtime identity are required.");
        }
        Integer catalogue = db.queryForObject("select count(*) from app.roles where code in ('INSIDE_SALES','SALES_MANAGER','OPERATIONS','FINANCE','SYSTEM_ADMIN')", Integer.class);
        Integer routines = db.queryForObject("""
            select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace
            where n.nspname='app' and p.pronargs=0 and not p.prosecdef
              and p.proname in ('reject_audit_mutation','protect_approved_price_book',
                'protect_approved_price_lines','journal_entry_immutable','journal_line_immutable',
                'journal_balance_guard','legal_entity_currency_guard')
              and has_function_privilege(current_user,p.oid,'EXECUTE')
            """, Integer.class);
        if (catalogue == null || catalogue != 5 || routines == null || routines != 7) {
            throw new IllegalStateException("Expected M1 role catalogue and restricted invoker functions are required.");
        }
        return Map.of("status", "UP", "application", "lab-sales-systems", "schemaVersion", VERSION,
                      "revision", revision, "mode", "LOCAL_SIMULATION");
    }
}
