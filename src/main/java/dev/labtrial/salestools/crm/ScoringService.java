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
public class ScoringService {
    public record Rule(long id,String key,long revision,int territory,int need,int timeframe,int budget) {}
    public record Input(boolean territory,boolean need,boolean timeframe,boolean budget) {}
    public record Evaluation(long id,long inquiryId,long sourceVersion,long ruleId,LocalDate asOf,Input input,
                             int territoryPoints,int needPoints,int timeframePoints,int budgetPoints,int score,String reasons) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final CrmReceipts receipts;
    public ScoringService(JdbcTemplate db,AccessControl access,AuditLog audit,CrmReceipts receipts) { this.db=db; this.access=access; this.audit=audit; this.receipts=receipts; }
    public List<Rule> rules(long ws,long entity) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,rule_key,revision,territory_points,need_points,timeframe_points,budget_points FROM crm_score_rules WHERE workspace_id=? AND entity_id=? ORDER BY rule_key,revision DESC",
            (r,n)->new Rule(r.getLong(1),r.getString(2),r.getLong(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getInt(7)),ws,entity);
    }
    public List<Evaluation> history(long ws,long entity,long inquiry) {
        access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER");
        return db.query("SELECT id,source_version,rule_id,as_of,territory,need,timeframe,budget,territory_points,need_points,timeframe_points,budget_points,score,reasons FROM crm_score_evaluations WHERE workspace_id=? AND entity_id=? AND inquiry_id=? ORDER BY id DESC",
            (r,n)->new Evaluation(r.getLong(1),inquiry,r.getLong(2),r.getLong(3),r.getObject(4,LocalDate.class),new Input(r.getBoolean(5),r.getBoolean(6),r.getBoolean(7),r.getBoolean(8)),r.getInt(9),r.getInt(10),r.getInt(11),r.getInt(12),r.getInt(13),r.getString(14)),ws,entity,inquiry);
    }
    @Transactional
    public long createRule(long ws,long entity,String key,int territory,int need,int timeframe,int budget,String intent) {
        var scope=access.requireContext(ws,entity,true,"SALES_MANAGER");
        if(key==null || key.isBlank() || key.trim().length()>100) throw new DomainException(400,"Rule key is required","key");
        if(territory<0 || territory>100 || need<0 || need>100 || timeframe<0 || timeframe>100 || budget<0 || budget>100) throw new DomainException(400,"Each weight must be between 0 and 100");
        key=key.trim();
        var receipt=receipts.key(intent,key,territory,need,timeframe,budget); Long previous=receipts.previous(scope,"SCORING_RULE",receipt); if(previous!=null) return previous;
        Long revision=db.queryForObject("SELECT coalesce(max(revision),0)+1 FROM crm_score_rules WHERE workspace_id=? AND entity_id=? AND rule_key=?",Long.class,ws,entity,key);
        long id=db.queryForObject("INSERT INTO crm_score_rules(workspace_id,entity_id,rule_key,revision,territory_points,need_points,timeframe_points,budget_points,actor_id) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,key,revision,territory,need,timeframe,budget,scope.actor().id());
        receipts.record(scope,"SCORING_RULE",receipt,id);
        audit.record(scope.actor(),ws,entity,"CREATE","crm_score_rules",Long.toString(id),revision,"SUCCESS",key);
        return id;
    }
    @Transactional
    public Evaluation evaluate(long ws,long entity,long inquiry,long sourceVersion,long ruleId,LocalDate asOf,Input input) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        if(asOf==null || input==null) throw new DomainException(400,"As-of date and scoring inputs are required");
        var previous=db.query("SELECT id,territory_points,need_points,timeframe_points,budget_points,score,reasons FROM crm_score_evaluations WHERE workspace_id=? AND entity_id=? AND inquiry_id=? AND source_version=? AND rule_id=? AND as_of=? AND territory=? AND need=? AND timeframe=? AND budget=?",
            (r,n)->new Evaluation(r.getLong(1),inquiry,sourceVersion,ruleId,asOf,input,r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getString(7)),ws,entity,inquiry,sourceVersion,ruleId,asOf,input.territory(),input.need(),input.timeframe(),input.budget());
        if(!previous.isEmpty()) return previous.get(0);
        var versions=db.query("SELECT version FROM crm_inquiries WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->r.getLong(1),ws,entity,inquiry);
        if(versions.isEmpty()) throw new DomainException(404,"Inquiry not found");
        if(versions.get(0)!=sourceVersion) throw new DomainException(409,"Inquiry has changed");
        var rules=db.query("SELECT territory_points,need_points,timeframe_points,budget_points FROM crm_score_rules WHERE workspace_id=? AND entity_id=? AND id=?",(r,n)->new int[]{r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4)},ws,entity,ruleId);
        if(rules.isEmpty()) throw new DomainException(404,"Score rule not found");
        int[] weights=rules.get(0); int t=input.territory()?weights[0]:0, n=input.need()?weights[1]:0, f=input.timeframe()?weights[2]:0, b=input.budget()?weights[3]:0;
        int total=Math.min(100,t+n+f+b);
        String reasons=String.join("; ",java.util.stream.Stream.of(input.territory()?null:"Territory not confirmed",input.need()?null:"Compatible need not confirmed",input.timeframe()?null:"Timeframe not confirmed",input.budget()?null:"Budget not confirmed").filter(java.util.Objects::nonNull).toList());
        long id=db.queryForObject("INSERT INTO crm_score_evaluations(workspace_id,entity_id,inquiry_id,source_version,rule_id,as_of,territory,need,timeframe,budget,territory_points,need_points,timeframe_points,budget_points,score,reasons,actor_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,inquiry,sourceVersion,ruleId,asOf,input.territory(),input.need(),input.timeframe(),input.budget(),t,n,f,b,total,reasons,scope.actor().id());
        audit.record(scope.actor(),ws,entity,"EVALUATE","crm_score_evaluations",Long.toString(id),1,"SUCCESS","Simulated score");
        return new Evaluation(id,inquiry,sourceVersion,ruleId,asOf,input,t,n,f,b,total,reasons);
    }
}
