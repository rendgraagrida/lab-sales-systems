package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.crm.OpportunityService;
import dev.labtrial.salestools.identity.AccessControl;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RenewalService {
    public record ScanResult(LocalDate asOf, List<Long> created, List<Long> existing, List<String> blocked) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final OpportunityService opportunities;
    public RenewalService(JdbcTemplate db, AccessControl access, AuditLog audit, OpportunityService opportunities) {
        this.db = db; this.access = access; this.audit = audit; this.opportunities = opportunities;
    }
    public Map<String, Object> clock(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForMap("select business_date,version from scenario_workspaces where id=?", w);
    }
    public List<Map<String, Object>> owners(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForList("select u.id,u.login,u.security_version from app_users u join workspace_memberships wm on wm.user_id=u.id and wm.workspace_id=? and wm.active join entity_memberships em on em.workspace_id=wm.workspace_id and em.user_id=u.id and em.entity_id=? and em.active where u.active and exists(select 1 from user_roles r where r.workspace_id=wm.workspace_id and r.user_id=u.id and r.active and r.role_code in ('INSIDE_SALES','SALES_MANAGER')) order by u.login,u.id", w, e);
    }
    public List<Map<String, Object>> list(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForList("select i.*,c.record_key contract_key,t.record_key predecessor_key,t.coverage_end,o.title,o.stage from renewal_intents i join service_contracts c on c.workspace_id=i.workspace_id and c.entity_id=i.entity_id and c.id=i.contract_id join service_contract_terms t on t.workspace_id=i.workspace_id and t.entity_id=i.entity_id and t.id=i.predecessor_term_id left join crm_opportunities o on o.workspace_id=i.workspace_id and o.entity_id=i.entity_id and o.id=i.opportunity_id where i.workspace_id=? and i.entity_id=? order by i.target_start,i.id", w, e);
    }
    public Map<String,Object> detail(long w,long e,long id) {
        access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.queryForList("select i.*,c.record_key contract_key,t.record_key predecessor_key,t.coverage_start,t.coverage_end,a.serial,o.title,o.stage from renewal_intents i join service_contracts c on c.workspace_id=i.workspace_id and c.entity_id=i.entity_id and c.id=i.contract_id join service_contract_terms t on t.workspace_id=i.workspace_id and t.entity_id=i.entity_id and t.id=i.predecessor_term_id join installed_instruments a on a.workspace_id=t.workspace_id and a.entity_id=t.entity_id and a.id=t.asset_id join crm_opportunities o on o.workspace_id=i.workspace_id and o.entity_id=i.entity_id and o.id=i.opportunity_id where i.workspace_id=? and i.entity_id=? and i.id=?",w,e,id);
        if(rows.size()!=1) throw new DomainException(404,"Renewal not found"); return rows.get(0);
    }
    public List<Map<String, Object>> due(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForList("select t.id,t.record_key,t.coverage_start,t.coverage_end,c.record_key contract_key,a.serial,case when t.coverage_end<=w.business_date then 'LAPSED - explicit policy required' when not exists(select 1 from customer_entity_accounts ac join customers cu on cu.workspace_id=ac.workspace_id and cu.id=ac.customer_id and cu.active where ac.workspace_id=c.workspace_id and ac.entity_id=c.entity_id and ac.id=c.customer_account_id and ac.active) then 'BLOCKED - customer unavailable' when not exists(select 1 from installation_events i where i.workspace_id=a.workspace_id and i.entity_id=a.entity_id and i.asset_id=a.id and i.business_date<=w.business_date) then 'BLOCKED - asset not installed' when t.coverage_end-w.business_date<=90 then 'DUE' else 'NOT DUE' end as renewal_state from service_contract_terms t join service_contract_assets ca on ca.workspace_id=t.workspace_id and ca.entity_id=t.entity_id and ca.id=t.contract_asset_id join service_contracts c on c.workspace_id=ca.workspace_id and c.entity_id=ca.entity_id and c.id=ca.contract_id join installed_instruments a on a.workspace_id=t.workspace_id and a.entity_id=t.entity_id and a.id=t.asset_id join scenario_workspaces w on w.id=t.workspace_id where t.workspace_id=? and t.entity_id=? and t.status='VALIDATED' and not exists(select 1 from service_contract_terms successor where successor.workspace_id=t.workspace_id and successor.entity_id=t.entity_id and successor.predecessor_term_id=t.id and successor.status='VALIDATED') order by t.coverage_end,t.id", w, e);
    }
    @Transactional
    public ScanResult scan(long w, long e, long workspaceVersion, long ownerId, long ownerSecurityVersion) {
        var scope = access.requireContext(w, e, true, "INSIDE_SALES", "SALES_MANAGER");
        var clocks = db.queryForList("select business_date,version from scenario_workspaces where id=? for update", w);
        if (clocks.size() != 1) throw new DomainException(404, "Workspace not found");
        if (((Number)clocks.get(0).get("version")).longValue() != workspaceVersion) throw new DomainException(409, "Business clock changed; reload the renewal scan", "version");
        LocalDate asOf = date(clocks.get(0).get("business_date"));
        var terms = db.queryForList("select t.id,t.coverage_end,t.asset_id,c.id contract_id,c.customer_account_id from service_contract_terms t join service_contract_assets ca on ca.workspace_id=t.workspace_id and ca.entity_id=t.entity_id and ca.id=t.contract_asset_id join service_contracts c on c.workspace_id=ca.workspace_id and c.entity_id=ca.entity_id and c.id=ca.contract_id where t.workspace_id=? and t.entity_id=? and t.status='VALIDATED' and t.coverage_end<=? + 90 and not exists(select 1 from service_contract_terms s where s.workspace_id=t.workspace_id and s.entity_id=t.entity_id and s.predecessor_term_id=t.id and s.status='VALIDATED') order by t.id for update of t", w, e, asOf);
        var created = new ArrayList<Long>(); var existing = new ArrayList<Long>(); var blocked = new ArrayList<String>();
        for (var term : terms) {
            long termId = number(term, "id"), contract = number(term, "contract_id"), asset = number(term, "asset_id");
            LocalDate end = date(term.get("coverage_end"));
            var prior = db.queryForList("select id,opportunity_id from renewal_intents where workspace_id=? and entity_id=? and predecessor_term_id=?", w, e, termId);
            if (!prior.isEmpty()) { existing.add(number(prior.get(0), "id")); continue; }
            if (!asOf.isBefore(end)) { blocked.add("Term " + termId + " has lapsed; explicit late-renewal policy is required"); continue; }
            boolean eligible = Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from installed_instruments a join customer_entity_accounts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.customer_account_id and c.active join customers customer on customer.workspace_id=c.workspace_id and customer.id=c.customer_id and customer.active where a.workspace_id=? and a.entity_id=? and a.id=? and a.customer_account_id=? and exists(select 1 from installation_events i where i.workspace_id=a.workspace_id and i.entity_id=a.entity_id and i.asset_id=a.id and i.business_date<=?))", Boolean.class, w, e, asset, number(term, "customer_account_id"), asOf));
            if (!eligible) { blocked.add("Term " + termId + " has an unavailable customer or uninstalled asset"); continue; }
            long intent = db.queryForObject("insert into renewal_intents(workspace_id,entity_id,contract_id,predecessor_term_id,target_start,customer_account_id,status,created_by) values (?,?,?,?,?,?,'OPEN',?) returning id", Long.class, w, e, contract, termId, end, number(term, "customer_account_id"), scope.actor().id());
            long opportunity = opportunities.createRenewal(scope, intent, ownerId, ownerSecurityVersion);
            db.update("update renewal_intents set opportunity_id=?,version=version+1 where workspace_id=? and entity_id=? and id=?", opportunity, w, e, intent);
            audit.record(scope.actor(), w, e, "CREATE", "renewal_intents", Long.toString(intent), 2, "SUCCESS", "T-90 renewal");
            created.add(intent);
        }
        return new ScanResult(asOf, List.copyOf(created), List.copyOf(existing), List.copyOf(blocked));
    }
    private static long number(Map<String, Object> row, String name) { return ((Number)row.get(name)).longValue(); }
    private static LocalDate date(Object value) { return value instanceof LocalDate d ? d : ((java.sql.Date)value).toLocalDate(); }
}
