package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.SalesPostingConfigService;
import dev.labtrial.salestools.finance.SourceAmounts;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.service.ServiceObligationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteService {
    public record LineInput(String key, long productId, long productVersion, long settingVersion, long uomVersion,
        long bookId, long bookVersion, long bookLineVersion, BigDecimal quantity, BigDecimal overridePrice,
        BigDecimal discountPct, BigDecimal discountAmount, long taxId, long taxVersion, List<ServiceObligationService.TargetInput> serviceTargets) {}
    public record AddressRef(long id,long version) {}
    public record Draft(String origin, long customerAccountId, long customerAccountVersion, Long opportunityId,
        LocalDate businessDate, LocalDate validUntil, long configVersion, AddressRef billingAddress, AddressRef shippingAddress, Long serviceConfigVersion, List<LineInput> lines) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final SalesPostingConfigService config;
    private final ServiceObligationService obligations;
    public QuoteService(JdbcTemplate db, AccessControl access, AuditLog audit, SalesPostingConfigService config, ServiceObligationService obligations) {
        this.db=db; this.access=access; this.audit=audit; this.config=config; this.obligations=obligations;
    }
    private AccessControl.Scope write(long w,long e) { return access.requireContext(w,e,true,"INSIDE_SALES","SALES_MANAGER"); }
    private void read(long w,long e) { access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER"); }
    private static long n(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args); if(rows.size()!=1) throw new DomainException(409,"Source or configuration changed"); return rows.get(0);
    }
    public List<Map<String,Object>> list(long w,long e,String query) {
        read(w,e); String q=query==null?"":query.trim(); if(q.length()>200) throw new DomainException(400,"Search is too long");
        return db.queryForList("select q.id,q.number,q.origin,q.version,r.id revision_id,r.revision,r.status from quotes q join lateral (select * from quote_revisions where workspace_id=q.workspace_id and entity_id=q.entity_id and quote_id=q.id order by revision desc limit 1) r on true where q.workspace_id=? and q.entity_id=? and position(lower(?) in lower(q.number))>0 order by q.id desc",w,e,q);
    }
    public Map<String,Object> quote(long w,long e,long id) {
        read(w,e); var rows=db.queryForList("select q.*,r.id revision_id,r.revision,r.status,r.payload_hash,r.business_date,r.valid_until,r.currency,r.terms_id,r.terms_days,r.customer_account_version,r.billing_address_id,r.billing_address_version,r.shipping_address_id,r.shipping_address_version,r.requires_approval,r.accepted_reference from quotes q join lateral (select * from quote_revisions where workspace_id=q.workspace_id and entity_id=q.entity_id and quote_id=q.id order by revision desc limit 1) r on true where q.workspace_id=? and q.entity_id=? and q.id=?",w,e,id);
        if(rows.isEmpty()) throw new DomainException(404,"Quote not found"); return rows.get(0);
    }
    public List<Map<String,Object>> lines(long w,long e,long id) { Map<String,Object> q=quote(w,e,id); return db.queryForList("select * from quote_lines where workspace_id=? and entity_id=? and revision_id=? and active order by ordinal,id",w,e,n(q,"revision_id")); }
    public List<Map<String,Object>> addressSnapshots(long w,long e,long id) { var q=quote(w,e,id); return db.queryForList("select address_role,address_version,label from document_address_snapshots where workspace_id=? and entity_id=? and revision_id=? order by address_role",w,e,n(q,"revision_id")); }
    public List<Map<String,Object>> customers(long w,long e) { read(w,e); return db.queryForList("select a.id,a.version,c.name,a.erp_code from customer_entity_accounts a join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active where a.workspace_id=? and a.entity_id=? and a.active order by c.name,a.id",w,e); }
    public List<Map<String,Object>> products(long w,long e) { read(w,e); return db.queryForList("select p.id,p.sku,p.name,p.category,p.version,s.version setting_version,u.version uom_version from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id and u.active where p.workspace_id=? and p.active and p.status='ACTIVE' and p.category in ('LAB_UNIT','LAB_SUPPLY','REPLACEMENT_PART','SERVICE_PLAN','IMPLEMENTATION_SERVICE') order by p.sku",e,w); }
    public List<Map<String,Object>> prices(long w,long e) { read(w,e); return db.queryForList("select b.id,b.code,b.version,b.valid_from,b.valid_to,l.product_id,l.version line_version,l.amount from price_books b join price_book_lines l on l.workspace_id=b.workspace_id and l.entity_id=b.entity_id and l.price_book_id=b.id where b.workspace_id=? and b.entity_id=? and b.active and b.status='APPROVED' and exists(select 1 from price_book_approvals a where a.workspace_id=b.workspace_id and a.entity_id=b.entity_id and a.price_book_id=b.id and a.revision=b.revision and a.approved_by=b.approved_by) order by b.code,l.product_id",w,e); }
    public List<Map<String,Object>> taxes(long w,long e) { read(w,e); return db.queryForList("select v.id,v.version,v.rate_pct,t.code,v.valid_from,v.valid_to from tax_versions v join tax_codes t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.tax_code_id and t.active where v.workspace_id=? and v.entity_id=? and not v.price_inclusive order by t.code,v.valid_from",w,e); }
    public List<Map<String,Object>> opportunities(long w,long e) { read(w,e); return db.queryForList("select id,title,customer_account_id,stage from crm_opportunities where workspace_id=? and entity_id=? order by id desc",w,e); }
    public List<Map<String,Object>> addresses(long w,long e) { read(w,e); return db.queryForList("select a.id,a.customer_account_id,a.address_role,v.label,v.revision version from party_addresses a join lateral (select revision,label from party_address_versions v where v.workspace_id=a.workspace_id and v.entity_id=a.entity_id and v.address_id=a.id order by revision desc limit 1) v on true where a.workspace_id=? and a.entity_id=? order by a.id",w,e); }
    public long configVersion(long w,long e) { read(w,e); var rows=db.query("select version from sales_posting_config where workspace_id=? and entity_id=?",(r,n)->r.getLong(1),w,e); if(rows.isEmpty()) throw new DomainException(409,"Configure sales posting accounts first"); return rows.get(0); }
    public Long serviceConfigVersion(long w,long e) { read(w,e); var rows=db.query("select version from service_posting_config where workspace_id=? and entity_id=?",(r,n)->r.getLong(1),w,e); return rows.isEmpty()?null:rows.get(0); }
    public List<Map<String,Object>> serviceProfiles(long w,long e) { read(w,e); return db.queryForList("select v.id,v.product_id,v.revision,p.sku from service_product_versions v join products p on p.workspace_id=v.workspace_id and p.id=v.product_id where v.workspace_id=? and v.entity_id=? and v.enabled and v.revision=(select max(x.revision) from service_product_versions x where x.workspace_id=v.workspace_id and x.entity_id=v.entity_id and x.product_id=v.product_id) order by p.sku",w,e); }
    public List<Map<String,Object>> serviceMatrix(long w,long e) { read(w,e); return db.queryForList("select c.id,c.version,d.sku device_sku,p.sku service_sku from product_compatibilities c join products d on d.workspace_id=c.workspace_id and d.id=c.device_id join products p on p.workspace_id=c.workspace_id and p.id=c.component_id where c.workspace_id=? and c.entity_id=? and c.active and p.category in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') order by d.sku,p.sku",w,e); }
    public List<Map<String,Object>> assets(long w,long e) { read(w,e); return db.queryForList("select id,version,record_key,serial,customer_account_id from installed_instruments where workspace_id=? and entity_id=? order by record_key",w,e); }
    public List<Map<String,Object>> renewals(long w,long e) { read(w,e); return db.queryForList("select id,version,customer_account_id,target_start,opportunity_id from renewal_intents where workspace_id=? and entity_id=? and status='OPEN' order by id",w,e); }
    public List<Map<String,Object>> targets(long w,long e,long quoteId) {
        var q=quote(w,e,quoteId);
        return db.queryForList("select t.*,l.line_key,device.line_key device_line_key from quote_service_targets t join quote_lines l on l.workspace_id=t.workspace_id and l.entity_id=t.entity_id and l.id=t.quote_line_id left join quote_lines device on device.workspace_id=t.workspace_id and device.entity_id=t.entity_id and device.id=t.target_device_quote_line_id where t.workspace_id=? and t.entity_id=? and l.revision_id=? and l.active and t.active order by l.ordinal,t.unit_no",w,e,n(q,"revision_id"));
    }
    private static String text(String s,int max,String field) { if(s==null || s.isBlank() || s.trim().length()>max) throw new DomainException(400,"Required or too long",field); return s.trim(); }
    private void validateAddress(long w,long e,long customer,String role,AddressRef ref,LocalDate businessDate) {
        if(ref==null) return;
        Integer count=db.queryForObject("select count(*) from party_addresses a join lateral (select revision,valid_from,valid_to from party_address_versions v where v.workspace_id=a.workspace_id and v.entity_id=a.entity_id and v.address_id=a.id order by revision desc limit 1) v on true where a.workspace_id=? and a.entity_id=? and a.id=? and a.customer_account_id=? and a.address_role=? and v.revision=? and v.valid_from<=? and (v.valid_to is null or v.valid_to>=?)",Integer.class,w,e,ref.id(),customer,role,ref.version(),businessDate,businessDate);
        if(count==null || count!=1) throw new DomainException(409,"Address reference changed or is unavailable");
    }
    @Transactional
    public long save(long w,long e,Long quoteId,Long expectedVersion,Draft input) {
        var scope=write(w,e);
        if(input==null || input.businessDate()==null || input.validUntil()==null || input.validUntil().isBefore(input.businessDate())) throw new DomainException(400,"Invalid validity dates");
        if(input.origin()==null || !Set.of("LOCAL_CRM","DIRECT_ERP").contains(input.origin()) || ("LOCAL_CRM".equals(input.origin()) != (input.opportunityId()!=null))) throw new DomainException(400,"Invalid quote origin","origin");
        if(input.lines()==null || input.lines().isEmpty() || input.lines().size()>100) throw new DomainException(400,"One to 100 lines required");
        long targetCount=input.lines().stream().filter(java.util.Objects::nonNull).filter(line->line.serviceTargets()!=null).mapToLong(line->line.serviceTargets().size()).sum();
        if(targetCount>100) throw new DomainException(400,"At most 100 service unit targets are allowed");
        var account=one("select a.version,a.terms_id,t.days,t.version terms_version,e.base_currency from customer_entity_accounts a join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active join payment_terms t on t.workspace_id=a.workspace_id and t.entity_id=a.entity_id and t.id=a.terms_id and t.active join legal_entities e on e.workspace_id=a.workspace_id and e.id=a.entity_id where a.workspace_id=? and a.entity_id=? and a.id=? and a.active for update of a,t",w,e,input.customerAccountId());
        if(n(account,"version")!=input.customerAccountVersion()) throw new DomainException(409,"Customer account changed");
        if(input.opportunityId()!=null) one("select id from crm_opportunities where workspace_id=? and entity_id=? and id=? and customer_account_id=? for update",w,e,input.opportunityId(),input.customerAccountId());
        var mappings=config.require(scope); if(mappings.version()!=input.configVersion()) throw new DomainException(409,"Sales configuration changed");
        SalesPostingConfigService.ServiceAccounts serviceMappings=null;
        if(input.serviceConfigVersion()!=null) {
            serviceMappings=config.requireService(scope);
            if(serviceMappings.version()!=input.serviceConfigVersion()) throw new DomainException(409,"Service posting configuration changed");
        }
        validateAddress(w,e,input.customerAccountId(),"BILLING",input.billingAddress(),input.businessDate());
        validateAddress(w,e,input.customerAccountId(),"SHIPPING",input.shippingAddress(),input.businessDate());
        Map<String,Object> old=null;
        if(quoteId!=null) { old=one("select q.*,r.id revision_id,r.status,r.revision from quotes q join lateral (select * from quote_revisions where workspace_id=q.workspace_id and entity_id=q.entity_id and quote_id=q.id order by revision desc limit 1) r on true where q.workspace_id=? and q.entity_id=? and q.id=? for update of q",w,e,quoteId); if(expectedVersion==null || n(old,"version")!=expectedVersion) throw new DomainException(409,"Quote changed"); if(!"DRAFT".equals(old.get("status"))) throw new DomainException(409,"Only draft revisions can be edited"); if(n(old,"customer_account_id")!=input.customerAccountId() || !old.get("origin").equals(input.origin()) || !java.util.Objects.equals(old.get("opportunity_id"),input.opportunityId())) throw new DomainException(409,"Quote identity cannot be changed"); }
        else if(expectedVersion!=null) throw new DomainException(400,"Unexpected version");
        record Prepared(LineInput input,Map<String,Object> product,Map<String,Object> price,Map<String,Object> tax,SourceAmounts.Snapshot amounts,boolean override) {}
        List<Prepared> prepared=new ArrayList<>(); Set<String> keys=new HashSet<>(); boolean approval=false;
        for(LineInput line:input.lines()) {
            if(line==null || !keys.add(text(line.key(),80,"lineKey"))) throw new DomainException(400,"Duplicate or invalid line key","lineKey");
            var p=one("select p.*,s.version setting_version,u.id unit_id,u.version unit_version,u.code unit_code,u.quantity_scale from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id and u.active where p.workspace_id=? and p.id=? and p.active and p.status='ACTIVE' and p.category in ('LAB_UNIT','LAB_SUPPLY','REPLACEMENT_PART','SERVICE_PLAN','IMPLEMENTATION_SERVICE') for update of p,s,u",e,w,line.productId());
            if(n(p,"version")!=line.productVersion() || n(p,"setting_version")!=line.settingVersion() || n(p,"unit_version")!=line.uomVersion()) throw new DomainException(409,"Product reference changed");
            var price=one("select b.version book_version,l.id line_id,l.version line_version,l.amount from price_books b join price_book_lines l on l.workspace_id=b.workspace_id and l.entity_id=b.entity_id and l.price_book_id=b.id where b.workspace_id=? and b.entity_id=? and b.id=? and b.active and b.status='APPROVED' and b.currency=? and b.valid_from<=? and (b.valid_to is null or b.valid_to>=?) and l.product_id=? for update of b,l",w,e,line.bookId(),account.get("base_currency"),input.businessDate(),input.businessDate(),line.productId());
            if(n(price,"book_version")!=line.bookVersion() || n(price,"line_version")!=line.bookLineVersion()) throw new DomainException(409,"Price book changed");
            Integer approvalRecord=db.queryForObject("select count(*) from price_books b join price_book_approvals a on a.workspace_id=b.workspace_id and a.entity_id=b.entity_id and a.price_book_id=b.id and a.revision=b.revision and a.approved_by=b.approved_by where b.workspace_id=? and b.entity_id=? and b.id=?",Integer.class,w,e,line.bookId());
            if(approvalRecord==null || approvalRecord!=1) throw new DomainException(409,"Price book lacks independent approval history");
            var tax=one("select v.id,v.version,v.rate_pct,v.output_account_id from tax_versions v join tax_codes t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.tax_code_id and t.active join chart_accounts a on a.workspace_id=v.workspace_id and a.entity_id=v.entity_id and a.id=v.output_account_id and a.active and a.account_type='LIABILITY' where v.workspace_id=? and v.entity_id=? and v.id=? and not v.price_inclusive and v.valid_from<=? and (v.valid_to is null or v.valid_to>?) for update of v,t,a",w,e,line.taxId(),input.businessDate(),input.businessDate());
            if(n(tax,"version")!=line.taxVersion()) throw new DomainException(409,"Tax reference changed");
            boolean override=line.overridePrice()!=null; BigDecimal unit=override?line.overridePrice():(BigDecimal)price.get("amount");
            var amounts=SourceAmounts.price(line.quantity(),unit,line.discountPct(),line.discountAmount(),(BigDecimal)tax.get("rate_pct"),((Number)p.get("quantity_scale")).intValue());
            if("SERIAL".equals(p.get("tracking")) && line.quantity().stripTrailingZeros().scale()>0) throw new DomainException(400,"Serial quantity must be whole");
            boolean service=Set.of("SERVICE_PLAN","IMPLEMENTATION_SERVICE").contains(p.get("category"));
            if(service && (serviceMappings==null || line.serviceTargets()==null || line.serviceTargets().isEmpty())) throw new DomainException(400,"Service configuration and unit targets are required");
            if(!service && line.serviceTargets()!=null && !line.serviceTargets().isEmpty()) throw new DomainException(400,"Physical lines cannot have service targets");
            if(service && (line.quantity().stripTrailingZeros().scale()>0 || line.quantity().compareTo(BigDecimal.valueOf(100))>0)) throw new DomainException(400,"Service quantity must be a whole number no greater than 100","quantity");
            if(service && line.serviceTargets().stream().anyMatch(java.util.Objects::isNull)) throw new DomainException(400,"Service target required");
            if(service && line.serviceTargets().stream().anyMatch(t -> t.serviceProductVersionId()!=line.serviceTargets().get(0).serviceProductVersionId())) throw new DomainException(400,"Service units must use the same product version");
            if(override || ("LAB_UNIT".equals(p.get("category")) && ((line.discountPct()!=null && line.discountPct().compareTo(BigDecimal.TEN)>0) || amounts.discount().multiply(BigDecimal.TEN).compareTo(amounts.subtotal())>0))) approval=true;
            prepared.add(new Prepared(line,p,price,tax,amounts,override));
        }
        Map<Long,BigDecimal> quantities=new java.util.HashMap<>();
        List<Long> devices=new ArrayList<>();
        for(Prepared x:prepared) {
            quantities.merge(x.input().productId(),x.input().quantity(),BigDecimal::add);
            if("LAB_UNIT".equals(x.product().get("category")) && !devices.contains(x.input().productId())) devices.add(x.input().productId());
        }
        for(Prepared x:prepared) {
            if(!devices.isEmpty() && Set.of("LAB_SUPPLY","REPLACEMENT_PART").contains(x.product().get("category"))) {
                boolean compatible=false;
                for(long device:devices) {
                    Integer count=db.queryForObject("select count(*) from product_compatibilities where workspace_id=? and entity_id=? and device_id=? and component_id=? and active",Integer.class,w,e,device,x.input().productId());
                    compatible |= count!=null && count>0;
                }
                if(!compatible) throw new DomainException(400,"Component is not compatible with any quoted device","productRef");
            }
        }
        Map<Long,BigDecimal> requirements=new java.util.HashMap<>();
        for(var entry:quantities.entrySet()) {
            var components=db.queryForList("select component_id,quantity from bundle_components where workspace_id=? and entity_id=? and bundle_id=?",w,e,entry.getKey());
            for(var component:components) requirements.merge(n(component,"component_id"),entry.getValue().multiply((BigDecimal)component.get("quantity")),BigDecimal::add);
        }
        for(var requirement:requirements.entrySet()) if(quantities.getOrDefault(requirement.getKey(),BigDecimal.ZERO).compareTo(requirement.getValue())<0) throw new DomainException(400,"Bundle component quantity is missing","quantity");
        long quote,rev;
        if(old==null) {
            db.update("insert into document_sequences(workspace_id,entity_id,document_type,next_value) values (?,?,'QUOTE',1) on conflict (workspace_id,entity_id,document_type) do nothing",w,e);
            long seq=db.queryForObject("update document_sequences set next_value=next_value+1,version=version+1 where workspace_id=? and entity_id=? and document_type='QUOTE' returning next_value-1",Long.class,w,e);
            quote=db.queryForObject("insert into quotes(workspace_id,entity_id,number,origin,customer_account_id,opportunity_id,created_by) values (?,?,?,?,?,?,?) returning id",Long.class,w,e,"Q-"+seq,input.origin(),input.customerAccountId(),input.opportunityId(),scope.actor().id());
            rev=db.queryForObject("insert into quote_revisions(workspace_id,entity_id,quote_id,revision,status,business_date,valid_until,currency,terms_id,terms_days,customer_account_version,config_version,created_by,requires_approval) values (?,?,?,1,'DRAFT',?,?,?,?,?,?,?,?,?) returning id",Long.class,w,e,quote,input.businessDate(),input.validUntil(),account.get("base_currency"),account.get("terms_id"),account.get("days"),input.customerAccountVersion(),input.configVersion(),scope.actor().id(),approval);
        } else {
            quote=quoteId; rev=n(old,"revision_id");
            db.update("update quotes set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,quote);
            db.update("update quote_revisions set business_date=?,valid_until=?,currency=?,terms_id=?,terms_days=?,customer_account_version=?,config_version=?,requires_approval=? where workspace_id=? and entity_id=? and id=?",input.businessDate(),input.validUntil(),account.get("base_currency"),account.get("terms_id"),account.get("days"),input.customerAccountVersion(),input.configVersion(),approval,w,e,rev);
            db.update("update quote_lines set active=false where workspace_id=? and entity_id=? and revision_id=? and active",w,e,rev);
        }
        db.update("update quote_revisions set billing_address_id=?,billing_address_version=?,shipping_address_id=?,shipping_address_version=? where workspace_id=? and entity_id=? and id=?",input.billingAddress()==null?null:input.billingAddress().id(),input.billingAddress()==null?null:input.billingAddress().version(),input.shippingAddress()==null?null:input.shippingAddress().id(),input.shippingAddress()==null?null:input.shippingAddress().version(),w,e,rev);
        db.update("insert into quote_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,rev,scope.actor().id());
        int ordinal=0;
        String columns="workspace_id,entity_id,revision_id,line_key,ordinal,product_id,product_version,product_setting_version,product_sku,product_name,product_category,product_line,uom_id,uom_version,uom_code,tracking,quantity_scale,quantity,unit_price,price_book_id,price_book_version,price_book_line_id,price_book_line_version,list_price,manual_override,discount_pct,discount_amount,subtotal,discount,net,tax,gross,tax_version_id,tax_version_version,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id,deferred_account_id,service_config_version,service_product_version_id,active";
        String[] columnNames=columns.split(",");
        String updates=java.util.Arrays.stream(columnNames).skip(4).map(c->c+"=excluded."+c).collect(java.util.stream.Collectors.joining(","));
        String insert="insert into quote_lines("+columns+") values ("+String.join(",",java.util.Collections.nCopies(columnNames.length,"?"))+") on conflict (workspace_id,entity_id,revision_id,line_key) do update set "+updates;
        for(Prepared x:prepared) {
            var l=x.input(); var p=x.product(); var b=x.price(); var t=x.tax(); var a=x.amounts();
            boolean service=Set.of("SERVICE_PLAN","IMPLEMENTATION_SERVICE").contains(p.get("category"));
            Long profile=service?l.serviceTargets().get(0).serviceProductVersionId():null;
            Long revenue=service?("SERVICE_PLAN".equals(p.get("category"))?serviceMappings.serviceRevenue():serviceMappings.implementationRevenue()):mappings.revenue();
            db.update(insert,w,e,rev,l.key().trim(),++ordinal,l.productId(),l.productVersion(),l.settingVersion(),p.get("sku"),p.get("name"),p.get("category"),p.get("product_line"),p.get("unit_id"),l.uomVersion(),p.get("unit_code"),p.get("tracking"),p.get("quantity_scale"),a.quantity(),a.unitCost(),l.bookId(),l.bookVersion(),b.get("line_id"),l.bookLineVersion(),b.get("amount"),x.override(),l.discountPct(),l.discountAmount(),a.subtotal(),a.discount(),a.net(),a.tax(),a.gross(),l.taxId(),l.taxVersion(),t.get("rate_pct"),t.get("output_account_id"),mappings.ar(),mappings.unbilled(),revenue,service?null:mappings.cogs(),service?null:mappings.inventory(),service?serviceMappings.deferred():null,service?serviceMappings.version():null,profile,true);
        }
        obligations.saveQuoteTargets(scope,rev,input.lines());
        audit.record(scope.actor(),w,e,old==null?"CREATE":"UPDATE","quotes",Long.toString(quote),old==null?1:n(old,"version")+1,"SUCCESS","quote draft"); return quote;
    }
    private Map<String,Object> locked(long w,long e,long quote,long version) {
        var r=one("select q.version,q.created_by,r.* from quotes q join lateral (select * from quote_revisions where workspace_id=q.workspace_id and entity_id=q.entity_id and quote_id=q.id order by revision desc limit 1) r on true where q.workspace_id=? and q.entity_id=? and q.id=? for update of q",w,e,quote);
        if(n(r,"version")!=version) throw new DomainException(409,"Quote changed"); return r;
    }
    private void bump(long w,long e,long quote) { db.update("update quotes set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,quote); }
    private void event(AccessControl.Scope s,long w,long e,long rev,String kind,String hash,String reason) {
        db.update("insert into quote_events(workspace_id,entity_id,revision_id,kind,payload_hash,actor_id,reason) values (?,?,?,?,?,?,?)",w,e,rev,kind,hash,s.actor().id(),reason);
    }
    private void validateServiceConfig(AccessControl.Scope scope,long w,long e,long rev) {
        var versions=db.query("select distinct service_config_version from quote_lines where workspace_id=? and entity_id=? and revision_id=? and active and product_category in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE')",(r,n)->(Long)r.getObject(1),w,e,rev);
        if(!versions.isEmpty()) {
            long current=config.requireService(scope).version();
            if(versions.size()!=1 || versions.get(0)==null || versions.get(0)!=current) throw new DomainException(409,"Service posting configuration changed");
        }
    }
    private void validateRenewalBinding(long w,long e,long rev) {
        Boolean valid=db.queryForObject("""
            select not exists (
              select 1 from quote_revisions r join quotes q on q.workspace_id=r.workspace_id and q.entity_id=r.entity_id and q.id=r.quote_id
              left join crm_opportunities o on o.workspace_id=q.workspace_id and o.entity_id=q.entity_id and o.id=q.opportunity_id
              where r.workspace_id=? and r.entity_id=? and r.id=? and (
                (o.pipeline='SERVICE_RENEWAL' and not exists (
                  select 1 from quote_lines l join quote_service_targets t on t.workspace_id=l.workspace_id and t.entity_id=l.entity_id and t.quote_line_id=l.id and t.active
                  join renewal_intents i on i.workspace_id=t.workspace_id and i.entity_id=t.entity_id and i.id=t.renewal_intent_id
                  where l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.revision_id=r.id and l.active and i.opportunity_id=o.id))
                or exists (
                  select 1 from quote_lines l join quote_service_targets t on t.workspace_id=l.workspace_id and t.entity_id=l.entity_id and t.quote_line_id=l.id and t.active
                  join renewal_intents i on i.workspace_id=t.workspace_id and i.entity_id=t.entity_id and i.id=t.renewal_intent_id
                  where l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.revision_id=r.id and l.active
                    and (q.opportunity_id is distinct from i.opportunity_id or o.pipeline is distinct from 'SERVICE_RENEWAL'))))
            """,Boolean.class,w,e,rev);
        if(!Boolean.TRUE.equals(valid)) throw new DomainException(409,"Renewal quote must use its linked opportunity and renewal intent");
    }
    private void validateReferences(long w,long e,long rev) {
        Integer stale=db.queryForObject("select count(*) from quote_lines l join quote_revisions r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.revision_id left join products p on p.workspace_id=l.workspace_id and p.id=l.product_id left join product_entity_settings s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.product_id=l.product_id left join uoms u on u.workspace_id=l.workspace_id and u.id=l.uom_id left join price_books b on b.workspace_id=l.workspace_id and b.entity_id=l.entity_id and b.id=l.price_book_id left join price_book_lines pl on pl.workspace_id=l.workspace_id and pl.entity_id=l.entity_id and pl.id=l.price_book_line_id left join tax_versions t on t.workspace_id=l.workspace_id and t.entity_id=l.entity_id and t.id=l.tax_version_id left join tax_codes tc on tc.workspace_id=t.workspace_id and tc.entity_id=t.entity_id and tc.id=t.tax_code_id where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active and (p.id is null or not p.active or p.version<>l.product_version or p.uom_id<>l.uom_id or s.id is null or not s.active or s.version<>l.product_setting_version or u.id is null or not u.active or u.version<>l.uom_version or b.id is null or not b.active or b.status<>'APPROVED' or b.version<>l.price_book_version or b.currency<>r.currency or r.business_date<b.valid_from or (b.valid_to is not null and r.business_date>b.valid_to) or pl.id is null or pl.version<>l.price_book_line_version or t.id is null or t.version<>l.tax_version_version or t.price_inclusive or tc.id is null or not tc.active or r.business_date<t.valid_from or (t.valid_to is not null and r.business_date>=t.valid_to))",Integer.class,w,e,rev);
        if(stale==null || stale>0) throw new DomainException(409,"Quote references changed");
    }
    private String hash(AccessControl.Scope scope,long w,long e,long rev) {
        String source=db.queryForObject("select concat(row_to_json(r)::text,coalesce((select jsonb_agg(to_jsonb(l) order by l.ordinal,l.id)::text from quote_lines l where l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.revision_id=r.id and l.active),''),coalesce((select jsonb_agg(c.user_id order by c.user_id)::text from quote_contributors c where c.workspace_id=r.workspace_id and c.entity_id=r.entity_id and c.revision_id=r.id),'')) from quote_revisions r where r.workspace_id=? and r.entity_id=? and r.id=?",String.class,w,e,rev);
        String targets=obligations.targetFingerprint(scope,rev);
        if(targets!=null && !targets.isEmpty()) source+=targets;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @Transactional
    public void submit(long w,long e,long quote,long version) {
        var s=write(w,e); var r=locked(w,e,quote,version); if(!"DRAFT".equals(r.get("status"))) throw new DomainException(409,"Quote is not a draft");
        long rev=n(r,"id");
        if(config.require(s).version()!=n(r,"config_version")) throw new DomainException(409,"Sales configuration changed");
        Integer eligible=db.queryForObject("select count(*) from quotes q join customer_entity_accounts a on a.workspace_id=q.workspace_id and a.entity_id=q.entity_id and a.id=q.customer_account_id and a.active join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active join payment_terms t on t.workspace_id=a.workspace_id and t.entity_id=a.entity_id and t.id=a.terms_id and t.active join quote_revisions r on r.workspace_id=q.workspace_id and r.entity_id=q.entity_id and r.quote_id=q.id where q.workspace_id=? and q.entity_id=? and q.id=? and r.id=? and a.version=r.customer_account_version and t.id=r.terms_id and t.days=r.terms_days",Integer.class,w,e,quote,rev);
        if(eligible==null || eligible!=1) throw new DomainException(409,"Customer or terms changed");
        long customer=db.queryForObject("select customer_account_id from quotes where workspace_id=? and entity_id=? and id=?",Long.class,w,e,quote);
        LocalDate sourceDate=((java.sql.Date)r.get("business_date")).toLocalDate();
        if(r.get("billing_address_id")!=null) validateAddress(w,e,customer,"BILLING",new AddressRef(n(r,"billing_address_id"),n(r,"billing_address_version")),sourceDate);
        if(r.get("shipping_address_id")!=null) validateAddress(w,e,customer,"SHIPPING",new AddressRef(n(r,"shipping_address_id"),n(r,"shipping_address_version")),sourceDate);
        Integer count=db.queryForObject("select count(*) from quote_lines where workspace_id=? and entity_id=? and revision_id=? and active",Integer.class,w,e,rev);
        if(count==null || count==0) throw new DomainException(409,"Quote has no lines");
        validateServiceConfig(s,w,e,rev);
        obligations.validateQuoteTargets(s,rev);
        validateRenewalBinding(w,e,rev);
        Integer inactiveProducts=db.queryForObject("select count(*) from quote_lines l join products p on p.workspace_id=l.workspace_id and p.id=l.product_id where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active and p.status<>'ACTIVE'",Integer.class,w,e,rev);
        if(inactiveProducts!=null && inactiveProducts>0) throw new DomainException(409,"Product publication changed");
        validateReferences(w,e,rev);
        db.update("insert into quote_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,rev,s.actor().id());
        String hash=hash(s,w,e,rev); String state=Boolean.TRUE.equals(r.get("requires_approval"))?"SUBMITTED":"APPROVED";
        db.update("update quote_revisions set status=?,payload_hash=?,submitted_by=?,submitted_at=now() where workspace_id=? and entity_id=? and id=?",state,hash,s.actor().id(),w,e,rev);
        db.update("insert into document_address_snapshots(workspace_id,entity_id,revision_id,address_id,address_version,address_role,label) select r.workspace_id,r.entity_id,r.id,a.id,v.revision,a.address_role,v.label from quote_revisions r join party_addresses a on a.workspace_id=r.workspace_id and a.entity_id=r.entity_id and a.id in (r.billing_address_id,r.shipping_address_id) join party_address_versions v on v.workspace_id=a.workspace_id and v.entity_id=a.entity_id and v.address_id=a.id and v.revision=case when a.id=r.billing_address_id then r.billing_address_version else r.shipping_address_version end where r.workspace_id=? and r.entity_id=? and r.id=?",w,e,rev);
        bump(w,e,quote); event(s,w,e,rev,"SUBMITTED",hash,""); audit.record(s.actor(),w,e,"SUBMIT","quotes",Long.toString(quote),version+1,"SUCCESS",state);
    }
    @Transactional
    public void decide(long w,long e,long quote,long version,boolean approved,String reason) {
        var s=access.requireContext(w,e,true,"SALES_MANAGER"); var r=locked(w,e,quote,version);
        if(!"SUBMITTED".equals(r.get("status"))) throw new DomainException(409,"Quote is not awaiting approval");
        long rev=n(r,"id"); Integer maker=db.queryForObject("select count(*) from quote_contributors where workspace_id=? and entity_id=? and revision_id=? and user_id=?",Integer.class,w,e,rev,s.actor().id());
        if(maker!=null && maker>0) throw new DomainException(403,"A revision contributor cannot approve it");
        String why=reason==null?"":reason.trim(); if(why.length()>500 || (!approved && why.isEmpty())) throw new DomainException(400,"Rejection reason required or too long","reason");
        String status=approved?"APPROVED":"REJECTED";
        db.update("update quote_revisions set status=?,approved_by=?,approved_at=now(),decision_reason=? where workspace_id=? and entity_id=? and id=?",status,s.actor().id(),why,w,e,rev);
        bump(w,e,quote); event(s,w,e,rev,status,(String)r.get("payload_hash"),why); audit.record(s.actor(),w,e,status,"quotes",Long.toString(quote),version+1,"SUCCESS",why);
    }
    @Transactional
    public void accept(long w,long e,long quote,long version,LocalDate businessDate,String reference) {
        var s=write(w,e); var r=locked(w,e,quote,version);
        if(!"APPROVED".equals(r.get("status"))) throw new DomainException(409,"Quote is not approved");
        Integer unavailable=db.queryForObject("select count(*) from quote_lines l left join products p on p.workspace_id=l.workspace_id and p.id=l.product_id where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active and (p.id is null or not p.active or p.status<>'ACTIVE')",Integer.class,w,e,n(r,"id"));
        if(unavailable==null || unavailable>0) throw new DomainException(409,"Published products are required for acceptance");
        String ref=text(reference,200,"reference");
        LocalDate from=((java.sql.Date)r.get("business_date")).toLocalDate(), until=((java.sql.Date)r.get("valid_until")).toLocalDate();
        if(businessDate==null || businessDate.isBefore(from) || businessDate.isAfter(until)) throw new DomainException(400,"Acceptance date is outside quote validity","businessDate");
        long rev=n(r,"id");
        if(config.require(s).version()!=n(r,"config_version")) throw new DomainException(409,"Sales configuration changed");
        Integer accountValid=db.queryForObject("select count(*) from quotes q join customer_entity_accounts a on a.workspace_id=q.workspace_id and a.entity_id=q.entity_id and a.id=q.customer_account_id and a.active join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active join payment_terms t on t.workspace_id=a.workspace_id and t.entity_id=a.entity_id and t.id=a.terms_id and t.active join quote_revisions r on r.workspace_id=q.workspace_id and r.entity_id=q.entity_id and r.quote_id=q.id where q.workspace_id=? and q.entity_id=? and q.id=? and r.id=? and a.version=r.customer_account_version and t.id=r.terms_id and t.days=r.terms_days",Integer.class,w,e,quote,rev);
        if(accountValid==null || accountValid!=1) throw new DomainException(409,"Customer or terms changed");
        long customer=db.queryForObject("select customer_account_id from quotes where workspace_id=? and entity_id=? and id=?",Long.class,w,e,quote);
        if(r.get("billing_address_id")!=null) validateAddress(w,e,customer,"BILLING",new AddressRef(n(r,"billing_address_id"),n(r,"billing_address_version")),from);
        if(r.get("shipping_address_id")!=null) validateAddress(w,e,customer,"SHIPPING",new AddressRef(n(r,"shipping_address_id"),n(r,"shipping_address_version")),from);
        validateReferences(w,e,rev);
        validateServiceConfig(s,w,e,rev);
        obligations.validateAcceptedTargets(s,rev);
        validateRenewalBinding(w,e,rev);
        db.update("update quote_revisions set status='ACCEPTED',accepted_by=?,accepted_at=now(),accepted_business_date=?,accepted_reference=? where workspace_id=? and entity_id=? and id=?",s.actor().id(),businessDate,ref,w,e,rev);
        bump(w,e,quote); event(s,w,e,rev,"ACCEPTED",(String)r.get("payload_hash"),ref); audit.record(s.actor(),w,e,"ACCEPT","quotes",Long.toString(quote),version+1,"SUCCESS","accepted source");
    }
    @Transactional
    public void withdraw(long w,long e,long quote,long version,String reason) {
        var s=write(w,e); var r=locked(w,e,quote,version); if(!"ACCEPTED".equals(r.get("status"))) throw new DomainException(409,"Quote is not accepted");
        Integer booked=db.queryForObject("select count(*) from sales_orders where workspace_id=? and entity_id=? and quote_id=?",Integer.class,w,e,quote);
        if(booked!=null && booked>0) throw new DomainException(409,"Booked quote cannot be withdrawn");
        String why=text(reason,500,"reason"); long rev=n(r,"id");
        db.update("update quote_revisions set status='WITHDRAWN' where workspace_id=? and entity_id=? and id=?",w,e,rev);
        bump(w,e,quote); event(s,w,e,rev,"WITHDRAWN",(String)r.get("payload_hash"),why); audit.record(s.actor(),w,e,"WITHDRAW","quotes",Long.toString(quote),version+1,"SUCCESS",why);
    }
    @Transactional
    public void revise(long w,long e,long quote,long version) {
        var s=write(w,e); var r=locked(w,e,quote,version);
        if(!Set.of("APPROVED","REJECTED","WITHDRAWN").contains(r.get("status"))) throw new DomainException(409,"Quote cannot be revised");
        Integer booked=db.queryForObject("select count(*) from sales_orders where workspace_id=? and entity_id=? and quote_id=?",Integer.class,w,e,quote);
        if(booked!=null && booked>0) throw new DomainException(409,"Booked quote cannot be revised");
        long old=n(r,"id");
        long rev=db.queryForObject("insert into quote_revisions(workspace_id,entity_id,quote_id,revision,status,business_date,valid_until,currency,terms_id,terms_days,customer_account_version,config_version,created_by,requires_approval) select workspace_id,entity_id,quote_id,revision+1,'DRAFT',business_date,valid_until,currency,terms_id,terms_days,customer_account_version,config_version,?,requires_approval from quote_revisions where workspace_id=? and entity_id=? and id=? returning id",Long.class,s.actor().id(),w,e,old);
        db.update("update quote_revisions n set billing_address_id=o.billing_address_id,billing_address_version=o.billing_address_version,shipping_address_id=o.shipping_address_id,shipping_address_version=o.shipping_address_version from quote_revisions o where n.workspace_id=? and n.entity_id=? and n.id=? and o.workspace_id=n.workspace_id and o.entity_id=n.entity_id and o.id=?",w,e,rev,old);
        db.update("insert into quote_lines(workspace_id,entity_id,revision_id,line_key,ordinal,product_id,product_version,product_setting_version,product_sku,product_name,product_category,product_line,uom_id,uom_version,uom_code,tracking,quantity_scale,quantity,unit_price,price_book_id,price_book_version,price_book_line_id,price_book_line_version,list_price,manual_override,discount_pct,discount_amount,subtotal,discount,net,tax,gross,tax_version_id,tax_version_version,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id,deferred_account_id,service_config_version,service_product_version_id) select workspace_id,entity_id,?,line_key,ordinal,product_id,product_version,product_setting_version,product_sku,product_name,product_category,product_line,uom_id,uom_version,uom_code,tracking,quantity_scale,quantity,unit_price,price_book_id,price_book_version,price_book_line_id,price_book_line_version,list_price,manual_override,discount_pct,discount_amount,subtotal,discount,net,tax,gross,tax_version_id,tax_version_version,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id,deferred_account_id,service_config_version,service_product_version_id from quote_lines where workspace_id=? and entity_id=? and revision_id=? and active",rev,w,e,old);
        obligations.copyQuoteTargets(s,old,rev);
        db.update("insert into quote_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?)",w,e,rev,s.actor().id());
        bump(w,e,quote); event(s,w,e,rev,"REVISED","",""); audit.record(s.actor(),w,e,"REVISE","quotes",Long.toString(quote),version+1,"SUCCESS","new revision");
    }
}
