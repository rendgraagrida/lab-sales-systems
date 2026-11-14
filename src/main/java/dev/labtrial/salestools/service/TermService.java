package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.SalesPostingConfigService;
import dev.labtrial.salestools.finance.ServiceRevenueService;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TermService {
    public record OpeningTerm(String contractKey,String termKey,long assetId,long serviceProductVersionId,LocalDate start,LocalDate end,BigDecimal netFee,String currency,String openingReference) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final SalesPostingConfigService config;
    private final ServiceRevenueService revenue;
    public TermService(JdbcTemplate db,AccessControl access,SalesPostingConfigService config,ServiceRevenueService revenue) { this.db=db; this.access=access; this.config=config; this.revenue=revenue; }
    @Transactional(propagation=Propagation.MANDATORY)
    public long createOpeningTerm(AccessControl.Scope caller,OpeningTerm input) {
        long w=caller.workspaceId(),e=caller.entityId();
        var scope=access.requireContext(w,e,true,"FINANCE");
        if(scope.actor().id()!=caller.actor().id()) throw new DomainException(403,"Actor changed");
        lockEntity(w,e);
        if(input==null || input.contractKey()==null || input.contractKey().isBlank() || input.termKey()==null || input.termKey().isBlank() || input.openingReference()==null || input.openingReference().isBlank() || input.start()==null || input.end()==null || !input.end().isAfter(input.start()) || input.netFee()==null || input.netFee().signum()<0 || input.netFee().scale()>2 || input.currency()==null || input.currency().isBlank()) throw new DomainException(400,"Invalid opening term");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from scenario_workspaces where id=? and initialization_state='NEW')",Boolean.class,w))) throw new DomainException(409,"Opening term requires a new workspace");
        var sources=db.queryForList("select a.customer_account_id,p.product_id,p.coverage_type_id,p.duration_months from installed_instruments a join service_product_versions p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=? and p.category_snapshot='SERVICE_PLAN' and p.enabled join legal_entities e on e.workspace_id=a.workspace_id and e.id=a.entity_id and e.base_currency=? where a.workspace_id=? and a.entity_id=? and a.id=? and exists(select 1 from installation_events i where i.workspace_id=a.workspace_id and i.entity_id=a.entity_id and i.asset_id=a.id and i.business_date<=?) for update of a",input.serviceProductVersionId(),input.currency(),w,e,input.assetId(),input.start());
        if(sources.size()!=1) throw new DomainException(409,"Opening asset, profile, currency or installation is unavailable");
        var source=sources.get(0); var accounts=config.requireService(scope);
        String hash=hash(input.toString());
        var prior=db.queryForList("select id,source_hash from service_contract_terms where workspace_id=? and entity_id=? and record_key=?",w,e,input.termKey());
        if(!prior.isEmpty()) { if(!hash.equals(prior.get(0).get("source_hash"))) throw new DomainException(409,"Opening term conflicts"); long id=number(prior.get(0),"id"); revenue.ensureSchedule(scope,id); return id; }
        long contract=db.queryForObject("insert into service_contracts(workspace_id,entity_id,record_key,customer_account_id,coverage_type_id,created_by) values (?,?,?,?,?,?) returning id",Long.class,w,e,input.contractKey(),number(source,"customer_account_id"),number(source,"coverage_type_id"),scope.actor().id());
        long link=db.queryForObject("insert into service_contract_assets(workspace_id,entity_id,contract_id,asset_id,created_by) values (?,?,?,?,?) returning id",Long.class,w,e,contract,input.assetId(),scope.actor().id());
        BigDecimal zero=BigDecimal.ZERO.setScale(2), fee=input.netFee().setScale(2);
        long term=db.queryForObject("insert into service_contract_terms(workspace_id,entity_id,record_key,contract_asset_id,asset_id,coverage_type_id,plan_product_id,service_product_version_id,source_kind,opening_reference,coverage_start,coverage_end,subtotal,discount,net_fee,tax,gross,currency,terms_days,ar_account_id,unbilled_account_id,deferred_account_id,revenue_account_id,accepted_date,source_hash,source_version,status,validated_by) values (?,?,?,?,?,?,?,?,'OPENING',?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,1,'VALIDATED',?) returning id",Long.class,w,e,input.termKey(),link,input.assetId(),number(source,"coverage_type_id"),number(source,"product_id"),input.serviceProductVersionId(),input.openingReference(),input.start(),input.end(),fee,zero,fee,zero,fee,input.currency(),accounts.ar(),accounts.unbilled(),accounts.deferred(),accounts.serviceRevenue(),input.start(),hash,scope.actor().id());
        revenue.ensureSchedule(scope,term); return term;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public long validateFromObligation(AccessControl.Scope scope,long obligationId) {
        long w=scope.workspaceId(),e=scope.entityId();
        var authorized=access.requireContext(w,e,true,"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE");
        if(authorized.actor().id()!=scope.actor().id()) throw new DomainException(403,"Actor changed");
        lockEntity(w,e);
        var rows=db.queryForList("select x.*,o.customer_account_id,p.product_id,t.renewal_intent_id from sales_service_obligations x join sales_order_lines l on l.workspace_id=x.workspace_id and l.entity_id=x.entity_id and l.id=x.order_line_id join sales_orders o on o.workspace_id=l.workspace_id and o.entity_id=l.entity_id and o.id=l.order_id join service_product_versions p on p.workspace_id=x.workspace_id and p.entity_id=x.entity_id and p.id=x.service_product_version_id join quote_service_targets t on t.workspace_id=x.workspace_id and t.entity_id=x.entity_id and t.id=x.quote_service_target_id where x.workspace_id=? and x.entity_id=? and x.id=? for update of x",w,e,obligationId);
        if(rows.size()!=1) throw new DomainException(404,"Service obligation not found");
        var x=rows.get(0);
        var prior=db.queryForList("select id from service_contract_terms where workspace_id=? and entity_id=? and source_obligation_id=?",w,e,obligationId);
        if(!prior.isEmpty()) { long id=number(prior.get(0),"id"); revenue.ensureSchedule(scope,id); return id; }
        if(!"PLAN".equals(x.get("kind")) || x.get("asset_id")==null || "CANCELLED".equals(x.get("status"))) throw new DomainException(409,"Plan asset is not ready");
        long asset=number(x,"asset_id");
        if(db.queryForList("select id from installed_instruments where workspace_id=? and entity_id=? and id=? and customer_account_id=? for update",w,e,asset,number(x,"customer_account_id")).size()!=1) throw new DomainException(409,"Plan asset owner changed");
        LocalDate start=x.get("requested_start")==null?null:date(x.get("requested_start")), end=x.get("requested_end")==null?null:date(x.get("requested_end"));
        Long renewal=x.get("renewal_intent_id")==null?null:number(x,"renewal_intent_id"), predecessor=null;
        long contract,link;
        if(renewal!=null) {
            var intents=db.queryForList("select i.*,p.asset_id,p.coverage_type_id,p.coverage_end,p.status predecessor_status from renewal_intents i join service_contract_terms p on p.workspace_id=i.workspace_id and p.entity_id=i.entity_id and p.id=i.predecessor_term_id where i.workspace_id=? and i.entity_id=? and i.id=? for update of i,p",w,e,renewal);
            if(intents.size()!=1) throw new DomainException(409,"Renewal source unavailable");
            var i=intents.get(0); LocalDate clock=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
            if(!"OPEN".equals(i.get("status")) || !"VALIDATED".equals(i.get("predecessor_status")) || number(i,"asset_id")!=asset || number(i,"coverage_type_id")!=number(x,"coverage_type_id") || number(i,"customer_account_id")!=number(x,"customer_account_id") || !clock.isBefore(date(i.get("coverage_end")))) throw new DomainException(409,"Renewal source changed or lapsed");
            predecessor=number(i,"predecessor_term_id"); contract=number(i,"contract_id"); start=date(i.get("target_start")); end=start.plusMonths(number(x,"duration_months"));
            if((x.get("requested_start")!=null && !start.equals(date(x.get("requested_start")))) || (x.get("requested_end")!=null && !end.equals(date(x.get("requested_end"))))) throw new DomainException(409,"Renewal coverage must be adjacent and match the selected profile duration");
            var links=db.queryForList("select id from service_contract_assets where workspace_id=? and entity_id=? and contract_id=? and asset_id=?",w,e,contract,asset);
            if(links.size()!=1) throw new DomainException(409,"Renewal contract asset changed"); link=number(links.get(0),"id");
        } else {
            if("INSTALLATION".equals(x.get("start_basis"))) {
                var events=db.queryForList("select business_date from installation_events where workspace_id=? and entity_id=? and asset_id=? order by id",w,e,asset);
                if(events.size()!=1) throw new DomainException(409,"Plan waits for installation");
                start=date(events.get(0).get("business_date")); end=start.plusMonths(number(x,"duration_months"));
            }
            if(start==null || end==null || !end.isAfter(start)) throw new DomainException(409,"Plan coverage dates unavailable");
            contract=db.queryForObject("insert into service_contracts(workspace_id,entity_id,record_key,customer_account_id,coverage_type_id,created_by) values (?,?,?,?,?,?) returning id",Long.class,w,e,"obligation:"+obligationId,number(x,"customer_account_id"),number(x,"coverage_type_id"),scope.actor().id());
            link=db.queryForObject("insert into service_contract_assets(workspace_id,entity_id,contract_id,asset_id,created_by) values (?,?,?,?,?) returning id",Long.class,w,e,contract,asset,scope.actor().id());
        }
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from service_contract_terms where workspace_id=? and entity_id=? and asset_id=? and coverage_type_id=? and status='VALIDATED' and coverage_start<? and coverage_end>?)",Boolean.class,w,e,asset,number(x,"coverage_type_id"),end,start))) throw new DomainException(409,"Coverage overlaps an existing validated term");
        long term=db.queryForObject("insert into service_contract_terms(workspace_id,entity_id,record_key,contract_asset_id,asset_id,coverage_type_id,plan_product_id,service_product_version_id,source_kind,source_obligation_id,predecessor_term_id,renewal_intent_id,coverage_start,coverage_end,subtotal,discount,net_fee,tax,gross,currency,terms_days,ar_account_id,unbilled_account_id,deferred_account_id,revenue_account_id,output_account_id,tax_version_id,accepted_date,source_hash,source_version,status,validated_by) values (?,?,?,?,?,?,?,?,'ORDER',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'VALIDATED',?) returning id",Long.class,w,e,"obligation:"+obligationId,link,asset,number(x,"coverage_type_id"),number(x,"product_id"),number(x,"service_product_version_id"),obligationId,predecessor,renewal,start,end,x.get("subtotal"),x.get("discount"),x.get("net"),x.get("tax"),x.get("gross"),x.get("currency"),x.get("terms_days"),x.get("ar_account_id"),x.get("unbilled_account_id"),x.get("deferred_account_id"),x.get("revenue_account_id"),x.get("output_account_id"),x.get("tax_version_id"),x.get("accepted_date"),x.get("accepted_source_hash"),number(x,"version"),scope.actor().id());
        if(renewal!=null) db.update("update renewal_intents set status='CONVERTED',accepted_term_id=?,version=version+1 where workspace_id=? and entity_id=? and id=?",term,w,e,renewal);
        revenue.ensureSchedule(scope,term); return term;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void validateInstallationPlans(AccessControl.Scope scope,long assetId,long installationEventId) {
        long w=scope.workspaceId(),e=scope.entityId();
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from installation_events where workspace_id=? and entity_id=? and id=? and asset_id=?)",Boolean.class,w,e,installationEventId,assetId))) throw new DomainException(409,"Installation event does not belong to asset");
        var plans=db.queryForList("select id from sales_service_obligations where workspace_id=? and entity_id=? and asset_id=? and kind='PLAN' and start_basis='INSTALLATION' and status='WAITING_INSTALLATION' order by id for update",w,e,assetId);
        for(var plan:plans) { long id=number(plan,"id"); validateFromObligation(scope,id); db.update("update sales_service_obligations set status='READY',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,id); }
    }
    private static LocalDate date(Object value) { return value instanceof LocalDate d?d:((java.sql.Date)value).toLocalDate(); }
    private void lockEntity(long w,long e) {
        if(db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",w,e).size()!=1) throw new DomainException(404,"Entity not found");
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
}
