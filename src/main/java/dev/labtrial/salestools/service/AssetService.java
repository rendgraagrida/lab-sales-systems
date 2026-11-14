package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetService {
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final TermService terms;
    private final dev.labtrial.salestools.finance.ServiceRevenueService revenue;
    public AssetService(JdbcTemplate db, AccessControl access, AuditLog audit, TermService terms, dev.labtrial.salestools.finance.ServiceRevenueService revenue) { this.db=db; this.access=access; this.audit=audit; this.terms=terms; this.revenue=revenue; }
    public List<Map<String,Object>> list(long w,long e) {
        access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE");
        return db.queryForList("select a.*,p.sku,c.erp_code,case when exists(select 1 from installation_events i join scenario_workspaces w on w.id=i.workspace_id where i.workspace_id=a.workspace_id and i.entity_id=a.entity_id and i.asset_id=a.id and i.business_date<=w.business_date) then 'INSTALLED' else 'SHIPPED' end asset_status from installed_instruments a join products p on p.workspace_id=a.workspace_id and p.id=a.product_id join customer_entity_accounts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.customer_account_id where a.workspace_id=? and a.entity_id=? order by a.id desc",w,e);
    }
    public List<Map<String,Object>> customers(long w,long e) {
        access.requireContext(w,e,false,"OPERATIONS");
        return db.queryForList("select a.id,a.version,a.erp_code,c.name from customer_entity_accounts a join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active where a.workspace_id=? and a.entity_id=? and a.active order by c.name",w,e);
    }
    public List<Map<String,Object>> devices(long w,long e) {
        access.requireContext(w,e,false,"OPERATIONS");
        return db.queryForList("select p.id,p.version,p.sku,p.name,s.version setting_version from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active where p.workspace_id=? and p.active and p.status='ACTIVE' and p.category='LAB_UNIT' and p.tracking='SERIAL' order by p.sku",e,w);
    }
    public List<Map<String,Object>> operators(long w,long e) {
        access.requireContext(w,e,false,"OPERATIONS");
        return db.queryForList("select c.id,c.version,c.first_name,c.last_name,a.id customer_account_id,l.version link_version from customer_entity_accounts a join customer_contact_links l on l.workspace_id=a.workspace_id and l.customer_id=a.customer_id and l.active join contacts c on c.workspace_id=l.workspace_id and c.id=l.contact_id and c.active where a.workspace_id=? and a.entity_id=? and a.active order by c.last_name,c.first_name",w,e);
    }
    public Map<String,Object> detail(long w,long e,long asset) {
        access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE");
        var rows=db.queryForList("select a.*,p.sku,c.erp_code from installed_instruments a join products p on p.workspace_id=a.workspace_id and p.id=a.product_id join customer_entity_accounts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.customer_account_id where a.workspace_id=? and a.entity_id=? and a.id=?",w,e,asset);
        if(rows.size()!=1) throw new DomainException(404,"Asset not found"); return rows.get(0);
    }
    public List<Map<String,Object>> history(long w,long e,long asset) {
        detail(w,e,asset);
        return db.queryForList("select business_date,completion_reference,reason,intent_key,occurred_at from installation_events where workspace_id=? and entity_id=? and asset_id=? order by id",w,e,asset);
    }
    public List<Map<String,Object>> operatorHistory(long w,long e,long asset) {
        detail(w,e,asset);
        return db.queryForList("select c.first_name,c.last_name,l.created_at from asset_operator_links l join contacts c on c.workspace_id=l.workspace_id and c.id=l.contact_id where l.workspace_id=? and l.entity_id=? and l.asset_id=? order by l.id",w,e,asset);
    }
    public List<Map<String,Object>> obligations(long w,long e,long asset) {
        detail(w,e,asset);
        return db.queryForList("select id,kind,status,version,order_line_id,unit_no from sales_service_obligations where workspace_id=? and entity_id=? and asset_id=? order by id",w,e,asset);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public long createOpeningAsset(AccessControl.Scope caller,long customerAccountId,long productId,String serial,String recordKey,LocalDate installationDate,String evidence,long operatorContactId) {
        long w=caller.workspaceId(),e=caller.entityId();
        var scope=access.requireContext(w,e,true,"FINANCE");
        if(scope.actor().id()!=caller.actor().id()) throw new DomainException(403,"Actor changed");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from scenario_workspaces where id=? and initialization_state='NEW')",Boolean.class,w))) throw new DomainException(409,"Opening asset requires a new workspace");
        return register(scope,customerAccountId,productId,serial,recordKey,installationDate,evidence,operatorContactId,"opening:"+recordKey,"opening");
    }
    @Transactional
    public long registerLegacy(long w,long e,long customerAccountId,long customerVersion,long productId,long productVersion,long settingVersion,String serial,String recordKey,LocalDate installationDate,String evidence,long operatorContactId,long contactVersion,long linkVersion,String intent) {
        var scope=access.requireContext(w,e,true,"OPERATIONS");
        intent=text(intent,"intent");
        var existing=db.queryForList("select id from installed_instruments where workspace_id=? and entity_id=? and registration_key=?",w,e,intent);
        if(existing.isEmpty() && !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from customer_entity_accounts a join customer_contact_links l on l.workspace_id=a.workspace_id and l.customer_id=a.customer_id and l.active and l.version=? join contacts c on c.workspace_id=l.workspace_id and c.id=l.contact_id and c.active and c.version=? join products p on p.workspace_id=a.workspace_id and p.id=? and p.version=? join product_entity_settings s on s.workspace_id=p.workspace_id and s.entity_id=a.entity_id and s.product_id=p.id and s.active and s.version=? where a.workspace_id=? and a.entity_id=? and a.id=? and a.active and a.version=? and c.id=?)",Boolean.class,linkVersion,contactVersion,productId,productVersion,settingVersion,w,e,customerAccountId,customerVersion,operatorContactId))) throw new DomainException(409,"References changed or are unavailable. Review the owner, device, assignment and operator versions; submitted values have not been refreshed.","customerRef","productRef","settingVersion","contact","linkVersion");
        return register(scope,customerAccountId,productId,serial,recordKey,installationDate,evidence,operatorContactId,intent,customerVersion+":"+productVersion+":"+settingVersion+":"+contactVersion+":"+linkVersion);
    }
    private long register(AccessControl.Scope scope,long customer,long product,String serial,String key,LocalDate date,String evidence,long operator,String intent,String referenceVersions) {
        long w=scope.workspaceId(),e=scope.entityId();
        serial=text(serial,"serial"); key=text(key,"key"); evidence=text(evidence,"reason"); intent=text(intent,"intent");
        String hash=hash(customer,product,serial,key,date,evidence,operator,referenceVersions);
        var existing=db.queryForList("select id,registration_hash from installed_instruments where workspace_id=? and entity_id=? and registration_key=?",w,e,intent);
        if(!existing.isEmpty()) { if(!hash.equals(existing.get(0).get("registration_hash"))) throw new DomainException(409,"Registration intent conflicts; use the original values for replay or a new intent for a different registration.","intent"); return number(existing.get(0),"id"); }
        LocalDate clock=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
        if(date==null || date.isAfter(clock)) throw new DomainException(400,"Installation date must not be in the future","date");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from customer_entity_accounts a join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active where a.workspace_id=? and a.entity_id=? and a.id=? and a.active)",Boolean.class,w,e,customer))) throw new DomainException(409,"Customer account unavailable","customerRef");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active where p.workspace_id=? and p.id=? and p.active and p.status='ACTIVE' and p.category='LAB_UNIT' and p.tracking='SERIAL')",Boolean.class,e,w,product))) throw new DomainException(409,"Device product unavailable","productRef");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from customer_entity_accounts a join customer_contact_links l on l.workspace_id=a.workspace_id and l.customer_id=a.customer_id and l.active join contacts c on c.workspace_id=l.workspace_id and c.id=l.contact_id and c.active where a.workspace_id=? and a.entity_id=? and a.id=? and c.id=?)",Boolean.class,w,e,customer,operator))) throw new DomainException(409,"Operator is not actively linked to the owner","contact");
        long asset=db.queryForObject("insert into installed_instruments(workspace_id,entity_id,record_key,product_id,serial,customer_account_id,origin,registration_key,registration_hash,legacy_evidence,created_by) values (?,?,?,?,?,?,'LEGACY',?,?,?,?) returning id",Long.class,w,e,key,product,serial,customer,intent,hash,evidence,scope.actor().id());
        db.update("insert into installation_events(workspace_id,entity_id,asset_id,business_date,completion_reference,reason,intent_key,payload_hash,created_by) values (?,?,?,?,?,?,?,?,?)",w,e,asset,date,evidence,evidence,"registration:"+intent,hash,scope.actor().id());
        db.update("insert into asset_operator_links(workspace_id,entity_id,asset_id,contact_id,created_by) values (?,?,?,?,?)",w,e,asset,operator,scope.actor().id());
        audit.record(scope.actor(),w,e,"CREATE","installed_instruments",Long.toString(asset),1,"SUCCESS","legacy asset registration");
        return asset;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordPostedShipment(AccessControl.Scope scope,long shipmentId) {
        long w=scope.workspaceId(),e=scope.entityId();
        var authorized=access.requireContext(w,e,true,"OPERATIONS");
        if(authorized.actor().id()!=scope.actor().id()) throw new DomainException(403,"Actor changed");
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from shipments where workspace_id=? and entity_id=? and id=? and status='POSTED')",Boolean.class,w,e,shipmentId))) throw new DomainException(409,"Shipment is not posted");
        Boolean invalid=db.queryForObject("select exists(select 1 from shipments h join shipment_lines l on l.workspace_id=h.workspace_id and l.entity_id=h.entity_id and l.shipment_id=h.id and l.active join products p on p.workspace_id=l.workspace_id and p.id=l.product_id and p.category='LAB_UNIT' and p.tracking='SERIAL' where h.workspace_id=? and h.entity_id=? and h.id=? and h.status='POSTED' and (l.slice_start is null or l.slice_end is null or l.slice_start<>trunc(l.slice_start) or l.slice_end<>trunc(l.slice_end) or (select count(*) from shipment_line_tracking t where t.workspace_id=l.workspace_id and t.entity_id=l.entity_id and t.shipment_line_id=l.id and t.active)<>l.quantity or exists(select 1 from shipment_line_tracking t left join inventory_reservations r on r.workspace_id=t.workspace_id and r.entity_id=t.entity_id and r.id=t.reservation_id left join instrument_serials s on s.workspace_id=r.workspace_id and s.entity_id=r.entity_id and s.id=r.serial_id where t.workspace_id=l.workspace_id and t.entity_id=l.entity_id and t.shipment_line_id=l.id and t.active and (t.quantity<>1 or r.order_line_id<>l.order_line_id or s.id is null or s.status<>'ISSUED' or s.product_id<>l.product_id))))",Boolean.class,w,e,shipmentId);
        if(Boolean.TRUE.equals(invalid)) throw new DomainException(409,"Posted serial shipment lineage is incomplete or ambiguous");
        var rows=db.queryForList("select tr.id tracking_id,r.serial_id,s.serial,sl.order_line_id,sl.product_id,o.customer_account_id,(sl.slice_start + row_number() over(partition by sl.id order by tr.id)) unit_no,sl.slice_start,sl.slice_end from shipments h join sales_orders o on o.workspace_id=h.workspace_id and o.entity_id=h.entity_id and o.id=h.order_id join shipment_lines sl on sl.workspace_id=h.workspace_id and sl.entity_id=h.entity_id and sl.shipment_id=h.id and sl.active join shipment_line_tracking tr on tr.workspace_id=sl.workspace_id and tr.entity_id=sl.entity_id and tr.shipment_line_id=sl.id and tr.active join inventory_reservations r on r.workspace_id=tr.workspace_id and r.entity_id=tr.entity_id and r.id=tr.reservation_id join instrument_serials s on s.workspace_id=r.workspace_id and s.entity_id=r.entity_id and s.id=r.serial_id and s.product_id=sl.product_id join products p on p.workspace_id=sl.workspace_id and p.id=sl.product_id and p.category='LAB_UNIT' where h.workspace_id=? and h.entity_id=? and h.id=? and h.status='POSTED' and tr.quantity=1 and s.status='ISSUED' order by sl.id,tr.id",w,e,shipmentId);
        for(var row:rows) {
            long tracking=number(row,"tracking_id"), line=number(row,"order_line_id"), unit=((java.math.BigDecimal)row.get("unit_no")).longValueExact();
            String key="shipment:"+tracking, fingerprint=hash(shipmentId,tracking,line,unit,row.get("serial"));
            var previous=db.queryForList("select id,registration_hash from installed_instruments where workspace_id=? and entity_id=? and shipment_tracking_id=?",w,e,tracking);
            long asset;
            if(previous.isEmpty()) asset=db.queryForObject("insert into installed_instruments(workspace_id,entity_id,record_key,product_id,serial,customer_account_id,origin,inventory_serial_id,shipment_tracking_id,source_order_line_id,device_unit_no,registration_key,registration_hash,created_by) values (?,?,?,?,?,?,'SHIPMENT',?,?,?,?,?,?,?) returning id",Long.class,w,e,key,number(row,"product_id"),row.get("serial"),number(row,"customer_account_id"),number(row,"serial_id"),tracking,line,unit,key,fingerprint,scope.actor().id());
            else { if(!fingerprint.equals(previous.get(0).get("registration_hash"))) throw new DomainException(409,"Shipment asset lineage changed"); asset=number(previous.get(0),"id"); }
            db.update("update sales_service_obligations set asset_id=?,status=case when kind='PLAN' and start_basis='INSTALLATION' then 'WAITING_INSTALLATION' else 'READY' end,version=version+1 where workspace_id=? and entity_id=? and target_device_order_line_id=? and target_device_unit_no=? and asset_id is null and status='WAITING_ASSET'",asset,w,e,line,unit);
        }
    }
    @Transactional
    public void linkOperator(long w,long e,long assetId,long assetVersion,long contactId,long contactVersion,long linkVersion) {
        var scope=access.requireContext(w,e,true,"OPERATIONS");
        var rows=db.queryForList("select a.version from installed_instruments a join customer_entity_accounts account on account.workspace_id=a.workspace_id and account.entity_id=a.entity_id and account.id=a.customer_account_id and account.active join customer_contact_links l on l.workspace_id=account.workspace_id and l.customer_id=account.customer_id and l.active and l.version=? join contacts c on c.workspace_id=l.workspace_id and c.id=l.contact_id and c.active and c.version=? where a.workspace_id=? and a.entity_id=? and a.id=? and c.id=? for update of a",linkVersion,contactVersion,w,e,assetId,contactId);
        if(rows.size()!=1) throw new DomainException(409,"Operator or owner link is unavailable");
        if(number(rows.get(0),"version")!=assetVersion) throw new DomainException(409,"Asset changed");
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from asset_operator_links where workspace_id=? and entity_id=? and asset_id=? and contact_id=?)",Boolean.class,w,e,assetId,contactId))) throw new DomainException(409,"Operator already linked");
        db.update("insert into asset_operator_links(workspace_id,entity_id,asset_id,contact_id,created_by) values (?,?,?,?,?)",w,e,assetId,contactId,scope.actor().id());
        db.update("update installed_instruments set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,assetId);
        audit.record(scope.actor(),w,e,"LINK","installed_instruments",Long.toString(assetId),assetVersion+1,"SUCCESS","asset operator");
    }
    @Transactional
    public void complete(long w,long e,long assetId,long assetVersion,Long obligationId,LocalDate completionDate,LocalDate postingDate,String reference,String reason,String intent) {
        var scope=access.requireContext(w,e,true,"OPERATIONS");
        if(db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",w,e).size()!=1) throw new DomainException(404,"Entity not found");
        reference=text(reference,"reference"); reason=text(reason,"reason"); intent=text(intent,"intent");
        if(completionDate==null || postingDate==null || postingDate.isBefore(completionDate)) throw new DomainException(400,"Invalid completion or posting date","date");
        LocalDate clock=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
        if(completionDate.isAfter(clock)) throw new DomainException(400,"Completion is after business date","date");
        var assets=db.queryForList("select a.*,s.shipment_date from installed_instruments a left join shipment_line_tracking tr on tr.workspace_id=a.workspace_id and tr.entity_id=a.entity_id and tr.id=a.shipment_tracking_id left join shipment_lines l on l.workspace_id=tr.workspace_id and l.entity_id=tr.entity_id and l.id=tr.shipment_line_id left join shipments s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.id=l.shipment_id where a.workspace_id=? and a.entity_id=? and a.id=? for update of a",w,e,assetId);
        if(assets.size()!=1) throw new DomainException(404,"Asset not found");
        var asset=assets.get(0);
        String payload=hash(assetId,obligationId,completionDate,postingDate,reference,reason);
        var prior=db.queryForList("select id,payload_hash from installation_events where workspace_id=? and entity_id=? and intent_key=?",w,e,intent);
        if(!prior.isEmpty()) {
            if(!payload.equals(prior.get(0).get("payload_hash"))) throw new DomainException(409,"Installation intent conflicts");
            if(obligationId!=null) revenue.completeOnboarding(scope,obligationId,number(prior.get(0),"id"),completionDate,postingDate,intent,reference,reason);
            return;
        }
        if(obligationId!=null) {
            var completed=db.queryForList("select installation_event_id from service_completions where workspace_id=? and entity_id=? and intent_key=? and obligation_id=?",w,e,intent,obligationId);
            if(!completed.isEmpty()) {
                Object value=completed.get(0).get("installation_event_id");
                revenue.completeOnboarding(scope,obligationId,value==null?null:((Number)value).longValue(),completionDate,postingDate,intent,reference,reason);
                return;
            }
        }
        if(number(asset,"version")!=assetVersion) throw new DomainException(409,"Asset changed");
        if(asset.get("shipment_date")!=null && completionDate.isBefore(date(asset.get("shipment_date")))) throw new DomainException(400,"Installation predates shipment","date");
        if(obligationId!=null && !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from sales_service_obligations where workspace_id=? and entity_id=? and id=? and asset_id=? and kind='ONBOARDING' and status='READY')",Boolean.class,w,e,obligationId,assetId))) throw new DomainException(409,"Onboarding obligation changed or belongs to another asset");
        var installed=db.queryForList("select id,business_date from installation_events where workspace_id=? and entity_id=? and asset_id=?",w,e,assetId);
        Long event=null;
        if(installed.isEmpty()) {
            event=db.queryForObject("insert into installation_events(workspace_id,entity_id,asset_id,onboarding_obligation_id,business_date,completion_reference,reason,intent_key,payload_hash,created_by) values (?,?,?,?,?,?,?,?,?,?) returning id",Long.class,w,e,assetId,obligationId,completionDate,reference,reason,intent,payload,scope.actor().id());
            db.update("update installed_instruments set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,assetId);
            terms.validateInstallationPlans(scope,assetId,event);
        } else {
            if(obligationId==null) throw new DomainException(409,"Asset already installed");
            if(completionDate.isBefore(date(installed.get(0).get("business_date")))) throw new DomainException(409,"Onboarding completion predates installation");
        }
        if(obligationId!=null) {
            revenue.completeOnboarding(scope,obligationId,event,completionDate,postingDate,intent,reference,reason);
            db.update("update sales_service_obligations set status='COMPLETED',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,obligationId);
        }
        audit.record(scope.actor(),w,e,"COMPLETE","installed_instruments",Long.toString(assetId),assetVersion+1,"SUCCESS","installation or onboarding");
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static LocalDate date(Object value) { return value instanceof LocalDate d ? d : ((java.sql.Date)value).toLocalDate(); }
    private static String text(String s,String field) { if(s==null || s.isBlank() || s.trim().length()>200) throw new DomainException(400,"Required or too long",field); return s.trim(); }
    private static String hash(Object... values) {
        try { var md=MessageDigest.getInstance("SHA-256"); for(Object value:values) { byte[] bytes=String.valueOf(value).getBytes(StandardCharsets.UTF_8); md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); md.update(bytes); } return HexFormat.of().formatHex(md.digest()); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
