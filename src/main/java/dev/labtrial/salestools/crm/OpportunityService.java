package dev.labtrial.salestools.crm;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.order.HandoverService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpportunityService {
    public record Opportunity(long id, long customerAccountId, Long inquiryId, String title, String pipeline,
                              String stage, long ownerId, long version) {}
    public record Qualification(long customerAccountId, long accountVersion, Long inquiryId, Long inquiryVersion,
                                String title, String pipeline, long ownerId, String reason,String intent) {}
    public record Event(long opportunityId,long version,String stage,String reason,long actorId,java.time.OffsetDateTime occurredAt) {}
    public record Lifecycle(long customerId,String lifecycle,String reason,long actorId,java.time.OffsetDateTime occurredAt) {}
    public record Request(long id,long opportunityId,String status,String reasons,String intent) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final HandoverService handover;
    private final CrmReceipts receipts;

    public OpportunityService(JdbcTemplate db, AccessControl access, AuditLog audit, HandoverService handover,CrmReceipts receipts) {
        this.db = db; this.access = access; this.audit = audit; this.handover = handover; this.receipts=receipts;
    }

    public List<Opportunity> list(long ws, long entity) {
        access.requireContext(ws, entity, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.query("SELECT id,customer_account_id,inquiry_id,title,pipeline,stage,owner_id,version FROM crm_opportunities WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",
            (r,n) -> new Opportunity(r.getLong(1),r.getLong(2),(Long)r.getObject(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7),r.getLong(8)),ws,entity);
    }
    public List<Event> history(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT opportunity_id,version,stage,reason,actor_id,occurred_at FROM crm_opportunity_events WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",(r,n)->new Event(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getLong(5),r.getObject(6,java.time.OffsetDateTime.class)),ws,entity);
    }
    public List<Lifecycle> lifecycles(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT customer_id,lifecycle,reason,actor_id,occurred_at FROM crm_lifecycle_events WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",(r,n)->new Lifecycle(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getObject(5,java.time.OffsetDateTime.class)),ws,entity);
    }
    public List<Request> requests(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,opportunity_id,status,blocked_reasons::text,intent FROM sales_handover_requests WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",(r,n)->new Request(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5)),ws,entity);
    }
    private Opportunity find(long ws, long entity, long id, boolean lock) {
        var rows = db.query("SELECT id,customer_account_id,inquiry_id,title,pipeline,stage,owner_id,version FROM crm_opportunities WHERE workspace_id=? AND entity_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
            (r,n) -> new Opportunity(r.getLong(1),r.getLong(2),(Long)r.getObject(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7),r.getLong(8)),ws,entity,id);
        if (rows.isEmpty()) throw new DomainException(404,"Opportunity not found");
        return rows.get(0);
    }
    public Opportunity get(long ws, long entity, long id) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return find(ws,entity,id,false);
    }
    private void requireOwner(long ws,long entity,long user) {
        Boolean eligible = db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM app_users u
              JOIN workspace_memberships w ON w.user_id=u.id AND w.workspace_id=? AND w.active
              JOIN entity_memberships e ON e.workspace_id=w.workspace_id AND e.user_id=u.id AND e.entity_id=? AND e.active
              JOIN user_roles r ON r.workspace_id=w.workspace_id AND r.user_id=u.id AND r.active
                AND r.role_code IN ('INSIDE_SALES','SALES_MANAGER')
              WHERE u.id=? AND u.active)
            """,Boolean.class,ws,entity,user);
        if (!Boolean.TRUE.equals(eligible)) throw new DomainException(400,"Owner is unavailable","ownerId");
    }
    private static String required(String value,String field,int max) {
        if (value==null || value.isBlank() || value.trim().length()>max) throw new DomainException(400,"A valid "+field+" is required",field);
        return value.trim();
    }
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public long createRenewal(AccessControl.Scope scope,long renewalIntentId,long ownerId,long ownerSecurityVersion) {
        long ws=scope.workspaceId(), entity=scope.entityId();
        var authorized=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        if(authorized.actor().id()!=scope.actor().id()) throw new DomainException(403,"Actor changed");
        requireOwner(ws,entity,ownerId);
        Boolean current=db.queryForObject("select exists(select 1 from app_users where id=? and active and security_version=?)",Boolean.class,ownerId,ownerSecurityVersion);
        if(!Boolean.TRUE.equals(current)) throw new DomainException(409,"Owner security reference changed");
        var sources=db.queryForList("""
            select i.customer_account_id,i.opportunity_id,i.status,i.target_start,t.coverage_end,
                   t.currency,p.sku,a.serial
            from renewal_intents i
            join service_contract_terms t on t.workspace_id=i.workspace_id and t.entity_id=i.entity_id and t.id=i.predecessor_term_id
            join installed_instruments a on a.workspace_id=t.workspace_id and a.entity_id=t.entity_id and a.id=t.asset_id
            join products p on p.workspace_id=t.workspace_id and p.id=t.plan_product_id
            join service_contract_assets link on link.workspace_id=t.workspace_id and link.entity_id=t.entity_id and link.id=t.contract_asset_id and link.asset_id=t.asset_id and link.contract_id=i.contract_id
            join service_contracts c on c.workspace_id=i.workspace_id and c.entity_id=i.entity_id and c.id=i.contract_id
            join customer_entity_accounts ca on ca.workspace_id=i.workspace_id and ca.entity_id=i.entity_id and ca.id=i.customer_account_id and ca.active
            join scenario_workspaces clock on clock.id=i.workspace_id
            where i.workspace_id=? and i.entity_id=? and i.id=? and t.status='VALIDATED'
              and c.customer_account_id=i.customer_account_id and a.customer_account_id=i.customer_account_id
              and i.target_start=t.coverage_end and i.target_start>clock.business_date
            for update of i,t
            """,ws,entity,renewalIntentId);
        if(sources.size()!=1) throw new DomainException(409,"Renewal source is unavailable or changed");
        var source=sources.get(0);
        if(source.get("opportunity_id")!=null) return ((Number)source.get("opportunity_id")).longValue();
        if(!"OPEN".equals(source.get("status"))) throw new DomainException(409,"Renewal intent is not open");
        String title="Renewal "+source.get("sku")+" / "+source.get("serial")+" / "+source.get("target_start");
        String reason="Renewal intent "+renewalIntentId+"; currency "+source.get("currency");
        long id=db.queryForObject("INSERT INTO crm_opportunities(workspace_id,entity_id,customer_account_id,title,pipeline,stage,owner_id,created_by) VALUES (?,?,?,?,'SERVICE_RENEWAL','QUALIFIED',?,?) RETURNING id",Long.class,ws,entity,source.get("customer_account_id"),title,ownerId,scope.actor().id());
        db.update("INSERT INTO crm_opportunity_events(workspace_id,entity_id,opportunity_id,version,stage,reason,actor_id) VALUES (?,?,?,1,'QUALIFIED',?,?)",ws,entity,id,reason,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"QUALIFY","crm_opportunities",Long.toString(id),1,"SUCCESS",reason);
        return id;
    }
    @Transactional
    public long qualify(long ws,long entity,Qualification command) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        String title=required(command.title(),"title",500), reason=required(command.reason(),"reason",2000);
        if (command.pipeline()==null || !List.of("EQUIPMENT_SALES","SUPPLY_REORDER").contains(command.pipeline())) throw new DomainException(400,"Invalid pipeline","pipeline");
        var key=receipts.key(command.intent(),command.customerAccountId(),command.accountVersion(),command.inquiryId(),command.inquiryVersion(),title,command.pipeline(),command.ownerId(),reason);
        Long previous=receipts.previous(scope,"OPPORTUNITY_QUALIFY",key); if(previous!=null) return previous;
        var accounts=db.query("SELECT customer_id,version,active FROM customer_entity_accounts WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",
            (r,n)->new long[]{r.getLong(1),r.getLong(2),r.getBoolean(3)?1:0},ws,entity,command.customerAccountId());
        if(accounts.isEmpty()) throw new DomainException(404,"Customer account not found");
        var account=accounts.get(0);
        if(account[1]!=command.accountVersion()) throw new DomainException(409,"Customer account has changed");
        if(account[2]==0) throw new DomainException(400,"Customer account is inactive","customerAccountId");
        if(command.inquiryId()!=null) {
            var inquiries=db.query("SELECT customer_id,version,status FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",
                (r,n)->new Object[]{r.getObject(1),r.getLong(2),r.getString(3)},ws,entity,command.inquiryId());
            if(inquiries.isEmpty()) throw new DomainException(404,"Inquiry not found");
            var inquiry=inquiries.get(0);
            if(command.inquiryVersion()==null || (Long)inquiry[1]!=command.inquiryVersion().longValue()) throw new DomainException(409,"Inquiry has changed");
            if(inquiry[0]!=null && ((Number)inquiry[0]).longValue()!=account[0]) throw new DomainException(400,"Inquiry belongs to a different customer","inquiryId");
            if(List.of("RESOLVED","CANCELLED").contains((String)inquiry[2])) throw new DomainException(409,"Inquiry is closed");
        } else if(command.inquiryVersion()!=null) throw new DomainException(400,"Unexpected inquiry version","inquiryId");
        requireOwner(ws,entity,command.ownerId());
        long id=db.queryForObject("INSERT INTO crm_opportunities(workspace_id,entity_id,customer_account_id,inquiry_id,title,pipeline,stage,owner_id,created_by) VALUES (?,?,?,?,?,?,'QUALIFIED',?,?) RETURNING id",Long.class,ws,entity,command.customerAccountId(),command.inquiryId(),title,command.pipeline(),command.ownerId(),scope.actor().id());
        db.update("INSERT INTO crm_opportunity_events(workspace_id,entity_id,opportunity_id,version,stage,reason,actor_id) VALUES (?,?,?,1,'QUALIFIED',?,?)",ws,entity,id,reason,scope.actor().id());
        receipts.record(scope,"OPPORTUNITY_QUALIFY",key,id);
        audit.record(scope.actor(),ws,entity,"QUALIFY","crm_opportunities",Long.toString(id),1,"SUCCESS",reason);
        return id;
    }
    @Transactional
    public void lifecycle(long ws,long entity,long customer,long version,String lifecycle,String reason) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        if(lifecycle==null || !List.of("LEAD","MQL","SQL","CUSTOMER","INACTIVE").contains(lifecycle)) throw new DomainException(400,"Invalid lifecycle","lifecycle");
        reason=required(reason,"reason",2000);
        var rows=db.query("SELECT version,active FROM customers WHERE workspace_id=? AND id=? FOR UPDATE",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,customer);
        if(rows.isEmpty()) throw new DomainException(404,"Customer not found");
        if(rows.get(0)[0]!=version) throw new DomainException(409,"Customer has changed");
        if(rows.get(0)[1]==0) throw new DomainException(400,"Customer is inactive");
        var accounts=db.query("SELECT id FROM customer_entity_accounts WHERE workspace_id=? AND entity_id=? AND customer_id=? AND active",(r,n)->r.getLong(1),ws,entity,customer);
        if(accounts.isEmpty()) throw new DomainException(404,"Customer account not found");
        long id=db.queryForObject("INSERT INTO crm_lifecycle_events(workspace_id,entity_id,customer_id,lifecycle,reason,actor_id) VALUES (?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,customer,lifecycle,reason,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"LIFECYCLE","crm_lifecycle_events",Long.toString(id),1,"SUCCESS",reason);
    }
    @Transactional
    public HandoverService.HandoverResult changeStage(long ws,long entity,long id,long version,String stage,String reason,String intent) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        if("WON".equals(stage)) {
            intent=required(intent,"intent",200); reason=required(reason,"reason",2000);
            var prior=db.query("SELECT r.opportunity_id,e.version,e.reason FROM sales_handover_requests r JOIN crm_opportunity_events e ON e.workspace_id=r.workspace_id AND e.entity_id=r.entity_id AND e.opportunity_id=r.opportunity_id AND e.stage='WON' WHERE r.workspace_id=? AND r.entity_id=? AND r.intent=?",(r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getString(3)},ws,entity,intent);
            if(!prior.isEmpty()) {
                var saved=prior.get(0);
                if((Long)saved[0]!=id || (Long)saved[1]!=version+1 || !saved[2].equals(reason)) throw new DomainException(409,"Handover intent has different input");
                return handover.requestOpportunity(ws,entity,id,intent);
            }
        }
        var current=find(ws,entity,id,true);
        if(current.version()!=version) throw new DomainException(409,"Opportunity has changed");
        if(List.of("WON","LOST").contains(current.stage())) throw new DomainException(409,"Opportunity is closed");
        if(stage==null || !List.of("QUALIFIED","PROPOSAL","NEGOTIATION","WON","LOST").contains(stage) || current.stage().equals(stage)) throw new DomainException(400,"Invalid stage","stage");
        reason=required(reason,"reason",2000);
        if("WON".equals(stage)) intent=required(intent,"intent",200);
        db.update("UPDATE crm_opportunities SET stage=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",stage,ws,entity,id);
        db.update("INSERT INTO crm_opportunity_events(workspace_id,entity_id,opportunity_id,version,stage,reason,actor_id) VALUES (?,?,?,?,?,?,?)",ws,entity,id,version+1,stage,reason,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"STAGE","crm_opportunities",Long.toString(id),version+1,"SUCCESS",reason);
        return "WON".equals(stage) ? handover.requestOpportunity(ws,entity,id,intent) : null;
    }
}
