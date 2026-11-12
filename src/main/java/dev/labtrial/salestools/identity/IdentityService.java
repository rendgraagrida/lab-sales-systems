package dev.labtrial.salestools.identity;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private static final Set<String> ROLES=Set.of("INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE","SYSTEM_ADMIN");
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final PasswordEncoder encoder;
    public IdentityService(JdbcTemplate db, AccessControl access, AuditLog audit, PasswordEncoder encoder) { this.db=db; this.access=access; this.audit=audit; this.encoder=encoder; }
    static String required(String value,int max,String label,String field) { if(value==null || value.trim().isEmpty() || value.trim().length()>max) throw new DomainException(400,label+" is invalid",field); return value.trim(); }
    private String password(String value) { if(value==null || value.length()<16 || value.isBlank()) throw new DomainException(400,"Password must contain at least 16 characters","password"); return encoder.encode(value); }
    private AccessControl.Scope admin(long ws) { return access.requireWorkspace(ws,true,"SYSTEM_ADMIN"); }
    private void event(AccessControl.Scope s, Long entity, String action,String type,long id,long version) { audit.record(s.actor(),s.workspaceId(),entity,action,type,Long.toString(id),version,"SUCCESS",""); }
    private void stateEvent(AccessControl.Scope s, Long entity, String action,String type,long id,long version,boolean active) { audit.record(s.actor(),s.workspaceId(),entity,action,type,Long.toString(id),version,"SUCCESS","{\"active\":"+active+"}"); }
    private void changed(int count) { if(count!=1) throw new DomainException(409,"Record changed; reload and try again"); }
    private void scopedUser(long ws,long user) {
        if (db.queryForObject("SELECT count(*) FROM workspace_memberships WHERE workspace_id=? AND user_id=?",Integer.class,ws,user)==0) throw new DomainException(404,"User not found");
        db.query("SELECT id FROM app_users WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),user);
        db.query("SELECT user_id FROM workspace_memberships WHERE workspace_id=? AND user_id=? FOR UPDATE",(r,n)->r.getLong(1),ws,user);
        db.query("SELECT role_code FROM user_roles WHERE workspace_id=? AND user_id=? ORDER BY role_code FOR UPDATE",(r,n)->r.getString(1),ws,user);
    }
    // Global account changes affect every active workspace, not just the current one.
    // admin() already takes the shared transaction-scoped security lock.
    private void authorizeGlobalAccountChange(AccessControl.Scope actor, long user) {
        if (actor.actor().id() == user) return;
        Boolean denied = db.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM workspace_memberships target
              WHERE target.user_id=? AND target.active AND NOT EXISTS (
                SELECT 1 FROM workspace_memberships mine
                JOIN user_roles role ON role.workspace_id=mine.workspace_id AND role.user_id=mine.user_id
                WHERE mine.workspace_id=target.workspace_id AND mine.user_id=? AND mine.active
                  AND role.role_code='SYSTEM_ADMIN' AND role.active
              )
            )
            """, Boolean.class, user, actor.actor().id());
        if (Boolean.TRUE.equals(denied)) throw new DomainException(403,"Access denied");
    }
    private void preserveAdministrator(long user, Long workspace) {
        Boolean last = db.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM workspace_memberships target
              JOIN app_users target_user ON target_user.id=target.user_id AND target_user.active
              JOIN user_roles target_role ON target_role.workspace_id=target.workspace_id
                AND target_role.user_id=target.user_id AND target_role.role_code='SYSTEM_ADMIN' AND target_role.active
              WHERE target.user_id=? AND target.active AND (CAST(? AS bigint) IS NULL OR target.workspace_id=?)
                AND NOT EXISTS (
                  SELECT 1 FROM workspace_memberships other
                  JOIN app_users other_user ON other_user.id=other.user_id AND other_user.active
                  JOIN user_roles other_role ON other_role.workspace_id=other.workspace_id
                    AND other_role.user_id=other.user_id AND other_role.role_code='SYSTEM_ADMIN' AND other_role.active
                  WHERE other.workspace_id=target.workspace_id AND other.user_id<>target.user_id AND other.active
                )
            )
            """, Boolean.class, user, workspace, workspace);
        if (Boolean.TRUE.equals(last)) throw new DomainException(409,"At least one active workspace administrator is required");
    }
    public List<Map<String,Object>> users(long ws) { access.requireWorkspace(ws,false,"SYSTEM_ADMIN"); return db.queryForList("SELECT u.id,u.login,u.active,u.version,m.active AS membership_active,m.version AS membership_version FROM app_users u JOIN workspace_memberships m ON m.user_id=u.id WHERE m.workspace_id=? ORDER BY u.login",ws); }
    public List<Map<String,Object>> roles(long ws) { access.requireWorkspace(ws,false,"SYSTEM_ADMIN"); return db.queryForList("SELECT user_id,role_code,active,version FROM user_roles WHERE workspace_id=? ORDER BY user_id,role_code",ws); }
    public List<Map<String,Object>> entityGrants(long ws) { access.requireWorkspace(ws,false,"SYSTEM_ADMIN"); return db.queryForList("SELECT user_id,entity_id,active,version FROM entity_memberships WHERE workspace_id=? ORDER BY user_id,entity_id",ws); }
    public List<Map<String,Object>> allEntities(long ws) { access.requireWorkspace(ws,false,"SYSTEM_ADMIN"); return db.queryForList("SELECT id,code,name,base_currency,active,version FROM legal_entities WHERE workspace_id=? ORDER BY code",ws); }
    public Map<String,Object> workspaceDetails(long ws) { access.requireWorkspace(ws,false,"SYSTEM_ADMIN"); return db.queryForMap("SELECT code,name,business_date,version FROM scenario_workspaces WHERE id=?",ws); }
    @Transactional public void updateWorkspace(long ws,long version,String code,String name,LocalDate date) {
        var s=admin(ws); code=required(code, 80, "Workspace code", "code"); name=required(name, 200, "Workspace name", "name"); if(date==null) throw new DomainException(400, "Business date is required", "businessDate");
        try { changed(db.update("UPDATE scenario_workspaces SET code=?,name=?,business_date=?,version=version+1 WHERE id=? AND version=?",code,name,date,ws,version)); event(s,null,"UPDATE","workspace",ws,version+1); }
        catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Workspace code already exists"); }
    }
    @Transactional public long createWorkspace(long source,String code,String name,LocalDate date) {
        var s=admin(source); code=required(code, 80, "Workspace code", "code"); name=required(name, 200, "Workspace name", "name"); if(date==null) throw new DomainException(400, "Business date is required", "businessDate");
        try { long id=db.queryForObject("INSERT INTO scenario_workspaces(code,name,business_date,created_by) VALUES (?,?,?,?) RETURNING id",Long.class,code,name,date,s.actor().id());
            db.update("INSERT INTO workspace_memberships(workspace_id,user_id) VALUES (?,?)",id,s.actor().id()); db.update("INSERT INTO user_roles(workspace_id,user_id,role_code) VALUES (?,?,'SYSTEM_ADMIN')",id,s.actor().id());
            audit.record(s.actor(),id,null,"CREATE","workspace",Long.toString(id),1,"SUCCESS",""); return id;
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Workspace code already exists"); }
    }
    @Transactional public void createUser(long ws,String login,String plain) {
        var s=admin(ws); String key=PersistentUsers.canonical(login); if(key.isBlank()||key.length()>120) throw new DomainException(400, "Login is invalid", "login"); String hash=password(plain);
        try { long id=db.queryForObject("INSERT INTO app_users(login,password_hash) VALUES (?,?) RETURNING id",Long.class,key,hash); db.update("INSERT INTO workspace_memberships(workspace_id,user_id) VALUES (?,?)",ws,id); event(s,null,"CREATE","user",id,1); }
        catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Login already exists"); }
    }
    @Transactional public void userState(long ws,long user,long version,boolean active) { var s=admin(ws); scopedUser(ws,user); authorizeGlobalAccountChange(s,user); if(!active) preserveAdministrator(user,null); changed(db.update("UPDATE app_users SET active=?,security_version=security_version+1,version=version+1 WHERE id=? AND version=?",active,user,version)); stateEvent(s,null,"UPDATE_ACTIVE","user",user,version+1,active); }
    @Transactional public void resetPassword(long ws,long user,long version,String plain) { var s=admin(ws); scopedUser(ws,user); authorizeGlobalAccountChange(s,user); String hash=password(plain); changed(db.update("UPDATE app_users SET password_hash=?,security_version=security_version+1,version=version+1 WHERE id=? AND version=?",hash,user,version)); event(s,null,"RESET_CREDENTIAL","user",user,version+1); }
    @Transactional public void membership(long ws,long user,long version,boolean active) { var s=admin(ws); scopedUser(ws,user); if(!active) preserveAdministrator(user,ws); changed(db.update("UPDATE workspace_memberships SET active=?,version=version+1 WHERE workspace_id=? AND user_id=? AND version=?",active,ws,user,version)); stateEvent(s,null,"UPDATE_MEMBERSHIP","user",user,version+1,active); }
    @Transactional public void role(long ws,long user,String role,long version,boolean active) { var s=admin(ws); scopedUser(ws,user); if(role == null || !ROLES.contains(role)) throw new DomainException(400, "Role is invalid", "role");
        if(!active && "SYSTEM_ADMIN".equals(role)) preserveAdministrator(user,ws);
        if(version==0) { try { db.update("INSERT INTO user_roles(workspace_id,user_id,role_code,active) VALUES (?,?,?,?)",ws,user,role,active); } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Grant changed; reload and try again"); } }
        else changed(db.update("UPDATE user_roles SET active=?,version=version+1 WHERE workspace_id=? AND user_id=? AND role_code=? AND version=?",active,ws,user,role,version));
        audit.record(s.actor(),ws,null,"UPDATE_ROLE","user",Long.toString(user),version+1,"SUCCESS","{\"role\":\""+role+"\",\"active\":"+active+"}");
    }
    @Transactional public void entityGrant(long ws,long entity,long user,long version,boolean active) { var s=admin(ws); scopedUser(ws,user); if(db.queryForObject("SELECT count(*) FROM legal_entities WHERE workspace_id=? AND id=?",Integer.class,ws,entity)==0) throw new DomainException(404,"Entity not found");
        if(version==0) { try { db.update("INSERT INTO entity_memberships(workspace_id,entity_id,user_id,active) VALUES (?,?,?,?)",ws,entity,user,active); } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Grant changed; reload and try again"); } }
        else changed(db.update("UPDATE entity_memberships SET active=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND user_id=? AND version=?",active,ws,entity,user,version));
        stateEvent(s,entity,"UPDATE_ENTITY_MEMBERSHIP","user",user,version+1,active);
    }
    @Transactional public void entity(long ws,Long id,long version,String code,String name,String currency,boolean active) { var s=admin(ws); code=required(code, 80, "Entity code", "code"); name=required(name, 200, "Entity name", "name"); if(currency == null || !Set.of("EUR","USD").contains(currency)) throw new DomainException(400, "Currency is invalid", "currency"); if(id != null && db.queryForObject("SELECT count(*) FROM legal_entities WHERE workspace_id=? AND id=?",Integer.class,ws,id)==0) throw new DomainException(404,"Entity not found");
        try { if(id==null) { long created=db.queryForObject("INSERT INTO legal_entities(workspace_id,code,name,base_currency,active) VALUES (?,?,?,?,?) RETURNING id",Long.class,ws,code,name,currency,active); stateEvent(s,created,"CREATE","entity",created,1,active); }
            else { changed(db.update("UPDATE legal_entities SET code=?,name=?,active=?,version=version+1 WHERE workspace_id=? AND id=? AND version=? AND base_currency=?",code,name,active,ws,id,version,currency)); stateEvent(s,id,"UPDATE","entity",id,version+1,active); }
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Entity code already exists"); }
    }
    @Transactional public void invite(long ws,String login) { var s=admin(ws); String key=PersistentUsers.canonical(login);
        var ids=db.query("SELECT DISTINCT u.id FROM app_users u JOIN workspace_memberships target ON target.user_id=u.id JOIN workspace_memberships mine ON mine.workspace_id=target.workspace_id AND mine.user_id=? AND mine.active WHERE u.login=? AND target.active",(r,n)->r.getLong(1),s.actor().id(),key);
        if(ids.isEmpty()) throw new DomainException(404,"User not found"); long user=ids.get(0);
        db.query("SELECT id FROM app_users WHERE id=? FOR UPDATE",(r,n)->r.getLong(1),user);
        try { db.update("INSERT INTO workspace_memberships(workspace_id,user_id) VALUES (?,?)",ws,user); } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Membership already exists"); }
        stateEvent(s,null,"CREATE_MEMBERSHIP","user",user,1,true);
    }
}
