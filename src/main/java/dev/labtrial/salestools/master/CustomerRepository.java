package dev.labtrial.salestools.master;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {
    public record Customer(long id, String code, String name, String segment, String territory, String domain,
                           Long parentId, boolean active, long version) {}
    private final JdbcTemplate db;
    public CustomerRepository(JdbcTemplate db) { this.db = db; }
    private static final String VISIBLE = "(EXISTS (SELECT 1 FROM customer_entity_accounts a JOIN entity_memberships em ON em.workspace_id=a.workspace_id AND em.entity_id=a.entity_id JOIN legal_entities e ON e.workspace_id=em.workspace_id AND e.id=em.entity_id WHERE a.workspace_id=c.workspace_id AND a.customer_id=c.id AND a.active AND em.user_id=? AND em.active AND e.active) OR (c.created_by=? AND NOT EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.customer_id=c.id)))";
    private static Customer map(ResultSet r, int n) throws SQLException {
        return new Customer(r.getLong("id"), r.getString("code"), r.getString("name"), r.getString("segment"), r.getString("territory"), r.getString("domain"), (Long) r.getObject("parent_customer_id"), r.getBoolean("active"), r.getLong("version"));
    }
    public List<Customer> list(long workspace, long actor, String search) {
        return db.query("SELECT c.id,c.code,c.name,c.segment,c.territory,c.domain,c.parent_customer_id,c.active,c.version FROM customers c WHERE c.workspace_id=? AND " + VISIBLE + " AND (position(lower(?) in lower(c.code))>0 OR position(lower(?) in lower(c.name))>0) ORDER BY c.code,c.id", CustomerRepository::map, workspace,actor,actor,search,search);
    }
    public List<Customer> find(long workspace, long actor, long id, boolean lock) {
        return db.query("SELECT c.id,c.code,c.name,c.segment,c.territory,c.domain,c.parent_customer_id,c.active,c.version FROM customers c WHERE c.workspace_id=? AND " + VISIBLE + " AND c.id=?" + (lock ? " FOR UPDATE OF c" : ""), CustomerRepository::map, workspace,actor,actor,id);
    }
    public long insert(long workspace, long actor, String code, String name, String segment, String territory, String domain, Long parent) {
        return db.queryForObject("INSERT INTO customers(workspace_id,created_by,code,name,segment,territory,domain,parent_customer_id) VALUES (?,?,?,?,?,?,?,?) RETURNING id", Long.class, workspace,actor,code,name,segment,territory,domain,parent);
    }
    public void update(long workspace, long id, String code, String name, String segment, String territory, String domain, Long parent) {
        db.update("UPDATE customers SET code=?,name=?,segment=?,territory=?,domain=?,parent_customer_id=?,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?", code,name,segment,territory,domain,parent,workspace,id);
    }
    public void deactivate(long workspace, long id) {
        db.update("UPDATE customers SET active=false,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",workspace,id);
    }
    public boolean createsCycle(long workspace, long child, long parent) {
        Boolean result = db.queryForObject("WITH RECURSIVE ancestors(id,parent_customer_id) AS (SELECT id,parent_customer_id FROM customers WHERE workspace_id=? AND id=? UNION SELECT c.id,c.parent_customer_id FROM customers c JOIN ancestors a ON c.id=a.parent_customer_id WHERE c.workspace_id=?) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=?)", Boolean.class,workspace,parent,workspace,child);
        return Boolean.TRUE.equals(result);
    }
}
