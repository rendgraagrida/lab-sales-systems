package dev.labtrial.salestools.identity;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapService {
    public record BootstrapResult(long userId, long workspaceId, boolean replayed) {}
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final AuditLog audit;
    public BootstrapService(JdbcTemplate db, PasswordEncoder encoder, AuditLog audit) { this.db=db; this.encoder=encoder; this.audit=audit; }
    @Transactional
    public BootstrapResult bootstrap(String login, char[] password, String workspaceCode, String workspaceName, LocalDate businessDate) {
        String key = PersistentUsers.canonical(login);
        String code = IdentityService.required(workspaceCode, 80, "Workspace code", "code");
        String name = IdentityService.required(workspaceName, 200, "Workspace name", "name");
        if (key.isBlank() || key.length()>120 || businessDate == null || password == null || password.length < 16 || new String(password).isBlank()) throw new DomainException(400,"Invalid bootstrap input");
        String hash = digest(key,code,name,businessDate.toString());
        db.queryForObject("SELECT pg_advisory_xact_lock(1279345485, 1)", Object.class);
        var receipts = db.query("SELECT user_id,workspace_id,payload_hash FROM setup_receipts WHERE receipt_key='INITIAL_ADMIN'", (r,n) -> new Object[]{r.getLong(1),r.getLong(2),r.getString(3)});
        if (!receipts.isEmpty()) {
            Object[] r=receipts.get(0);
            if (!hash.equals(r[2])) throw new DomainException(409,"Bootstrap already completed with different intent");
            return new BootstrapResult((long)r[0],(long)r[1],true);
        }
        try {
            long user = db.queryForObject("INSERT INTO app_users(login,password_hash) VALUES (?,?) RETURNING id",Long.class,key,encoder.encode(new String(password)));
            long ws = db.queryForObject("INSERT INTO scenario_workspaces(code,name,business_date,created_by) VALUES (?,?,?,?) RETURNING id",Long.class,code,name,businessDate,user);
            db.update("INSERT INTO workspace_memberships(workspace_id,user_id) VALUES (?,?)",ws,user);
            db.update("INSERT INTO user_roles(workspace_id,user_id,role_code) VALUES (?,?,'SYSTEM_ADMIN')",ws,user);
            db.update("INSERT INTO setup_receipts(receipt_key,user_id,workspace_id,payload_hash) VALUES ('INITIAL_ADMIN',?,?,?)",user,ws,hash);
            audit.record(new AccessControl.Actor(user,key,1),ws,null,"BOOTSTRAP","workspace",Long.toString(ws),1,"SUCCESS","initial administrator");
            return new BootstrapResult(user,ws,false);
        } catch (DataIntegrityViolationException ex) { throw new DomainException(409,"Bootstrap conflicts with existing data"); }
    }
    private static String digest(String... values) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            for (String value:values) { byte[] bytes=value.getBytes(StandardCharsets.UTF_8); md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); md.update(bytes); }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
