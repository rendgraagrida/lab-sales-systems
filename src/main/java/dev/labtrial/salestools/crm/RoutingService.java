package dev.labtrial.salestools.crm;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingService {
    public record Rule(long id,String key,long revision,String territory,String productLine,int priority,long targetId,boolean enabled) {}
    public record Result(Long ruleId,Long assigneeId,String reason) {}
    public record Evaluation(long id,long sourceVersion,Long ruleId,String outcome,String reason,java.time.OffsetDateTime occurredAt) {}
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final InquiryService inquiries; private final CrmReceipts receipts;
    public RoutingService(JdbcTemplate db,AccessControl access,AuditLog audit,InquiryService inquiries,CrmReceipts receipts) {this.db=db;this.access=access;this.audit=audit;this.inquiries=inquiries;this.receipts=receipts;}
    public List<Rule> rules(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,rule_key,revision,territory,product_line,priority,target_id,enabled FROM crm_routing_rules WHERE workspace_id=? AND entity_id=? ORDER BY rule_key,revision DESC",(r,n)->new Rule(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5),r.getInt(6),r.getLong(7),r.getBoolean(8)),ws,entity);
    }
    public List<Evaluation> history(long ws,long entity,long inquiry) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,source_version,rule_id,outcome,reason,occurred_at FROM crm_routing_evaluations WHERE workspace_id=? AND entity_id=? AND inquiry_id=? ORDER BY id DESC",(r,n)->new Evaluation(r.getLong(1),r.getLong(2),(Long)r.getObject(3),r.getString(4),r.getString(5),r.getObject(6,java.time.OffsetDateTime.class)),ws,entity,inquiry);
    }
    @Transactional
    public long createRule(long ws,long entity,String key,String territory,String productLine,int priority,long target,boolean enabled,String intent) {
        var scope=access.requireContext(ws,entity,true,"SALES_MANAGER"); key=InquiryService.text(key,"key",100,true);
        territory=InquiryService.text(territory,"territory",80,false); productLine=InquiryService.text(productLine,"productLine",80,false);
        var receipt=receipts.key(intent,key,territory,productLine,priority,target,enabled); Long previous=receipts.previous(scope,"ROUTING_RULE",receipt); if(previous!=null) return previous;
        if(!inquiries.eligible(ws,entity,target)) throw new DomainException(400,"Target is unavailable","targetId");
        long revision=db.queryForObject("SELECT coalesce(max(revision),0)+1 FROM crm_routing_rules WHERE workspace_id=? AND entity_id=? AND rule_key=?",Long.class,ws,entity,key);
        long id=db.queryForObject("INSERT INTO crm_routing_rules(workspace_id,entity_id,rule_key,revision,territory,product_line,priority,target_id,enabled,actor_id) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,key,revision,territory,productLine,priority,target,enabled,scope.actor().id());
        receipts.record(scope,"ROUTING_RULE",receipt,id);
        audit.record(scope.actor(),ws,entity,"CREATE","crm_routing_rules",Long.toString(id),revision,"SUCCESS",key); return id;
    }
    @Transactional
    public Result route(long ws,long entity,long id,long version) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.query("SELECT version,territory,product_line,status FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new Object[]{r.getLong(1),r.getString(2),r.getString(3),r.getString(4)},ws,entity,id);
        if(rows.isEmpty()) throw new DomainException(404,"Inquiry not found"); var row=rows.get(0);
        if((Long)row[0]!=version) throw new DomainException(409,"Inquiry has changed");
        if(List.of("RESOLVED","CANCELLED").contains(row[3])) throw new DomainException(409,"Inquiry is closed");
        var matches=db.query("SELECT r.id,r.target_id,r.rule_key FROM crm_routing_rules r WHERE r.workspace_id=? AND r.entity_id=? AND r.enabled AND (r.territory='' OR r.territory=?) AND (r.product_line='' OR r.product_line=?) AND r.revision=(SELECT max(x.revision) FROM crm_routing_rules x WHERE x.workspace_id=r.workspace_id AND x.entity_id=r.entity_id AND x.rule_key=r.rule_key) ORDER BY r.priority DESC,r.rule_key ASC,r.id ASC LIMIT 1",(r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getString(3)},ws,entity,row[1],row[2]);
        Long rule=null,target=null; String reason;
        if(matches.isEmpty()) reason="No matching routing rule";
        else {var match=matches.get(0); rule=(Long)match[0]; long candidate=(Long)match[1]; if(inquiries.eligible(ws,entity,candidate)) {target=candidate;reason="Matched rule "+match[2];} else reason="Matched rule "+match[2]+" but target is unavailable; review required";}
        db.update("UPDATE crm_inquiries SET assignee_id=?,version=version+1 WHERE workspace_id=? AND entity_id=? AND id=?",target,ws,entity,id);
        db.update("INSERT INTO crm_routing_evaluations(workspace_id,entity_id,inquiry_id,source_version,territory_snapshot,product_line_snapshot,rule_id,outcome,reason,actor_id) VALUES (?,?,?,?,?,?,?,?,?,?)",ws,entity,id,version,row[1],row[2],rule,target==null?"UNASSIGNED":"ASSIGNED",reason,scope.actor().id());
        db.update("INSERT INTO crm_inquiry_events(workspace_id,entity_id,inquiry_id,version,kind,detail,snapshot,actor_id) SELECT workspace_id,entity_id,id,?,'ROUTE',?,to_jsonb(i),? FROM crm_inquiries i WHERE workspace_id=? AND entity_id=? AND id=?",version+1,reason,scope.actor().id(),ws,entity,id);
        audit.record(scope.actor(),ws,entity,"ROUTE","crm_inquiries",Long.toString(id),version+1,"SUCCESS",reason); return new Result(rule,target,reason);
    }
}
