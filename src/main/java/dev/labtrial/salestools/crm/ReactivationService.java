package dev.labtrial.salestools.crm;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReactivationService {
    public record Rule(long id,String key,long revision,int dormantDays) {}
    public record Case(long id,long customerId,long shipmentLineId,LocalDate purchaseDate,String status,Long activityId,long version) {}
    public record Evaluation(long customerId,Long shipmentLineId,LocalDate purchaseDate,Long caseId,String outcome,String reason) {}
    public record EvaluationHistory(long id,long customerId,long ruleId,LocalDate asOf,Long shipmentLineId,LocalDate purchaseDate,Long caseId,String outcome,String reason) {}
    public record CaseEvent(long version,String kind,String reason,long actorId,java.time.OffsetDateTime occurredAt) {}
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final CrmReceipts receipts;
    public ReactivationService(JdbcTemplate db,AccessControl access,AuditLog audit,CrmReceipts receipts) {this.db=db;this.access=access;this.audit=audit;this.receipts=receipts;}
    public List<Rule> rules(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,rule_key,revision,dormant_days FROM crm_reactivation_rules WHERE workspace_id=? AND entity_id=? ORDER BY rule_key,revision DESC",(r,n)->new Rule(r.getLong(1),r.getString(2),r.getLong(3),r.getInt(4)),ws,entity);
    }
    public List<Case> cases(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,customer_id,purchase_shipment_line_id,purchase_date,status,follow_up_activity_id,version FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",(r,n)->new Case(r.getLong(1),r.getLong(2),r.getLong(3),r.getObject(4,LocalDate.class),r.getString(5),(Long)r.getObject(6),r.getLong(7)),ws,entity);
    }
    public List<EvaluationHistory> history(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,customer_id,rule_id,as_of,purchase_shipment_line_id,purchase_date,case_id,outcome,reason FROM crm_reactivation_evaluations WHERE workspace_id=? AND entity_id=? ORDER BY id DESC",(r,n)->new EvaluationHistory(r.getLong(1),r.getLong(2),r.getLong(3),r.getObject(4,LocalDate.class),(Long)r.getObject(5),r.getObject(6,LocalDate.class),(Long)r.getObject(7),r.getString(8),r.getString(9)),ws,entity);
    }
    public List<CaseEvent> caseHistory(long ws,long entity,long id) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT version,kind,reason,actor_id,occurred_at FROM crm_reactivation_events WHERE workspace_id=? AND entity_id=? AND case_id=? ORDER BY version",(r,n)->new CaseEvent(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getObject(5,java.time.OffsetDateTime.class)),ws,entity,id);
    }
    @Transactional
    public long createRule(long ws,long entity,String key,int days,String intent) {
        var scope=access.requireContext(ws,entity,true,"SALES_MANAGER"); key=InquiryService.text(key,"key",100,true);
        if(days<1 || days>36500) throw new DomainException(400,"Invalid dormant threshold","days");
        var receipt=receipts.key(intent,key,days); Long previous=receipts.previous(scope,"REACTIVATION_RULE",receipt); if(previous!=null) return previous;
        long revision=db.queryForObject("SELECT coalesce(max(revision),0)+1 FROM crm_reactivation_rules WHERE workspace_id=? AND entity_id=? AND rule_key=?",Long.class,ws,entity,key);
        long id=db.queryForObject("INSERT INTO crm_reactivation_rules(workspace_id,entity_id,rule_key,revision,dormant_days,actor_id) VALUES (?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,key,revision,days,scope.actor().id());
        receipts.record(scope,"REACTIVATION_RULE",receipt,id);
        audit.record(scope.actor(),ws,entity,"CREATE","crm_reactivation_rules",Long.toString(id),revision,"SUCCESS",key); return id;
    }
    @Transactional
    public List<Evaluation> scan(long ws,long entity,long ruleId,LocalDate asOf) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        if(asOf==null) throw new DomainException(400,"As-of date is required","asOf");
        var rules=db.query("SELECT dormant_days FROM crm_reactivation_rules WHERE workspace_id=? AND entity_id=? AND id=?",(r,n)->r.getInt(1),ws,entity,ruleId);
        if(rules.isEmpty()) throw new DomainException(404,"Reactivation rule not found");
        var customers=db.query("SELECT DISTINCT c.id FROM customers c JOIN customer_entity_accounts a ON a.workspace_id=c.workspace_id AND a.customer_id=c.id WHERE c.workspace_id=? AND a.entity_id=? AND c.active AND a.active ORDER BY c.id",(r,n)->r.getLong(1),ws,entity);
        var results=new java.util.ArrayList<Evaluation>();
        for(long customer:customers) {
            var purchases=db.query("""
              SELECT sl.id,s.shipment_date FROM shipment_lines sl JOIN shipments s ON s.workspace_id=sl.workspace_id AND s.entity_id=sl.entity_id AND s.id=sl.shipment_id
              JOIN sales_orders o ON o.workspace_id=s.workspace_id AND o.entity_id=s.entity_id AND o.id=s.order_id
              JOIN customer_entity_accounts a ON a.workspace_id=o.workspace_id AND a.entity_id=o.entity_id AND a.id=o.customer_account_id
              WHERE sl.workspace_id=? AND sl.entity_id=? AND a.customer_id=? AND s.status='POSTED' AND sl.active AND s.shipment_date<=? AND sl.net>0
              ORDER BY s.shipment_date DESC,sl.id DESC LIMIT 1
              """,(r,n)->new Object[]{r.getLong(1),r.getObject(2,LocalDate.class)},ws,entity,customer,asOf);
            Long line=purchases.isEmpty()?null:(Long)purchases.get(0)[0]; LocalDate date=purchases.isEmpty()?null:(LocalDate)purchases.get(0)[1];
            Long caseId=null; String outcome,reason;
            if(line==null) {outcome="NO_HISTORY";reason="No positive-net posted shipment on or before as-of";}
            else if(date.plusDays(rules.get(0)).isAfter(asOf)) {outcome="NOT_DORMANT";reason="Threshold not reached";}
            else {
                var existing=db.query("SELECT id FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? AND customer_id=? AND purchase_shipment_line_id=?",(r,n)->r.getLong(1),ws,entity,customer,line);
                var open=db.query("SELECT id FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? AND customer_id=? AND status='OPEN' ORDER BY id LIMIT 1",(r,n)->r.getLong(1),ws,entity,customer);
                Boolean held=db.queryForObject("SELECT EXISTS(SELECT 1 FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND customer_id=? AND status NOT IN ('RESOLVED','CANCELLED')) OR EXISTS(SELECT 1 FROM crm_opportunities o JOIN customer_entity_accounts a ON a.workspace_id=o.workspace_id AND a.entity_id=o.entity_id AND a.id=o.customer_account_id WHERE o.workspace_id=? AND o.entity_id=? AND a.customer_id=? AND o.stage NOT IN ('WON','LOST'))",Boolean.class,ws,entity,customer,ws,entity,customer);
                if(!existing.isEmpty()) {caseId=existing.get(0);outcome="HELD";reason="Existing case for purchase cycle; explicit reopen required";}
                else if(!open.isEmpty()) {caseId=open.get(0);outcome="HELD";reason="An open reactivation case already exists";}
                else if(Boolean.TRUE.equals(held)) {outcome="HELD";reason="Open inquiry or opportunity";}
                else {
                    caseId=db.queryForObject("INSERT INTO crm_reactivation_cases(workspace_id,entity_id,customer_id,purchase_shipment_line_id,purchase_date,created_by) VALUES (?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,customer,line,date,scope.actor().id());
                    db.update("INSERT INTO crm_reactivation_events(workspace_id,entity_id,case_id,version,kind,reason,actor_id) VALUES (?,?,?,1,'CREATE','Qualifying dormant purchase',?)",ws,entity,caseId,scope.actor().id());
                    outcome="ELIGIBLE";reason="Dormant threshold reached";
                }
            }
            db.update("INSERT INTO crm_reactivation_evaluations(workspace_id,entity_id,customer_id,rule_id,as_of,purchase_shipment_line_id,purchase_date,case_id,outcome,reason,actor_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",ws,entity,customer,ruleId,asOf,line,date,caseId,outcome,reason,scope.actor().id());
            results.add(new Evaluation(customer,line,date,caseId,outcome,reason));
        }
        audit.record(scope.actor(),ws,entity,"SCAN","crm_reactivation_rules",Long.toString(ruleId),1,"SUCCESS","As of "+asOf); return List.copyOf(results);
    }
    @Transactional
    public void transition(long ws,long entity,long id,long version,String status,String reason) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.query("SELECT version,status FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new Object[]{r.getLong(1),r.getString(2)},ws,entity,id);
        if(rows.isEmpty()) throw new DomainException(404,"Case not found");
        if((Long)rows.get(0)[0]!=version) throw new DomainException(409,"Case has changed");
        if(status==null || !List.of("OPEN","RESOLVED").contains(status) || status.equals(rows.get(0)[1])) throw new DomainException(400,"Invalid case transition");
        reason=InquiryService.text(reason,"reason",2000,true);
        if(status.equals("OPEN")) {
            Boolean conflict=db.queryForObject("SELECT EXISTS(SELECT 1 FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? AND customer_id=(SELECT customer_id FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? AND id=?) AND status='OPEN' AND id<>?)",Boolean.class,ws,entity,ws,entity,id,id);
            if(Boolean.TRUE.equals(conflict)) throw new DomainException(409,"Another case is open");
        }
        db.update("UPDATE crm_reactivation_cases SET status=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",status,ws,entity,id);
        db.update("INSERT INTO crm_reactivation_events(workspace_id,entity_id,case_id,version,kind,reason,actor_id) VALUES (?,?,?,?,?,?,?)",ws,entity,id,version+1,status.equals("OPEN")?"REOPEN":"RESOLVE",reason,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"TRANSITION","crm_reactivation_cases",Long.toString(id),version+1,"SUCCESS",reason);
    }
    @Transactional
    public long followUp(long ws,long entity,long id,long version,String subject,java.time.OffsetDateTime due) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.query("SELECT version,status,customer_id,follow_up_activity_id FROM crm_reactivation_cases WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new Object[]{r.getLong(1),r.getString(2),r.getLong(3),r.getObject(4)},ws,entity,id);
        if(rows.isEmpty()) throw new DomainException(404,"Case not found"); var row=rows.get(0);
        if((Long)row[0]!=version) throw new DomainException(409,"Case has changed");
        if(!row[1].equals("OPEN") || row[3]!=null) throw new DomainException(409,"Case is closed or already has a follow-up");
        subject=InquiryService.text(subject,"subject",500,true);
        long activity=db.queryForObject("INSERT INTO crm_activities(workspace_id,entity_id,customer_id,kind,subject,assignee_id,due_at,created_by) VALUES (?,?,?,'TASK',?,?,?,?) RETURNING id",Long.class,ws,entity,row[2],subject,scope.actor().id(),due,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"CREATE","crm_activities",Long.toString(activity),1,"SUCCESS","Simulated reactivation task");
        db.update("UPDATE crm_reactivation_cases SET follow_up_activity_id=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",activity,ws,entity,id);
        db.update("INSERT INTO crm_reactivation_events(workspace_id,entity_id,case_id,version,kind,reason,actor_id) VALUES (?,?,?,?,'FOLLOW_UP',?,?)",ws,entity,id,version+1,subject,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"FOLLOW_UP","crm_reactivation_cases",Long.toString(id),version+1,"SUCCESS","Simulated task"); return activity;
    }
}
