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
public class ActivityService {
    public record Activity(long id,Long inquiryId,Long opportunityId,Long customerId,String kind,String subject,long assigneeId,
                           OffsetDateTime dueAt,OffsetDateTime completedAt,String outcome,boolean substantiveResponse,long version) {}
    public record Draft(Long inquiryId,Long inquiryVersion,Long opportunityId,Long opportunityVersion,Long customerId,Long customerVersion,
                        String kind,String subject,long assigneeId,OffsetDateTime dueAt,String intent) {}
    public record Event(long version,String action,String detail,Long actorId,OffsetDateTime occurredAt) {}
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final InquiryService inquiries; private final CrmReceipts receipts;
    public ActivityService(JdbcTemplate db,AccessControl access,AuditLog audit,InquiryService inquiries,CrmReceipts receipts) { this.db=db; this.access=access; this.audit=audit; this.inquiries=inquiries; this.receipts=receipts; }
    public List<Activity> list(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,inquiry_id,opportunity_id,customer_id,kind,subject,assignee_id,due_at,completed_at,outcome,substantive_response,version FROM crm_activities WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",
          (r,n)->new Activity(r.getLong(1),(Long)r.getObject(2),(Long)r.getObject(3),(Long)r.getObject(4),r.getString(5),r.getString(6),r.getLong(7),r.getObject(8,OffsetDateTime.class),r.getObject(9,OffsetDateTime.class),r.getString(10),r.getBoolean(11),r.getLong(12)),ws,entity);
    }
    public List<Event> history(long ws,long entity,long id) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT revision,action,detail,actor_user_id,occurred_at FROM audit_events WHERE workspace_id=? AND entity_id=? AND target_type='crm_activities' AND target_id=? ORDER BY id",(r,n)->new Event(r.getLong(1),r.getString(2),r.getString(3),(Long)r.getObject(4),r.getObject(5,OffsetDateTime.class)),ws,entity,Long.toString(id));
    }
    @Transactional
    public long create(long ws,long entity,Draft d) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        if(d.kind()==null || !List.of("TASK","NOTE","CALL","EMAIL","MEETING").contains(d.kind())) throw new DomainException(400,"Invalid activity type","kind");
        String subject=InquiryService.text(d.subject(),"subject",500,true);
        var key=receipts.key(d.intent(),d.inquiryId(),d.inquiryVersion(),d.opportunityId(),d.opportunityVersion(),d.customerId(),d.customerVersion(),d.kind(),subject,d.assigneeId(),d.dueAt()==null?null:d.dueAt().toInstant());
        Long previous=receipts.previous(scope,"ACTIVITY_CREATE",key); if(previous!=null) return previous;
        if(d.inquiryId()==null && d.opportunityId()==null && d.customerId()==null) throw new DomainException(400,"Select an inquiry, opportunity or customer");
        Long inquiryCustomer=null, opportunityCustomer=null;
        if(d.inquiryId()!=null) {
            var rows=db.query("SELECT version,customer_id FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new Long[]{r.getLong(1),(Long)r.getObject(2)},ws,entity,d.inquiryId());
            if(rows.isEmpty()) throw new DomainException(404,"Inquiry not found");
            if(d.inquiryVersion()==null || !rows.get(0)[0].equals(d.inquiryVersion())) throw new DomainException(409,"Inquiry has changed"); inquiryCustomer=rows.get(0)[1];
        } else if(d.inquiryVersion()!=null) throw new DomainException(400,"Unexpected inquiry version");
        if(d.opportunityId()!=null) {
            var rows=db.query("SELECT o.version,a.customer_id,o.inquiry_id FROM crm_opportunities o JOIN customer_entity_accounts a ON a.workspace_id=o.workspace_id AND a.entity_id=o.entity_id AND a.id=o.customer_account_id WHERE o.workspace_id=? AND o.entity_id=? AND o.id=? FOR UPDATE OF o",(r,n)->new Long[]{r.getLong(1),r.getLong(2),(Long)r.getObject(3)},ws,entity,d.opportunityId());
            if(rows.isEmpty()) throw new DomainException(404,"Opportunity not found");
            if(d.opportunityVersion()==null || !rows.get(0)[0].equals(d.opportunityVersion())) throw new DomainException(409,"Opportunity has changed");
            opportunityCustomer=rows.get(0)[1];
            if(d.inquiryId()!=null && !d.inquiryId().equals(rows.get(0)[2])) throw new DomainException(400,"Opportunity and inquiry do not match");
        } else if(d.opportunityVersion()!=null) throw new DomainException(400,"Unexpected opportunity version");
        if(d.customerId()!=null) {
            var rows=db.query("SELECT c.version,c.active FROM customers c WHERE c.workspace_id=? AND c.id=? AND (EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.entity_id=? AND a.customer_id=c.id AND a.active) OR (c.created_by=? AND NOT EXISTS (SELECT 1 FROM customer_entity_accounts a WHERE a.workspace_id=c.workspace_id AND a.customer_id=c.id))) FOR UPDATE OF c",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,d.customerId(),entity,scope.actor().id());
            if(rows.isEmpty()) throw new DomainException(404,"Customer not found");
            if(d.customerVersion()==null || rows.get(0)[0]!=d.customerVersion()) throw new DomainException(409,"Customer has changed");
            if(rows.get(0)[1]==0) throw new DomainException(400,"Customer is inactive");
            if((inquiryCustomer!=null && !inquiryCustomer.equals(d.customerId())) || (opportunityCustomer!=null && !opportunityCustomer.equals(d.customerId()))) throw new DomainException(400,"Activity links have different customers");
        } else if(d.customerVersion()!=null) throw new DomainException(400,"Unexpected customer version");
        if(!inquiries.eligible(ws,entity,d.assigneeId())) throw new DomainException(400,"Assignee is unavailable","assigneeId");
        long id=db.queryForObject("INSERT INTO crm_activities(workspace_id,entity_id,inquiry_id,opportunity_id,customer_id,kind,subject,assignee_id,due_at,created_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,d.inquiryId(),d.opportunityId(),d.customerId(),d.kind(),subject,d.assigneeId(),d.dueAt(),scope.actor().id());
        receipts.record(scope,"ACTIVITY_CREATE",key,id);
        audit.record(scope.actor(),ws,entity,"CREATE","crm_activities",Long.toString(id),1,"SUCCESS","Simulated activity"); return id;
    }
    @Transactional
    public void edit(long ws,long entity,long id,long version,String subject,long assignee,OffsetDateTime due) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.query("SELECT version,completed_at FROM crm_activities WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new Object[]{r.getLong(1),r.getObject(2)},ws,entity,id);
        if(rows.isEmpty()) throw new DomainException(404,"Activity not found");
        if((Long)rows.get(0)[0]!=version) throw new DomainException(409,"Activity has changed");
        if(rows.get(0)[1]!=null) throw new DomainException(409,"Activity is already complete");
        subject=InquiryService.text(subject,"subject",500,true);
        if(!inquiries.eligible(ws,entity,assignee)) throw new DomainException(400,"Assignee is unavailable","assigneeId");
        db.update("UPDATE crm_activities SET subject=?,assignee_id=?,due_at=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",subject,assignee,due,ws,entity,id);
        audit.record(scope.actor(),ws,entity,"UPDATE","crm_activities",Long.toString(id),version+1,"SUCCESS","Simulated activity edited");
    }
    @Transactional
    public void complete(long ws,long entity,long id,long version,String outcome,boolean response) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.query("SELECT version,completed_at,kind FROM crm_activities WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new Object[]{r.getLong(1),r.getObject(2),r.getString(3)},ws,entity,id);
        if(rows.isEmpty()) throw new DomainException(404,"Activity not found");
        if((Long)rows.get(0)[0]!=version) throw new DomainException(409,"Activity has changed");
        if(rows.get(0)[1]!=null) throw new DomainException(409,"Activity is already complete");
        if(response && !List.of("CALL","EMAIL","MEETING").contains(rows.get(0)[2])) throw new DomainException(400,"Only a call, email or meeting can record a simulated response","response");
        outcome=InquiryService.text(outcome,"outcome",5000,true);
        db.update("UPDATE crm_activities SET completed_at=now(),completed_by=?,outcome=?,substantive_response=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",scope.actor().id(),outcome,response,ws,entity,id);
        audit.record(scope.actor(),ws,entity,"COMPLETE","crm_activities",Long.toString(id),version+1,"SUCCESS","Simulated activity completed");
    }
}
