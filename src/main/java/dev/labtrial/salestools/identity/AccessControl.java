package dev.labtrial.salestools.identity;

import dev.labtrial.salestools.core.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AccessControl {
    public record Actor(long id, String login, long securityVersion) {}
    public record Scope(Actor actor, long workspaceId, Long entityId, Set<String> roles) {
        public boolean has(String role) { return roles.contains(role); }
    }
    public record WorkspaceOption(long id, String code, String name, LocalDate businessDate, String state) {}
    public record EntityOption(long id, String code, String name, String currency) {}
    private final JdbcTemplate db;
    public AccessControl(JdbcTemplate db) { this.db = db; }
    public Actor actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof IdentityPrincipal p)) throw new DomainException(401, "Authentication required");
        return checkedActor(p, false);
    }
    Actor checkedActor(IdentityPrincipal p, boolean lock) {
        var rows = db.query("SELECT id,login,security_version FROM app_users WHERE id=? AND active AND security_version=?" + (lock ? " FOR UPDATE" : ""), (r,n) -> new Actor(r.getLong(1),r.getString(2),r.getLong(3)), p.id(), p.securityVersion());
        if (rows.isEmpty()) throw new DomainException(401, "Authentication required");
        return rows.get(0);
    }
    public Scope requireWorkspace(long workspaceId, boolean write, String... requiredRoles) { return require(workspaceId, null, write, requiredRoles); }
    public Scope requireContext(long workspaceId, long entityId, boolean write, String... requiredRoles) { return require(workspaceId, entityId, write, requiredRoles); }
    private Scope require(long workspaceId, Long entityId, boolean write, String... requiredRoles) {
        if (write && !TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Write authorization requires a transaction");
        if (write) db.queryForObject("SELECT pg_advisory_xact_lock(1279345485, 2)", Object.class);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof IdentityPrincipal p)) throw new DomainException(401, "Authentication required");
        Actor actor = checkedActor(p, write);
        String lock = write ? " FOR UPDATE" : "";
        if (db.query("SELECT active FROM workspace_memberships WHERE workspace_id=? AND user_id=?" + lock, (r,n) -> r.getBoolean(1), workspaceId, actor.id()).stream().noneMatch(Boolean::booleanValue)) throw new DomainException(404, "Context not found");
        Set<String> roles = db.query("SELECT role_code,active FROM user_roles WHERE workspace_id=? AND user_id=? ORDER BY role_code" + lock, (r,n) -> r.getBoolean(2) ? r.getString(1) : null, workspaceId, actor.id()).stream().filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        if (entityId != null && db.query("SELECT em.active AND e.active FROM entity_memberships em JOIN legal_entities e ON e.workspace_id=em.workspace_id AND e.id=em.entity_id WHERE em.workspace_id=? AND em.entity_id=? AND em.user_id=?" + (write ? " FOR UPDATE OF em" : ""), (r,n) -> r.getBoolean(1), workspaceId, entityId, actor.id()).stream().noneMatch(Boolean::booleanValue)) throw new DomainException(404, "Context not found");
        if (requiredRoles.length > 0 && java.util.Arrays.stream(requiredRoles).noneMatch(roles::contains)) throw new DomainException(403, "Access denied");
        return new Scope(actor, workspaceId, entityId, roles);
    }
    public List<WorkspaceOption> workspaces() {
        Actor a = actor();
        return db.query("SELECT w.id,w.code,w.name,w.business_date,w.initialization_state FROM scenario_workspaces w JOIN workspace_memberships m ON m.workspace_id=w.id WHERE m.user_id=? AND m.active ORDER BY w.name,w.id", (r,n) -> new WorkspaceOption(r.getLong(1),r.getString(2),r.getString(3),r.getObject(4,LocalDate.class),r.getString(5)), a.id());
    }
    public List<EntityOption> entities(long workspaceId) {
        Actor a = requireWorkspace(workspaceId,false).actor();
        return db.query("SELECT e.id,e.code,e.name,e.base_currency FROM legal_entities e JOIN entity_memberships m ON m.workspace_id=e.workspace_id AND m.entity_id=e.id WHERE e.workspace_id=? AND m.user_id=? AND m.active AND e.active ORDER BY e.name,e.id", (r,n) -> new EntityOption(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)), workspaceId,a.id());
    }
}
