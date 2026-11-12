package dev.labtrial.salestools.core;

import dev.labtrial.salestools.identity.AccessControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public final class AuditLog {
    private final JdbcTemplate db;
    public AuditLog(JdbcTemplate db) { this.db = db; }
    public void record(AccessControl.Actor actor, Long workspaceId, Long entityId, String action,
                       String targetType, String targetId, long revision, String outcome, String detail) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit must commit with its operation.");
        }
        db.update("""
            insert into audit_events(actor_user_id,workspace_id,entity_id,action,target_type,target_id,
                                     revision,outcome,detail) values (?,?,?,?,?,?,?,?,?)
            """, actor == null ? null : actor.id(), workspaceId, entityId, action, targetType,
            targetId, revision, outcome, detail == null ? "" : detail);
    }
}
