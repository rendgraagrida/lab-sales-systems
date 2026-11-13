package dev.labtrial.salestools.crm;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InquiryService {
    public record Inquiry(long id,String sourceKey,OffsetDateTime receivedAt,String subject,String need,Long customerId,Long contactId,
                          String territory,String productLine,String status,String priority,Long assigneeId,OffsetDateTime followUpDue,long version) {}
    public record Draft(String sourceKey,OffsetDateTime receivedAt,String subject,String need,Long customerId,Long customerVersion,
                        Long contactId,Long contactVersion,Long linkVersion,String territory,String productLine,String priority,OffsetDateTime followUpDue) {}
    public record Event(long version,String kind,String detail,long actorId,OffsetDateTime occurredAt,String snapshot) {}
    public record Response(long activityId,long actorId,OffsetDateTime completedAt,String kind,String outcome) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    public InquiryService(JdbcTemplate db,AccessControl access,AuditLog audit) { this.db=db; this.access=access; this.audit=audit; }
    private static Inquiry row(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Inquiry(r.getLong(1),r.getString(2),r.getObject(3,OffsetDateTime.class),r.getString(4),r.getString(5),(Long)r.getObject(6),(Long)r.getObject(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),(Long)r.getObject(12),r.getObject(13,OffsetDateTime.class),r.getLong(14));
    }
    private static final String COLUMNS="id,source_key,received_at,subject,need,customer_id,contact_id,territory,product_line,status,priority,assignee_id,follow_up_due,version";
    private Inquiry find(long ws,long entity,long id,boolean lock) {
        var rows=db.query("SELECT "+COLUMNS+" FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND id=?"+(lock?" FOR UPDATE":""),(r,n)->row(r),ws,entity,id);
        if(rows.isEmpty()) throw new DomainException(404,"Inquiry not found");
        return rows.get(0);
    }
    public Inquiry get(long ws,long entity,long id) { access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER"); return find(ws,entity,id,false); }
    public Response firstResponse(long ws,long entity,long id) {
        get(ws,entity,id);
        return db.query("SELECT id,completed_by,completed_at,kind,outcome FROM crm_activities WHERE workspace_id=? AND entity_id=? AND inquiry_id=? AND substantive_response AND completed_at IS NOT NULL AND kind IN ('CALL','EMAIL','MEETING') ORDER BY completed_at,id LIMIT 1",(r,n)->new Response(r.getLong(1),r.getLong(2),r.getObject(3,OffsetDateTime.class),r.getString(4),r.getString(5)),ws,entity,id).stream().findFirst().orElse(null);
    }
    public List<Inquiry> list(long ws,long entity,String filter,String query) {
        var scope=access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        if(filter==null || !List.of("all","my","unassigned","team","overdue").contains(filter)) throw new DomainException(400,"Invalid queue filter");
        String q=query==null?"":query.trim();
        return db.query("SELECT "+COLUMNS+" FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND (subject ILIKE ? OR source_key ILIKE ?) AND ("+
            "?='all' OR (?='my' AND assignee_id=?) OR (?='unassigned' AND assignee_id IS NULL) OR (?='team' AND assignee_id IS NOT NULL AND EXISTS (SELECT 1 FROM app_users u JOIN workspace_memberships w ON w.workspace_id=crm_inquiries.workspace_id AND w.user_id=u.id AND w.active JOIN entity_memberships e ON e.workspace_id=w.workspace_id AND e.entity_id=crm_inquiries.entity_id AND e.user_id=u.id AND e.active JOIN user_roles r ON r.workspace_id=w.workspace_id AND r.user_id=u.id AND r.active AND r.role_code IN ('INSIDE_SALES','SALES_MANAGER') WHERE u.id=crm_inquiries.assignee_id AND u.active)) OR (?='overdue' AND follow_up_due<now() AND status NOT IN ('RESOLVED','CANCELLED'))) ORDER BY received_at DESC,id DESC",
            (r,n)->row(r),ws,entity,"%"+q+"%","%"+q+"%",filter,filter,scope.actor().id(),filter,filter,filter);
    }
    public List<Event> history(long ws,long entity,long id) {
        get(ws,entity,id);
        return db.query("SELECT version,kind,detail,actor_id,occurred_at,snapshot::text FROM crm_inquiry_events WHERE workspace_id=? AND entity_id=? AND inquiry_id=? ORDER BY version",(r,n)->new Event(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getObject(5,OffsetDateTime.class),r.getString(6)),ws,entity,id);
    }
    static String text(String value,String field,int max,boolean required) {
        String v=value==null?"":value.trim(); if(v.length()>max || (required && v.isEmpty())) throw new DomainException(400,"Invalid "+field,field); return v;
    }
    private void references(long ws,long entity,long actor,Draft d) {
        if(d.customerId()!=null) {
            var rows=db.query("SELECT c.version,c.active FROM customers c WHERE c.workspace_id=? AND c.id=? AND (EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.entity_id=? AND a.customer_id=c.id AND a.active) OR (c.created_by=? AND NOT EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.customer_id=c.id))) FOR UPDATE OF c",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,d.customerId(),entity,actor);
            if(rows.isEmpty()) throw new DomainException(404,"Customer not found");
            if(d.customerVersion()==null || rows.get(0)[0]!=d.customerVersion()) throw new DomainException(409,"Customer has changed");
            if(rows.get(0)[1]==0) throw new DomainException(400,"Customer is inactive","customerId");
        } else if(d.customerVersion()!=null || d.linkVersion()!=null) throw new DomainException(400,"Unexpected customer version","customerId");
        if(d.contactId()!=null) {
            var rows=db.query("SELECT c.version,c.active FROM contacts c WHERE c.workspace_id=? AND c.id=? AND (EXISTS (SELECT 1 FROM customer_contact_links l JOIN customer_entity_accounts a ON a.workspace_id=l.workspace_id AND a.customer_id=l.customer_id AND a.entity_id=? AND a.active WHERE l.workspace_id=c.workspace_id AND l.contact_id=c.id AND l.active) OR (c.created_by=? AND NOT EXISTS (SELECT 1 FROM customer_contact_links l JOIN customer_entity_accounts a ON a.workspace_id=l.workspace_id AND a.customer_id=l.customer_id WHERE l.workspace_id=c.workspace_id AND l.contact_id=c.id AND l.active))) FOR UPDATE OF c",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,d.contactId(),entity,actor);
            if(rows.isEmpty()) throw new DomainException(404,"Contact not found");
            if(d.contactVersion()==null || rows.get(0)[0]!=d.contactVersion()) throw new DomainException(409,"Contact has changed");
            if(rows.get(0)[1]==0) throw new DomainException(400,"Contact is inactive","contactId");
        } else if(d.contactVersion()!=null || d.linkVersion()!=null) throw new DomainException(400,"Unexpected contact version","contactId");
        if(d.customerId()!=null && d.contactId()!=null) {
            var links=db.query("SELECT version,active FROM customer_contact_links WHERE workspace_id=? AND customer_id=? AND contact_id=? FOR UPDATE",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,d.customerId(),d.contactId());
            if(links.isEmpty() || links.get(0)[1]==0) throw new DomainException(400,"Customer and contact are not actively associated","contactId");
            if(d.linkVersion()==null || links.get(0)[0]!=d.linkVersion()) throw new DomainException(409,"Association has changed");
        }
    }
    private void event(AccessControl.Scope scope,long id,long version,String kind,String detail) {
        db.update("INSERT INTO crm_inquiry_events(workspace_id,entity_id,inquiry_id,version,kind,detail,snapshot,actor_id) SELECT workspace_id,entity_id,id,?,?,?,to_jsonb(i),? FROM crm_inquiries i WHERE workspace_id=? AND entity_id=? AND id=?",version,kind,detail,scope.actor().id(),scope.workspaceId(),scope.entityId(),id);
        audit.record(scope.actor(),scope.workspaceId(),scope.entityId(),kind,"crm_inquiries",Long.toString(id),version,"SUCCESS",detail);
    }
    private static String fingerprint(Object... values) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(Object value:values) {
                byte[] bytes=(value==null?"":value.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array()); digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @Transactional
    public long save(long ws,long entity,Long id,Long version,Draft d) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        String source=text(d.sourceKey(),"sourceKey",200,true), subject=text(d.subject(),"subject",500,true), need=text(d.need(),"need",5000,false), territory=text(d.territory(),"territory",80,false), product=text(d.productLine(),"productLine",80,false);
        if(d.receivedAt()==null) throw new DomainException(400,"Received time is required","receivedAt");
        if(d.priority()==null || !List.of("LOW","NORMAL","HIGH","URGENT").contains(d.priority())) throw new DomainException(400,"Invalid priority","priority");
        String hash=fingerprint(d.receivedAt().toInstant(),subject,need,d.customerId(),d.customerVersion(),d.contactId(),d.contactVersion(),d.linkVersion(),territory,product,d.priority(),d.followUpDue()==null?null:d.followUpDue().toInstant());
        if(id==null && version==null) {
            var previous=db.query("SELECT id,source_hash FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND source_key=?",(r,n)->new Object[]{r.getLong(1),r.getString(2)},ws,entity,source);
            if(!previous.isEmpty()) {
                if(!hash.equals(previous.get(0)[1])) throw new DomainException(409,"Inquiry source key has different input");
                return (Long)previous.get(0)[0];
            }
        }
        Inquiry old=id==null?null:find(ws,entity,id,true);
        if(old!=null && (version==null || old.version()!=version)) throw new DomainException(409,"Inquiry has changed");
        if(old==null && version!=null) throw new DomainException(400,"Unexpected version");
        if(old!=null && !old.sourceKey().equals(source)) throw new DomainException(400,"Source identity cannot change","sourceKey");
        references(ws,entity,scope.actor().id(),d);
        if(old!=null && !java.util.Objects.equals(old.customerId(),d.customerId())) {
            Boolean linked=db.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM crm_opportunities o JOIN customer_entity_accounts a
                    ON a.workspace_id=o.workspace_id AND a.entity_id=o.entity_id AND a.id=o.customer_account_id
                  WHERE o.workspace_id=? AND o.entity_id=? AND o.inquiry_id=? AND a.customer_id IS DISTINCT FROM ?)
                OR EXISTS(
                  SELECT 1 FROM crm_activities WHERE workspace_id=? AND entity_id=? AND inquiry_id=?
                    AND (?::bigint IS NOT NULL OR (customer_id IS NOT NULL AND customer_id IS DISTINCT FROM ?)))
                """,Boolean.class,ws,entity,id,d.customerId(),ws,entity,id,old.customerId(),d.customerId());
            if(Boolean.TRUE.equals(linked)) throw new DomainException(409,"Inquiry customer is linked to existing activity or opportunity");
        }
        if(old==null) {
            id=db.queryForObject("INSERT INTO crm_inquiries(workspace_id,entity_id,source_key,source_hash,received_at,subject,need,customer_id,contact_id,territory,product_line,priority,follow_up_due,created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,source,hash,d.receivedAt(),subject,need,d.customerId(),d.contactId(),territory,product,d.priority(),d.followUpDue(),scope.actor().id());
            db.update("INSERT INTO crm_inquiry_events(workspace_id,entity_id,inquiry_id,version,kind,detail,snapshot,actor_id) SELECT workspace_id,entity_id,id,1,'CREATE','Inquiry created',to_jsonb(i) || jsonb_build_object('customerVersion',?::bigint,'contactVersion',?::bigint,'linkVersion',?::bigint),? FROM crm_inquiries i WHERE workspace_id=? AND entity_id=? AND id=?",d.customerVersion(),d.contactVersion(),d.linkVersion(),scope.actor().id(),ws,entity,id);
            audit.record(scope.actor(),ws,entity,"CREATE","crm_inquiries",Long.toString(id),1,"SUCCESS","Inquiry created");
        } else {
            db.update("UPDATE crm_inquiries SET received_at=?,subject=?,need=?,customer_id=?,contact_id=?,territory=?,product_line=?,priority=?,follow_up_due=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",d.receivedAt(),subject,need,d.customerId(),d.contactId(),territory,product,d.priority(),d.followUpDue(),ws,entity,id);
            event(scope,id,version+1,"UPDATE","Inquiry edited");
        }
        return id;
    }
    boolean eligible(long ws,long entity,long user) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users u JOIN workspace_memberships w ON w.workspace_id=? AND w.user_id=u.id AND w.active JOIN entity_memberships e ON e.workspace_id=w.workspace_id AND e.entity_id=? AND e.user_id=u.id AND e.active JOIN user_roles r ON r.workspace_id=w.workspace_id AND r.user_id=u.id AND r.active AND r.role_code IN ('INSIDE_SALES','SALES_MANAGER') WHERE u.id=? AND u.active)",Boolean.class,ws,entity,user));
    }
    @Transactional
    public void assign(long ws,long entity,long id,long version,Long assignee,String reason,boolean claim) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var current=find(ws,entity,id,true); if(current.version()!=version) throw new DomainException(409,"Inquiry has changed");
        if(List.of("RESOLVED","CANCELLED").contains(current.status())) throw new DomainException(409,"Inquiry is closed");
        if(claim) { if(current.assigneeId()!=null) throw new DomainException(409,"Inquiry is already assigned"); assignee=scope.actor().id(); }
        else reason=text(reason,"reason",2000,true);
        if(assignee!=null && !eligible(ws,entity,assignee)) throw new DomainException(400,"Assignee is unavailable","assigneeId");
        db.update("UPDATE crm_inquiries SET assignee_id=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",assignee,ws,entity,id);
        event(scope,id,version+1,claim?"CLAIM":"REASSIGN",claim?"Claimed":reason);
    }
    @Transactional
    public void transition(long ws,long entity,long id,long version,String status,OffsetDateTime due,String reason) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER"); var current=find(ws,entity,id,true);
        if(current.version()!=version) throw new DomainException(409,"Inquiry has changed");
        if(status==null || !List.of("NEW","IN_PROGRESS","WAITING","RESOLVED","CANCELLED").contains(status)) throw new DomainException(400,"Invalid status","status");
        reason=text(reason,"reason",2000,true);
        db.update("UPDATE crm_inquiries SET status=?,follow_up_due=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",status,due,ws,entity,id); event(scope,id,version+1,"STATE",reason);
    }
}
