package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConfigurationService {
    private static final String[] READ_ROLES = {"INSIDE_SALES", "SALES_MANAGER", "OPERATIONS", "FINANCE"};
    public enum Kind {
        UOM("uoms", "Units of measure", false, "OPERATIONS"),
        WAREHOUSE("warehouses", "Warehouses", true, "OPERATIONS"),
        TERMS("payment_terms", "Payment terms", true, "FINANCE");
        final String table;
        final String title;
        final boolean entityScoped;
        final String writer;
        Kind(String table, String title, boolean entityScoped, String writer) {
            this.table = table; this.title = title; this.entityScoped = entityScoped; this.writer = writer;
        }
        public String getTitle() { return title; }
        public String getKey() { return name().toLowerCase(java.util.Locale.ROOT); }
        public boolean isTerms() { return this == TERMS; }
        public boolean isUom() { return this == UOM; }
        static Kind parse(String value) {
            for (Kind kind : values()) if (kind.getKey().equals(value)) return kind;
            throw new DomainException(404, "Configuration not found");
        }
    }
    public record Entry(long id, String code, String name, Integer days, Integer quantityScale, boolean active, long version) {}
    private final JdbcTemplate jdbc;
    private final AccessControl access;
    private final AuditLog audit;
    public ConfigurationService(JdbcTemplate jdbc, AccessControl access, AuditLog audit) {
        this.jdbc = jdbc; this.access = access; this.audit = audit;
    }
    public List<Entry> list(long ws, long entity, Kind kind, String search) {
        access.requireContext(ws, entity, false, kind == Kind.TERMS ? new String[]{"FINANCE"} : READ_ROLES);
        String q = search == null ? "" : search.trim();
        if (q.length() > 200) throw new DomainException(400, "Search is too long");
        String sql = "SELECT id,code,name," + (kind == Kind.TERMS ? "days" : "NULL::integer") + "," + (kind == Kind.UOM ? "quantity_scale" : "NULL::integer") + ",active,version FROM " + kind.table
                + " WHERE workspace_id=?" + (kind.entityScoped ? " AND entity_id=?" : "")
                + " AND (position(lower(?) in lower(code)) > 0 OR position(lower(?) in lower(name)) > 0) ORDER BY code,id";
        Object[] args = kind.entityScoped ? new Object[]{ws, entity, q, q} : new Object[]{ws, q, q};
        return jdbc.query(sql, (rs, n) -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5), rs.getBoolean(6), rs.getLong(7)), args);
    }
    public Entry get(long ws, long entity, Kind kind, long id) {
        access.requireContext(ws, entity, false, kind == Kind.TERMS ? new String[]{"FINANCE"} : READ_ROLES);
        return find(ws, entity, kind, id, false);
    }
    private Entry find(long ws, long entity, Kind kind, long id, boolean lock) {
        String sql = "SELECT id,code,name," + (kind == Kind.TERMS ? "days" : "NULL::integer") + "," + (kind == Kind.UOM ? "quantity_scale" : "NULL::integer") + ",active,version FROM " + kind.table
                + " WHERE workspace_id=?" + (kind.entityScoped ? " AND entity_id=?" : "") + " AND id=?" + (lock ? " FOR UPDATE" : "");
        Object[] args = kind.entityScoped ? new Object[]{ws, entity, id} : new Object[]{ws, id};
        List<Entry> rows = jdbc.query(sql, (rs, n) -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5), rs.getBoolean(6), rs.getLong(7)), args);
        if (rows.isEmpty()) throw new DomainException(404, "Configuration not found");
        return rows.get(0);
    }
    @Transactional
    public long save(long ws, long entity, Kind kind, Long id, Long expectedVersion, String code, String name, Integer days, Integer quantityScale) {
        AccessControl.Scope scope = access.requireContext(ws, entity, true, kind.writer);
        code = text(code, 80, "Code", "code"); name = text(name, 200, "Name", "name");
        if (kind == Kind.TERMS && (days == null || days < 0 || days > 3650)) throw new DomainException(400, "Days must be between 0 and 3650", "days");
        if (kind != Kind.TERMS && days != null) throw new DomainException(400, "Unexpected days field", "days");
        if (kind == Kind.UOM && (quantityScale == null || quantityScale < 0 || quantityScale > 6)) throw new DomainException(400, "Quantity precision must be between 0 and 6", "quantityScale");
        if (kind != Kind.UOM && quantityScale != null) throw new DomainException(400, "Unexpected quantity precision", "quantityScale");
        try {
            long result;
            long version;
            if (id == null) {
                if (expectedVersion != null) throw new DomainException(400, "Unexpected version");
                String sql = "INSERT INTO " + kind.table + " (workspace_id," + (kind.entityScoped ? "entity_id," : "")
                        + "code,name," + (kind == Kind.TERMS ? "days," : kind == Kind.UOM ? "quantity_scale," : "") + "created_by) VALUES ("
                        + (kind.entityScoped ? "?,?," : "?,") + "?,?," + (kind == Kind.TERMS || kind == Kind.UOM ? "?," : "") + "?) RETURNING id";
                Object[] args = kind == Kind.TERMS ? new Object[]{ws,entity,code,name,days,scope.actor().id()}
                        : kind == Kind.UOM ? new Object[]{ws,code,name,quantityScale,scope.actor().id()} : new Object[]{ws,entity,code,name,scope.actor().id()};
                result = jdbc.queryForObject(sql, Long.class, args); version = 1;
            } else {
                Entry current = find(ws, entity, kind, id, true);
                if (expectedVersion == null || current.version() != expectedVersion) throw new DomainException(409, "Configuration has changed");
                String sql = "UPDATE " + kind.table + " SET code=?,name=?," + (kind == Kind.TERMS ? "days=?," : kind == Kind.UOM ? "quantity_scale=?," : "")
                        + "version=version+1,updated_at=now() WHERE workspace_id=?" + (kind.entityScoped ? " AND entity_id=?" : "") + " AND id=?";
                Object[] args = kind == Kind.TERMS ? new Object[]{code,name,days,ws,entity,id}
                        : kind == Kind.UOM ? new Object[]{code,name,quantityScale,ws,id} : new Object[]{code,name,ws,entity,id};
                jdbc.update(sql, args); result = id; version = current.version() + 1;
            }
            audit.record(scope.actor(), ws, entity, id == null ? "CREATE" : "UPDATE", kind.table, Long.toString(result), version, "SUCCESS", "configuration");
            return result;
        } catch (DataIntegrityViolationException ex) { throw new DomainException(409, "Configuration conflicts with existing data"); }
    }
    @Transactional
    public void deactivate(long ws, long entity, Kind kind, long id, long expectedVersion) {
        AccessControl.Scope scope = access.requireContext(ws, entity, true, kind.writer);
        Entry current = find(ws, entity, kind, id, true);
        if (current.version() != expectedVersion) throw new DomainException(409, "Configuration has changed");
        String sql = "UPDATE " + kind.table + " SET active=false,version=version+1,updated_at=now() WHERE workspace_id=?"
                + (kind.entityScoped ? " AND entity_id=?" : "") + " AND id=?";
        jdbc.update(sql, kind.entityScoped ? new Object[]{ws,entity,id} : new Object[]{ws,id});
        audit.record(scope.actor(), ws, entity, "DEACTIVATE", kind.table, Long.toString(id), current.version()+1, "SUCCESS", "active");
    }
    private static String text(String value, int max, String label, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new DomainException(400, label + " is required or too long", field);
        return value.trim();
    }
}
