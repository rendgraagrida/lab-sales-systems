package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.order.QuoteService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceObligationService {
    public record TargetInput(int unitNo,long serviceProductVersionId,long compatibilityId,long compatibilityVersion,Long assetId,Long assetVersion,String deviceLineKey,Integer deviceUnitNo,String startBasis,LocalDate coverageStart,LocalDate coverageEnd,Long renewalIntentId,Long renewalIntentVersion) {}
    private final JdbcTemplate db;
    private final TermService terms;
    private final dev.labtrial.salestools.finance.ServiceRevenueService revenue;
    private final dev.labtrial.salestools.identity.AccessControl access;
    public ServiceObligationService(JdbcTemplate db,TermService terms,dev.labtrial.salestools.finance.ServiceRevenueService revenue,dev.labtrial.salestools.identity.AccessControl access) { this.db=db; this.terms=terms; this.revenue=revenue; this.access=access; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void saveQuoteTargets(AccessControl.Scope scope,long revisionId,List<QuoteService.LineInput> lines) {
        long w=scope.workspaceId(),e=scope.entityId();
        db.update("update quote_service_targets set active=false where workspace_id=? and entity_id=? and active and quote_line_id in (select id from quote_lines where workspace_id=? and entity_id=? and revision_id=?)",w,e,w,e,revisionId);
        for(var line:lines) {
            if(line.serviceTargets()==null) continue;
            var matches=db.queryForList("select id from quote_lines where workspace_id=? and entity_id=? and revision_id=? and line_key=? and active",w,e,revisionId,line.key().trim());
            if(matches.size()!=1) throw new DomainException(409,"Quote line changed");
            long lineId=n(matches.get(0),"id");
            for(var target:line.serviceTargets()) {
                if(target==null) throw new DomainException(400,"Service target required");
                Long device=null;
                if(target.deviceLineKey()!=null && !target.deviceLineKey().isBlank()) {
                    var rows=db.queryForList("select id from quote_lines where workspace_id=? and entity_id=? and revision_id=? and line_key=? and active",w,e,revisionId,target.deviceLineKey().trim());
                    if(rows.size()!=1) throw new DomainException(409,"Target device line unavailable"); device=n(rows.get(0),"id");
                }
                db.update("insert into quote_service_targets(workspace_id,entity_id,quote_line_id,unit_no,service_product_version_id,compatibility_id,compatibility_version,target_asset_id,target_asset_version,target_device_quote_line_id,target_device_unit_no,start_basis,coverage_start,coverage_end,renewal_intent_id,renewal_intent_version,active) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true)",w,e,lineId,target.unitNo(),target.serviceProductVersionId(),target.compatibilityId(),target.compatibilityVersion(),target.assetId(),target.assetVersion(),device,target.deviceUnitNo(),target.startBasis(),target.coverageStart(),target.coverageEnd(),target.renewalIntentId(),target.renewalIntentVersion());
            }
        }
        validateQuoteTargets(scope,revisionId);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void validateQuoteTargets(AccessControl.Scope scope,long revisionId) { validate(scope,revisionId); }
    @Transactional(propagation=Propagation.MANDATORY)
    public void validateAcceptedTargets(AccessControl.Scope scope,long revisionId) { validate(scope,revisionId); }
    private void validate(AccessControl.Scope scope,long revisionId) {
        long w=scope.workspaceId(),e=scope.entityId();
        var lines=db.queryForList("select l.id,l.quantity,l.product_category,l.product_id,l.service_product_version_id,q.customer_account_id from quote_lines l join quote_revisions r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.revision_id join quotes q on q.workspace_id=r.workspace_id and q.entity_id=r.entity_id and q.id=r.quote_id where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active order by l.id",w,e,revisionId);
        for(var line:lines) {
            long id=n(line,"id"); boolean service=List.of("SERVICE_PLAN","IMPLEMENTATION_SERVICE").contains(line.get("product_category"));
            var targets=db.queryForList("select * from quote_service_targets where workspace_id=? and entity_id=? and quote_line_id=? and active order by unit_no",w,e,id);
            int quantity;
            try { quantity=((java.math.BigDecimal)line.get("quantity")).intValueExact(); } catch(ArithmeticException ex) { if(service) throw new DomainException(400,"Service quantity must be whole"); else continue; }
            if((!service && !targets.isEmpty()) || (service && (quantity<1 || quantity>100 || targets.size()!=quantity))) throw new DomainException(409,"Service unit targets must match quantity");
            for(int i=0;i<targets.size();i++) {
                var t=targets.get(i); if(n(t,"unit_no")!=i+1 || n(t,"service_product_version_id")!=n(line,"service_product_version_id")) throw new DomainException(409,"Service target units or profile changed");
                var profiles=db.queryForList("select * from service_product_versions v where v.workspace_id=? and v.entity_id=? and v.id=? and v.product_id=? and v.enabled and v.revision=(select max(x.revision) from service_product_versions x where x.workspace_id=v.workspace_id and x.entity_id=v.entity_id and x.product_id=v.product_id)",w,e,n(t,"service_product_version_id"),n(line,"product_id"));
                if(profiles.size()!=1) throw new DomainException(409,"Service profile changed or disabled");
                boolean asset=t.get("target_asset_id")!=null, device=t.get("target_device_quote_line_id")!=null;
                if(asset==device) throw new DomainException(400,"Choose exactly one asset or device unit");
                long deviceProduct;
                if(asset) {
                    var assets=db.queryForList("select product_id from installed_instruments where workspace_id=? and entity_id=? and id=? and version=? and customer_account_id=?",w,e,n(t,"target_asset_id"),n(t,"target_asset_version"),n(line,"customer_account_id"));
                    if(assets.size()!=1) throw new DomainException(409,"Asset changed or belongs to another customer"); deviceProduct=n(assets.get(0),"product_id");
                } else {
                    var devices=db.queryForList("select product_id,quantity from quote_lines where workspace_id=? and entity_id=? and revision_id=? and id=? and active and product_category='LAB_UNIT' and tracking='SERIAL'",w,e,revisionId,n(t,"target_device_quote_line_id"));
                    if(devices.size()!=1 || t.get("target_device_unit_no")==null || n(t,"target_device_unit_no")<1 || ((java.math.BigDecimal)devices.get(0).get("quantity")).compareTo(java.math.BigDecimal.valueOf(n(t,"target_device_unit_no")))<0) throw new DomainException(409,"Target device unit unavailable"); deviceProduct=n(devices.get(0),"product_id");
                }
                if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from product_compatibilities where workspace_id=? and entity_id=? and id=? and version=? and active and device_id=? and component_id=?)",Boolean.class,w,e,n(t,"compatibility_id"),n(t,"compatibility_version"),deviceProduct,n(line,"product_id")))) throw new DomainException(409,"Device/service compatibility changed");
                String basis=(String)t.get("start_basis");
                if("IMPLEMENTATION_SERVICE".equals(line.get("product_category"))) {
                    if(!"INSTALLATION".equals(basis) || t.get("coverage_start")!=null || t.get("coverage_end")!=null || t.get("renewal_intent_id")!=null) throw new DomainException(400,"Onboarding cannot specify coverage");
                } else if("INSTALLATION".equals(basis)) {
                    if(t.get("coverage_start")!=null || t.get("coverage_end")!=null || t.get("renewal_intent_id")!=null) throw new DomainException(400,"Installation-based coverage cannot specify dates");
                } else if("EXPLICIT".equals(basis)) {
                    if(!asset || t.get("coverage_start")==null || t.get("coverage_end")==null || !date(t.get("coverage_end")).isAfter(date(t.get("coverage_start"))) || t.get("renewal_intent_id")!=null) throw new DomainException(400,"Explicit coverage requires an asset and valid dates");
                    if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from installation_events where workspace_id=? and entity_id=? and asset_id=? and business_date<=?)",Boolean.class,w,e,n(t,"target_asset_id"),t.get("coverage_start")))) throw new DomainException(409,"Explicit coverage requires an installed asset");
                } else if("RENEWAL".equals(basis)) {
                    if(!asset || t.get("renewal_intent_id")==null || t.get("renewal_intent_version")==null || !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from renewal_intents i join service_contract_terms p on p.workspace_id=i.workspace_id and p.entity_id=i.entity_id and p.id=i.predecessor_term_id join scenario_workspaces w on w.id=i.workspace_id where i.workspace_id=? and i.entity_id=? and i.id=? and i.version=? and i.status='OPEN' and i.customer_account_id=? and p.asset_id=? and p.status='VALIDATED' and i.target_start=p.coverage_end and w.business_date<p.coverage_end)",Boolean.class,w,e,n(t,"renewal_intent_id"),n(t,"renewal_intent_version"),n(line,"customer_account_id"),n(t,"target_asset_id")))) throw new DomainException(409,"Renewal source changed or lapsed");
                } else throw new DomainException(400,"Invalid service start basis");
            }
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public String targetFingerprint(AccessControl.Scope scope,long revisionId) {
        String result=db.queryForObject("select coalesce(jsonb_agg(to_jsonb(t)-'id' order by l.ordinal,t.unit_no)::text,'') from quote_service_targets t join quote_lines l on l.workspace_id=t.workspace_id and l.entity_id=t.entity_id and l.id=t.quote_line_id where t.workspace_id=? and t.entity_id=? and l.revision_id=? and l.active and t.active",String.class,scope.workspaceId(),scope.entityId(),revisionId);
        return result==null?"":result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void copyQuoteTargets(AccessControl.Scope scope,long oldRevisionId,long newRevisionId) {
        long w=scope.workspaceId(),e=scope.entityId();
        db.update("insert into quote_service_targets(workspace_id,entity_id,quote_line_id,unit_no,service_product_version_id,compatibility_id,compatibility_version,target_asset_id,target_asset_version,target_device_quote_line_id,target_device_unit_no,start_basis,coverage_start,coverage_end,renewal_intent_id,renewal_intent_version,active) select t.workspace_id,t.entity_id,nl.id,t.unit_no,t.service_product_version_id,t.compatibility_id,t.compatibility_version,t.target_asset_id,t.target_asset_version,nd.id,t.target_device_unit_no,t.start_basis,t.coverage_start,t.coverage_end,t.renewal_intent_id,t.renewal_intent_version,true from quote_service_targets t join quote_lines ol on ol.workspace_id=t.workspace_id and ol.entity_id=t.entity_id and ol.id=t.quote_line_id join quote_lines nl on nl.workspace_id=ol.workspace_id and nl.entity_id=ol.entity_id and nl.revision_id=? and nl.line_key=ol.line_key and nl.active left join quote_lines od on od.workspace_id=t.workspace_id and od.entity_id=t.entity_id and od.id=t.target_device_quote_line_id left join quote_lines nd on nd.workspace_id=od.workspace_id and nd.entity_id=od.entity_id and nd.revision_id=? and nd.line_key=od.line_key and nd.active where t.workspace_id=? and t.entity_id=? and ol.revision_id=? and ol.active and t.active",newRevisionId,newRevisionId,w,e,oldRevisionId);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void createFromAcceptedOrder(AccessControl.Scope scope,long orderId) {
        long w=scope.workspaceId(),e=scope.entityId();
        authorizeFinancial(scope);
        var order=db.queryForList("select o.source_hash,o.currency,o.terms_days,r.accepted_business_date business_date from sales_orders o join quote_revisions r on r.workspace_id=o.workspace_id and r.entity_id=o.entity_id and r.id=o.quote_revision_id and r.status='ACCEPTED' where o.workspace_id=? and o.entity_id=? and o.id=? and o.sealed for update of o",w,e,orderId);
        if(order.size()!=1) throw new DomainException(409,"Accepted order is unavailable");
        var header=order.get(0);
        var lines=db.queryForList("select l.* from sales_order_lines l where l.workspace_id=? and l.entity_id=? and l.order_id=? and l.product_category in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') order by l.id",w,e,orderId);
        for(var line:lines) {
            long lineId=n(line,"id");
            var targets=db.queryForList("select t.*,p.coverage_type_id,p.duration_months from quote_service_targets t join service_product_versions p on p.workspace_id=t.workspace_id and p.entity_id=t.entity_id and p.id=t.service_product_version_id where t.workspace_id=? and t.entity_id=? and t.quote_line_id=? and t.active order by t.unit_no",w,e,n(line,"quote_line_id"));
            int count;
            try { count=((java.math.BigDecimal)line.get("quantity")).intValueExact(); } catch(ArithmeticException ex) { throw new DomainException(409,"Invalid accepted service quantity"); }
            if(count<1 || count>100 || targets.size()!=count) throw new DomainException(409,"Accepted service targets do not match quantity");
            var snapshot=new dev.labtrial.salestools.finance.SourceAmounts.Snapshot((java.math.BigDecimal)line.get("quantity"),(java.math.BigDecimal)line.get("unit_price"),(java.math.BigDecimal)line.get("subtotal"),(java.math.BigDecimal)line.get("discount"),(java.math.BigDecimal)line.get("net"),(java.math.BigDecimal)line.get("tax"),(java.math.BigDecimal)line.get("gross"));
            for(int i=0;i<count;i++) {
                var t=targets.get(i);
                if(n(t,"unit_no")!=i+1) throw new DomainException(409,"Accepted service unit sequence changed");
                var existing=db.queryForList("select id from sales_service_obligations where workspace_id=? and entity_id=? and order_line_id=? and unit_no=?",w,e,lineId,i+1);
                if(!existing.isEmpty()) continue;
                Long deviceLine=null,asset=t.get("target_asset_id")==null?null:n(t,"target_asset_id");
                if(t.get("target_device_quote_line_id")!=null) {
                    var device=db.queryForList("select id from sales_order_lines where workspace_id=? and entity_id=? and order_id=? and quote_line_id=?",w,e,orderId,n(t,"target_device_quote_line_id"));
                    if(device.size()!=1) throw new DomainException(409,"Accepted device target is unavailable"); deviceLine=n(device.get(0),"id");
                    var shipped=db.queryForList("select id from installed_instruments where workspace_id=? and entity_id=? and source_order_line_id=? and device_unit_no=?",w,e,deviceLine,n(t,"target_device_unit_no"));
                    if(!shipped.isEmpty()) asset=n(shipped.get(0),"id");
                }
                boolean plan="SERVICE_PLAN".equals(line.get("product_category")); String basis=(String)t.get("start_basis");
                boolean installed=asset!=null && Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from installation_events where workspace_id=? and entity_id=? and asset_id=?)",Boolean.class,w,e,asset));
                String status=asset==null?"WAITING_ASSET":plan && "INSTALLATION".equals(basis) && !installed?"WAITING_INSTALLATION":"READY";
                var slice=dev.labtrial.salestools.finance.SourceAmounts.slice(snapshot,java.math.BigDecimal.valueOf(i),java.math.BigDecimal.valueOf(i+1L));
                long id=db.queryForObject("insert into sales_service_obligations(workspace_id,entity_id,order_line_id,unit_no,quote_service_target_id,kind,asset_id,target_device_order_line_id,target_device_unit_no,service_product_version_id,coverage_type_id,duration_months,compatibility_id,compatibility_version,start_basis,requested_start,requested_end,accepted_source_hash,accepted_date,subtotal,discount,net,tax,gross,currency,terms_days,ar_account_id,unbilled_account_id,deferred_account_id,revenue_account_id,output_account_id,tax_version_id,status,created_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id",Long.class,w,e,lineId,i+1,n(t,"id"),plan?"PLAN":"ONBOARDING",asset,deviceLine,t.get("target_device_unit_no"),n(t,"service_product_version_id"),t.get("coverage_type_id"),t.get("duration_months"),n(t,"compatibility_id"),n(t,"compatibility_version"),basis,t.get("coverage_start"),t.get("coverage_end"),header.get("source_hash"),header.get("business_date"),slice.subtotal(),slice.discount(),slice.net(),slice.tax(),slice.gross(),header.get("currency"),header.get("terms_days"),line.get("ar_account_id"),line.get("unbilled_account_id"),line.get("deferred_account_id"),line.get("revenue_account_id"),line.get("output_account_id"),line.get("tax_version_id"),status,scope.actor().id());
                if(plan && "READY".equals(status)) terms.validateFromObligation(scope,id);
            }
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelFromOrder(AccessControl.Scope scope,long orderLineId,java.math.BigDecimal quantity,String intent,String reason) {
        long w=scope.workspaceId(),e=scope.entityId(); int count;
        authorizeFinancial(scope);
        try { count=quantity.intValueExact(); } catch(ArithmeticException | NullPointerException ex) { throw new DomainException(400,"Service cancellation requires whole units"); }
        if(count<=0) throw new DomainException(400,"Service cancellation requires positive units");
        var rows=db.queryForList("select * from sales_service_obligations where workspace_id=? and entity_id=? and order_line_id=? and status<>'CANCELLED' order by unit_no desc for update",w,e,orderLineId);
        if(rows.size()<count) throw new DomainException(409,"Cancellation exceeds remaining service units");
        LocalDate clock=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
        for(int i=0;i<count;i++) {
            var obligation=rows.get(i); long id=n(obligation,"id");
            var termsForUnit=db.queryForList("select id from service_contract_terms where workspace_id=? and entity_id=? and source_obligation_id=? and status='VALIDATED' for update",w,e,id);
            Long term=termsForUnit.isEmpty()?null:n(termsForUnit.get(0),"id");
            revenue.requireCancelable(scope,term==null?id:null,term,clock);
            if(term!=null) db.update("update service_contract_terms set status='CANCELLED',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,term);
            db.update("update sales_service_obligations set status='CANCELLED',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,id);
        }
    }
    private void authorizeFinancial(AccessControl.Scope scope) {
        var fresh=access.requireContext(scope.workspaceId(),scope.entityId(),true,"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE");
        if(fresh.actor().id()!=scope.actor().id()) throw new DomainException(403,"Actor changed");
        if(db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",scope.workspaceId(),scope.entityId()).size()!=1) throw new DomainException(404,"Entity not found");
    }
    private static long n(Map<String,Object> row,String field) { Object v=row.get(field); if(!(v instanceof Number)) throw new DomainException(409,"Service source reference missing"); return ((Number)v).longValue(); }
    private static LocalDate date(Object value) { return value instanceof LocalDate d?d:((java.sql.Date)value).toLocalDate(); }
}
