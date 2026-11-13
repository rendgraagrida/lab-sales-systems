package dev.labtrial.salestools.procurement;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.finance.SourceAmounts;
import dev.labtrial.salestools.finance.PostingService;
import dev.labtrial.salestools.finance.Ledger;
import dev.labtrial.salestools.inventory.InventoryService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcurementService {
    public record RequestLineInput(String key, long productId, long productVersion, long settingVersion,
                                   long uomVersion, BigDecimal quantity) {}
    public record RequestInput(LocalDate businessDate, String purpose, List<RequestLineInput> lines) {}
    public record RequestLine(long id, String key, long productId, String sku, String name,
                              BigDecimal quantity, int quantityScale, long productVersion,
                              long settingVersion, long uomVersion) {
        public String productRef() { return productId+":"+productVersion+":"+settingVersion+":"+uomVersion; }
    }
    public record Request(long id, String number, String status, long revision, long version,
                          LocalDate businessDate, String purpose, long revisionId, long createdBy) {}
    public record ProductChoice(long id, String sku, String name, long version, long settingVersion,
                                long uomVersion, int quantityScale) {}
    public record OrderLineInput(String key, long requestLineId, Long previousLineId, BigDecimal quantity,
                                 BigDecimal unitCost, BigDecimal discountPct, BigDecimal discountAmount,
                                 long taxVersionId, long taxVersionVersion, String serviceProfile) {}
    public record OrderInput(long supplierAccountId, long supplierAccountVersion, long configVersion,
                             LocalDate businessDate, List<OrderLineInput> lines) {}
    public record ReceiptLineInput(String key,long orderLineId,BigDecimal quantity,Long warehouseId,
                                   Long warehouseVersion,List<InventoryService.TrackingInput> trackingRows) {}
    public record ReceiptInput(long orderId,long orderVersion,String kind,LocalDate postingDate,List<ReceiptLineInput> lines) {}
    public record ReceiptResult(long receiptId,Long journalId,boolean replayed) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final PostingService posting;
    private final InventoryService inventory;

    public ProcurementService(JdbcTemplate db, AccessControl access, AuditLog audit, PostingService posting, InventoryService inventory) {
        this.db = db; this.access = access; this.audit = audit; this.posting=posting; this.inventory=inventory;
    }
    private AccessControl.Scope read(long w,long e) { return access.requireContext(w,e,false,"OPERATIONS","FINANCE"); }
    private AccessControl.Scope write(long w,long e) { return access.requireContext(w,e,true,"OPERATIONS","FINANCE"); }
    private long id(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }
    private static String text(String value,int max,String field) {
        if (value==null || value.isBlank() || value.trim().length()>max) throw new DomainException(400,"Required or too long",field);
        return value.trim();
    }
    private String number(long w,long e,String type) {
        db.update("insert into document_sequences(workspace_id,entity_id,document_type,next_value) values (?,?,?,1) on conflict (workspace_id,entity_id,document_type) do nothing",w,e,type);
        long n=id("update document_sequences set next_value=next_value+1,version=version+1 where workspace_id=? and entity_id=? and document_type=? returning next_value-1",w,e,type);
        return type+"-"+n;
    }
    private static Request mapRequest(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Request(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getLong(5),r.getObject(6,LocalDate.class),r.getString(7),r.getLong(8),r.getLong(9));
    }
    private Request request(long w,long e,long id,boolean lock) {
        var rows=db.query("select p.id,p.number,p.status,p.revision,p.version,p.business_date,p.purpose,r.id,p.created_by from purchase_requests p join purchase_request_revisions r on r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.request_id=p.id and r.revision=p.revision where p.workspace_id=? and p.entity_id=? and p.id=?"+(lock?" for update of p,r":""),ProcurementService::mapRequest,w,e,id);
        if(rows.isEmpty()) throw new DomainException(404,"Purchase request not found");
        return rows.get(0);
    }
    public Request getRequest(long w,long e,long id) { read(w,e); return request(w,e,id,false); }
    public List<Request> requests(long w,long e,String search) {
        read(w,e); String q=search==null?"":search.trim(); if(q.length()>200) throw new DomainException(400,"Search is too long","q");
        return db.query("select p.id,p.number,p.status,p.revision,p.version,p.business_date,p.purpose,r.id,p.created_by from purchase_requests p join purchase_request_revisions r on r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.request_id=p.id and r.revision=p.revision where p.workspace_id=? and p.entity_id=? and (position(lower(?) in lower(p.number))>0 or position(lower(?) in lower(p.purpose))>0) order by p.id desc",ProcurementService::mapRequest,w,e,q,q);
    }
    public List<RequestLine> requestLines(long w,long e,long requestId) {
        read(w,e); Request p=request(w,e,requestId,false);
        return db.query("select l.id,l.line_key,l.product_id,l.product_sku,l.product_name,l.quantity,l.quantity_scale,l.product_version,l.setting_version,l.uom_version from purchase_request_lines l where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active order by l.id",(r,n)->new RequestLine(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5),r.getBigDecimal(6),r.getInt(7),r.getLong(8),r.getLong(9),r.getLong(10)),w,e,p.revisionId());
    }
    public List<ProductChoice> products(long w,long e) {
        read(w,e);
        return db.query("select p.id,p.sku,p.name,p.version,s.version,u.version,u.quantity_scale from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id and u.active where p.workspace_id=? and p.active order by p.sku,p.id",(r,n)->new ProductChoice(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getLong(5),r.getLong(6),r.getInt(7)),e,w);
    }
    private void contributor(long w,long e,long revision,long actor) {
        db.update("insert into purchase_request_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,revision,actor);
    }
    @Transactional
    public long saveRequest(long w,long e,Long requestId,Long expectedVersion,RequestInput input) {
        var scope=write(w,e);
        if(input==null || input.businessDate()==null) throw new DomainException(400,"Business date required","businessDate");
        String purpose=text(input.purpose(),500,"purpose");
        if(input.lines()==null || input.lines().isEmpty() || input.lines().size()>100) throw new DomainException(400,"One to 100 lines required","productRef");
        Request old=requestId==null?null:request(w,e,requestId,true);
        if(old==null && expectedVersion!=null) throw new DomainException(400,"Unexpected version");
        if(old!=null && (expectedVersion==null || expectedVersion.longValue()!=old.version())) throw new DomainException(409,"Purchase request changed");
        if(old!=null && !"DRAFT".equals(old.status())) throw new DomainException(409,"Only a draft can be edited");
        Set<String> keys=new HashSet<>();
        for(RequestLineInput line:input.lines()) {
            if(line==null) throw new DomainException(400,"Invalid line");
            String key=text(line.key(),80,"lineKey"); if(!keys.add(key)) throw new DomainException(400,"Duplicate line key","lineKey");
            var product=db.query("select p.version,s.version,u.version,u.quantity_scale,p.tracking from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id and u.active where p.workspace_id=? and p.id=? and p.active for update of p,s,u",(r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getInt(4),r.getString(5)},e,w,line.productId());
            if(product.size()!=1) throw new DomainException(400,"Product must be active and assigned","productRef");
            Object[] p=product.get(0);
            if((long)p[0]!=line.productVersion() || (long)p[1]!=line.settingVersion() || (long)p[2]!=line.uomVersion()) throw new DomainException(409,"Product reference changed");
            BigDecimal q=line.quantity(); int scale=(int)p[3];
            if(q==null || q.signum()<=0 || q.scale()>6 || q.scale() < -18 || (long)q.precision()-q.scale()>12 || q.stripTrailingZeros().scale()>scale || ("SERIAL".equals(p[4]) && q.stripTrailingZeros().scale()>0)) throw new DomainException(400,"Invalid quantity for unit","quantity");
        }
        long result,revision;
        if(old==null) {
            result=id("insert into purchase_requests(workspace_id,entity_id,number,status,business_date,purpose,created_by) values (?,?,?,'DRAFT',?,?,?) returning id",w,e,number(w,e,"PR"),input.businessDate(),purpose,scope.actor().id());
            revision=id("insert into purchase_request_revisions(workspace_id,entity_id,request_id,revision,status,business_date,purpose) values (?,?,?,1,'DRAFT',?,?) returning id",w,e,result,input.businessDate(),purpose);
        } else {
            result=old.id(); revision=old.revisionId();
            db.update("update purchase_requests set business_date=?,purpose=?,version=version+1 where workspace_id=? and entity_id=? and id=?",input.businessDate(),purpose,w,e,result);
            db.update("update purchase_request_revisions set business_date=?,purpose=? where workspace_id=? and entity_id=? and id=?",input.businessDate(),purpose,w,e,revision);
            db.update("update purchase_request_lines set active=false where workspace_id=? and entity_id=? and revision_id=? and active",w,e,revision);
        }
        contributor(w,e,revision,scope.actor().id());
        for(RequestLineInput line:input.lines()) {
            var unit=db.queryForMap("select u.id,u.quantity_scale,u.code,p.sku,p.name,p.category,p.tracking from products p join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id where p.workspace_id=? and p.id=?",w,line.productId());
            db.update("insert into purchase_request_lines(workspace_id,entity_id,revision_id,line_key,product_id,quantity,quantity_scale,product_version,setting_version,uom_id,uom_version,product_sku,product_name,product_category,uom_code,tracking,active) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true) on conflict (workspace_id,entity_id,revision_id,line_key) do update set product_id=excluded.product_id,quantity=excluded.quantity,quantity_scale=excluded.quantity_scale,product_version=excluded.product_version,setting_version=excluded.setting_version,uom_id=excluded.uom_id,uom_version=excluded.uom_version,product_sku=excluded.product_sku,product_name=excluded.product_name,product_category=excluded.product_category,uom_code=excluded.uom_code,tracking=excluded.tracking,active=true",w,e,revision,line.key().trim(),line.productId(),line.quantity(),unit.get("quantity_scale"),line.productVersion(),line.settingVersion(),unit.get("id"),line.uomVersion(),unit.get("sku"),unit.get("name"),unit.get("category"),unit.get("code"),unit.get("tracking"));
        }
        audit.record(scope.actor(),w,e,old==null?"CREATE":"UPDATE","purchase_requests",Long.toString(result),old==null?1:old.version()+1,"SUCCESS","request draft");
        return result;
    }
    private String requestHash(long w,long e,Request p) {
        StringBuilder b=new StringBuilder().append(p.businessDate()).append(':').append(p.purpose().length()).append(':').append(p.purpose());
        db.query("select line_key,product_id,quantity,quantity_scale,product_version,setting_version,uom_id,uom_version,product_sku,product_name,product_category,uom_code,tracking from purchase_request_lines where workspace_id=? and entity_id=? and revision_id=? and active order by line_key",r->{for(int i=1;i<=13;i++){String value=r.getString(i); b.append(value.length()).append(':').append(value);} b.append('\n');},w,e,p.revisionId());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private void validateRequestReferences(long w,long e,long revisionId) {
        Integer stale=db.queryForObject("select count(*) from purchase_request_lines l left join products p on p.workspace_id=l.workspace_id and p.id=l.product_id left join product_entity_settings s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.product_id=l.product_id left join uoms u on u.workspace_id=l.workspace_id and u.id=l.uom_id where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active and (p.id is null or not p.active or p.version<>l.product_version or p.uom_id<>l.uom_id or s.id is null or not s.active or s.version<>l.setting_version or u.id is null or not u.active or u.version<>l.uom_version)",Integer.class,w,e,revisionId);
        if(stale!=null && stale>0) throw new DomainException(409,"Product or unit reference changed");
    }
    @Transactional
    public void submitRequest(long w,long e,long requestId,long version) {
        var s=write(w,e); Request p=request(w,e,requestId,true);
        if(p.version()!=version) throw new DomainException(409,"Purchase request changed");
        if(!"DRAFT".equals(p.status())) throw new DomainException(409,"Request is not a draft");
        Integer count=db.queryForObject("select count(*) from purchase_request_lines where workspace_id=? and entity_id=? and revision_id=? and active",Integer.class,w,e,p.revisionId());
        if(count==null || count==0) throw new DomainException(400,"At least one line required");
        validateRequestReferences(w,e,p.revisionId());
        contributor(w,e,p.revisionId(),s.actor().id());
        db.update("update purchase_request_revisions set status='SUBMITTED',submitted_by=?,submitted_at=now(),payload_hash=? where workspace_id=? and entity_id=? and id=?",s.actor().id(),requestHash(w,e,p),w,e,p.revisionId());
        db.update("update purchase_requests set status='SUBMITTED',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,requestId);
        audit.record(s.actor(),w,e,"SUBMIT","purchase_requests",Long.toString(requestId),version+1,"SUCCESS","request revision");
    }
    @Transactional
    public void decideRequest(long w,long e,long requestId,long version,boolean approve,String reason) {
        var s=access.requireContext(w,e,true,"FINANCE"); Request p=request(w,e,requestId,true);
        if(p.version()!=version) throw new DomainException(409,"Purchase request changed");
        if(!"SUBMITTED".equals(p.status())) throw new DomainException(409,"Request is not submitted");
        Integer maker=db.queryForObject("select count(*) from purchase_request_contributors where workspace_id=? and entity_id=? and revision_id=? and user_id=?",Integer.class,w,e,p.revisionId(),s.actor().id());
        if(p.createdBy()==s.actor().id() || (maker!=null && maker>0)) throw new DomainException(403,"A revision contributor cannot decide it");
        String why=reason==null?"":reason.trim(); if(why.length()>500 || (!approve && why.isBlank())) throw new DomainException(400,"Rejection reason required or too long","reason");
        if(approve) {
            validateRequestReferences(w,e,p.revisionId());
            Integer conflict=db.queryForObject("""
                with latest as (
                  select distinct on (order_id) id,order_id from purchase_order_revisions
                   where workspace_id=? and entity_id=? and status='APPROVED' order by order_id,revision desc
                ), commitments as (
                  select rl.line_key,rl.product_id,ol.quantity as quantity
                    from latest v join purchase_order_lines ol on ol.revision_id=v.id and ol.active
                    join purchase_request_lines rl on rl.id=ol.request_line_id
                    join purchase_request_revisions rr on rr.id=rl.revision_id
                   where rr.request_id=?
                  union all
                  select rl.line_key,rl.product_id,receipt_line.slice_end-receipt_line.slice_start
                    from procurement_receipts receipt join procurement_receipt_lines receipt_line on receipt_line.receipt_id=receipt.id and receipt_line.active
                    join purchase_order_lines ol on ol.id=receipt_line.order_line_id
                    join purchase_order_revisions ov on ov.id=ol.revision_id
                    join latest v on v.order_id=ov.order_id and v.id<>ov.id
                    join purchase_request_lines rl on rl.id=ol.request_line_id
                    join purchase_request_revisions rr on rr.id=rl.revision_id
                   where receipt.workspace_id=? and receipt.entity_id=? and receipt.status='POSTED' and rr.request_id=?
                )
                select count(*) from (
                  select line_key,product_id,sum(quantity) quantity from commitments group by line_key,product_id
                ) c left join purchase_request_lines n on n.workspace_id=? and n.entity_id=? and n.revision_id=?
                    and n.active and n.line_key=c.line_key and n.product_id=c.product_id
                 where n.id is null or n.quantity<c.quantity
                """,Integer.class,w,e,requestId,w,e,requestId,w,e,p.revisionId());
            if(conflict!=null && conflict>0) throw new DomainException(409,"Request revision would reduce committed quantities");
        }
        String status=approve?"APPROVED":"REJECTED";
        db.update("update purchase_request_revisions set status=?,decided_by=?,decided_at=now(),decision_reason=? where workspace_id=? and entity_id=? and id=?",status,s.actor().id(),why,w,e,p.revisionId());
        db.update("update purchase_requests set status=?,version=version+1 where workspace_id=? and entity_id=? and id=?",status,w,e,requestId);
        audit.record(s.actor(),w,e,status,"purchase_requests",Long.toString(requestId),version+1,"SUCCESS",why);
    }
    @Transactional
    public void reviseRequest(long w,long e,long requestId,long version) {
        var s=write(w,e); Request p=request(w,e,requestId,true);
        if(p.version()!=version) throw new DomainException(409,"Purchase request changed");
        if(!"APPROVED".equals(p.status()) && !"REJECTED".equals(p.status())) throw new DomainException(409,"Request cannot be revised");
        long revision=id("insert into purchase_request_revisions(workspace_id,entity_id,request_id,revision,status,business_date,purpose) values (?,?,?,?,'DRAFT',?,?) returning id",w,e,requestId,p.revision()+1,p.businessDate(),p.purpose());
        db.update("insert into purchase_request_lines(workspace_id,entity_id,revision_id,line_key,product_id,quantity,quantity_scale,product_version,setting_version,uom_id,uom_version,product_sku,product_name,product_category,uom_code,tracking) select workspace_id,entity_id,?,line_key,product_id,quantity,quantity_scale,product_version,setting_version,uom_id,uom_version,product_sku,product_name,product_category,uom_code,tracking from purchase_request_lines where workspace_id=? and entity_id=? and revision_id=? and active",revision,w,e,p.revisionId());
        contributor(w,e,revision,s.actor().id());
        db.update("update purchase_requests set status='DRAFT',revision=revision+1,version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,requestId);
        audit.record(s.actor(),w,e,"REVISE","purchase_requests",Long.toString(requestId),version+1,"SUCCESS","new request revision");
    }
    private static long num(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static boolean same(BigDecimal left,BigDecimal right) { return left==null?right==null:right!=null && left.compareTo(right)==0; }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);
        if(rows.size()!=1) throw new DomainException(409,"Source or configuration changed");
        return rows.get(0);
    }
    public List<Map<String,Object>> orders(long w,long e,String q) {
        read(w,e); String search=q==null?"":q.trim();
        if(search.length()>200) throw new DomainException(400,"Search is too long","q");
        return db.queryForList("select id,number,status,revision,version from purchase_orders where workspace_id=? and entity_id=? and position(lower(?) in lower(number))>0 order by id desc",w,e,search);
    }
    public Map<String,Object> order(long w,long e,long orderId) {
        read(w,e);
        return one("select p.*,r.id as revision_id,r.currency,r.terms_id,r.terms_days,r.config_version,r.supplier_account_version,r.business_date from purchase_orders p join purchase_order_revisions r on r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.order_id=p.id and r.revision=p.revision where p.workspace_id=? and p.entity_id=? and p.id=?",w,e,orderId);
    }
    public List<Map<String,Object>> orderLines(long w,long e,long orderId) {
        Map<String,Object> row=order(w,e,orderId);
        return db.queryForList("select l.*,r.request_id from purchase_order_lines l join purchase_request_lines q on q.workspace_id=l.workspace_id and q.entity_id=l.entity_id and q.id=l.request_line_id join purchase_request_revisions r on r.workspace_id=q.workspace_id and r.entity_id=q.entity_id and r.id=q.revision_id where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active order by l.id",w,e,num(row,"revision_id"));
    }
    public List<Map<String,Object>> supplierChoices(long w,long e) {
        read(w,e);
        return db.queryForList("select a.id,a.version,s.code,s.name from supplier_entity_accounts a join suppliers s on s.workspace_id=a.workspace_id and s.id=a.supplier_id where a.workspace_id=? and a.entity_id=? and a.active and s.active order by s.code",w,e);
    }
    public List<Map<String,Object>> approvedRequestLines(long w,long e) {
        read(w,e);
        return db.queryForList("select l.id,l.line_key,l.product_sku,l.product_name,l.quantity,p.number from purchase_request_lines l join purchase_request_revisions r on r.id=l.revision_id and r.status='APPROVED' join purchase_requests p on p.id=r.request_id and p.status='APPROVED' and p.revision=r.revision where l.workspace_id=? and l.entity_id=? and l.active order by p.number,l.id",w,e);
    }
    public List<Map<String,Object>> taxChoices(long w,long e) {
        read(w,e);
        return db.queryForList("select v.id,v.version,v.rate_pct,v.valid_from,v.valid_to,t.code from tax_versions v join tax_codes t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.tax_code_id where v.workspace_id=? and v.entity_id=? and t.active and not v.price_inclusive order by t.code,v.valid_from",w,e);
    }
    public Long configVersion(long w,long e) {
        read(w,e);
        var values=db.query("select version from procurement_posting_config where workspace_id=? and entity_id=?",(r,n)->r.getLong(1),w,e);
        return values.isEmpty()?null:values.get(0);
    }
    public List<Map<String,Object>> receiptSources(long w,long e) {
        read(w,e);
        return db.queryForList("select l.id,l.line_key,l.product_sku,l.product_name,l.quantity,l.tracking,p.id order_id,p.number,p.version from purchase_order_lines l join purchase_order_revisions r on r.id=l.revision_id and r.status='APPROVED' join purchase_orders p on p.id=r.order_id and p.status='APPROVED' and p.revision=r.revision where l.workspace_id=? and l.entity_id=? and l.active order by p.number,l.id",w,e);
    }
    @Transactional
    public long saveOrder(long w,long e,Long orderId,Long expectedVersion,OrderInput input) {
        var scope=write(w,e);
        if(input==null || input.businessDate()==null || input.lines()==null || input.lines().isEmpty() || input.lines().size()>100) throw new DomainException(400,"Order date and one to 100 order lines required");
        Map<String,Object> old=orderId==null?null:one("select p.*,r.id revision_id from purchase_orders p join purchase_order_revisions r on r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.order_id=p.id and r.revision=p.revision where p.workspace_id=? and p.entity_id=? and p.id=? for update of p,r",w,e,orderId);
        if(old==null && expectedVersion!=null) throw new DomainException(400,"Unexpected version");
        if(old!=null && (expectedVersion==null || num(old,"version")!=expectedVersion)) throw new DomainException(409,"Purchase order changed");
        if(old!=null && !"DRAFT".equals(old.get("status"))) throw new DomainException(409,"Only draft orders can be edited");
        if(old!=null && num(old,"supplier_account_id")!=input.supplierAccountId()) throw new DomainException(409,"Supplier cannot be changed on an order");
        Map<String,Object> supplier=one("select a.id,a.version,a.terms_id,t.days,t.version terms_version from supplier_entity_accounts a join suppliers s on s.workspace_id=a.workspace_id and s.id=a.supplier_id left join payment_terms t on t.workspace_id=a.workspace_id and t.entity_id=a.entity_id and t.id=a.terms_id and t.active where a.workspace_id=? and a.entity_id=? and a.id=? and a.active and s.active for update of a,s",w,e,input.supplierAccountId());
        if(num(supplier,"version")!=input.supplierAccountVersion()) throw new DomainException(409,"Supplier account changed");
        if(supplier.get("terms_id")!=null && supplier.get("terms_version")==null) throw new DomainException(409,"Payment terms inactive");
        Map<String,Object> config=one("select c.*,e.base_currency from procurement_posting_config c join legal_entities e on e.workspace_id=c.workspace_id and e.id=c.entity_id where c.workspace_id=? and c.entity_id=? for update of c",w,e);
        if(num(config,"version")!=input.configVersion()) throw new DomainException(409,"Posting configuration changed");
        for(String column:List.of("inventory_account_id","ap_account_id","grni_account_id","service_expense_account_id")) {
            Integer active=db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=? and active",Integer.class,w,e,num(config,column));
            if(active==null || active!=1) throw new DomainException(409,"Posting account is inactive");
        }
        Set<String> keys=new HashSet<>();
        for(OrderLineInput line:input.lines()) if(line==null || !keys.add(text(line.key(),80,"lineKey"))) throw new DomainException(400,"Invalid or duplicate order line");
        Map<String,Map<String,Object>> oldLines=new LinkedHashMap<>();
        if(old!=null) for(var existing:db.queryForList("select * from purchase_order_lines where workspace_id=? and entity_id=? and revision_id=? and active",w,e,num(old,"revision_id"))) oldLines.put((String)existing.get("line_key"),existing);
        long result,rev;
        if(old==null) {
            result=id("insert into purchase_orders(workspace_id,entity_id,number,supplier_account_id,status,created_by) values (?,?,?,?,'DRAFT',?) returning id",w,e,number(w,e,"PO"),input.supplierAccountId(),scope.actor().id());
            rev=id("insert into purchase_order_revisions(workspace_id,entity_id,order_id,revision,status,currency,terms_id,terms_days,config_version,inventory_account_id,ap_account_id,grni_account_id,expense_account_id,supplier_account_version,terms_version,business_date) values (?,?,?,1,'DRAFT',?,?,?,?,?,?,?,?,?,?,?) returning id",w,e,result,config.get("base_currency"),supplier.get("terms_id"),supplier.get("days")==null?0:supplier.get("days"),input.configVersion(),config.get("inventory_account_id"),config.get("ap_account_id"),config.get("grni_account_id"),config.get("service_expense_account_id"),input.supplierAccountVersion(),supplier.get("terms_version"),input.businessDate());
        } else {
            result=orderId; rev=num(old,"revision_id");
            db.update("update purchase_orders set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,result);
            db.update("update purchase_order_revisions set currency=?,terms_id=?,terms_days=?,config_version=?,inventory_account_id=?,ap_account_id=?,grni_account_id=?,expense_account_id=?,supplier_account_version=?,terms_version=?,business_date=? where workspace_id=? and entity_id=? and id=?",config.get("base_currency"),supplier.get("terms_id"),supplier.get("days")==null?0:supplier.get("days"),input.configVersion(),config.get("inventory_account_id"),config.get("ap_account_id"),config.get("grni_account_id"),config.get("service_expense_account_id"),input.supplierAccountVersion(),supplier.get("terms_version"),input.businessDate(),w,e,rev);
            db.update("update purchase_order_lines set active=false where workspace_id=? and entity_id=? and revision_id=? and active",w,e,rev);
        }
        db.update("insert into purchase_order_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,rev,scope.actor().id());
        for(OrderLineInput line:input.lines()) {
            Map<String,Object> source=one("select l.*,r.business_date from purchase_request_lines l join purchase_request_revisions r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.revision_id where l.workspace_id=? and l.entity_id=? and l.id=? and l.active and r.status='APPROVED'",w,e,line.requestLineId());
            boolean service=List.of("SERVICE_PLAN","IMPLEMENTATION_SERVICE").contains(source.get("product_category"));
            if(service && (line.serviceProfile()==null || !List.of("OPERATIONAL","PREPAID","MULTIPERIOD").contains(line.serviceProfile()))) throw new DomainException(400,"Choose a service profile","serviceProfile");
            if(!service && line.serviceProfile()!=null && !line.serviceProfile().isBlank()) throw new DomainException(400,"Goods cannot use a service profile","serviceProfile");
            if(service && !"NONE".equals(source.get("tracking"))) throw new DomainException(400,"Services cannot use physical tracking");
            var taxProfile=db.query("select price_inclusive from tax_versions where workspace_id=? and entity_id=? and id=? and version=?",(r,n)->r.getBoolean(1),w,e,line.taxVersionId(),line.taxVersionVersion());
            if(taxProfile.size()==1 && taxProfile.get(0)) throw new DomainException(400,"Inclusive tax is blocked; choose an exclusive recoverable tax","taxRef");
            Map<String,Object> tax=one("select v.*,exists(select 1 from payment_posting_config p where p.workspace_id=v.workspace_id and p.entity_id=v.entity_id and p.bank_account_id=v.input_account_id) as bank_role_conflict from tax_versions v join tax_codes t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.tax_code_id join chart_accounts a on a.workspace_id=v.workspace_id and a.entity_id=v.entity_id and a.id=v.input_account_id where v.workspace_id=? and v.entity_id=? and v.id=? and v.version=? and t.active and a.active and a.account_type='ASSET' and not v.price_inclusive and ?::date>=v.valid_from and (v.valid_to is null or ?::date<v.valid_to)",w,e,line.taxVersionId(),line.taxVersionVersion(),input.businessDate(),input.businessDate());
            if(num(tax,"input_account_id")==num(config,"inventory_account_id") || Boolean.TRUE.equals(tax.get("bank_role_conflict")))
                throw new DomainException(400,"Input Tax must differ from Inventory and Bank","taxRef");
            SourceAmounts.Snapshot amount;
            var prior=oldLines.get(line.key().trim());
            if(prior!=null && num(prior,"request_line_id")==line.requestLineId()
                    && Objects.equals(prior.get("previous_line_id"),line.previousLineId())
                    && same((BigDecimal)prior.get("quantity"),line.quantity()) && same((BigDecimal)prior.get("unit_cost"),line.unitCost())
                    && same((BigDecimal)prior.get("discount_pct"),line.discountPct()) && same((BigDecimal)prior.get("discount_amount"),line.discountAmount())
                    && num(prior,"tax_version_id")==line.taxVersionId() && num(prior,"tax_version_version")==line.taxVersionVersion()
                    && Objects.equals(prior.get("service_profile"),line.serviceProfile())) {
                amount=new SourceAmounts.Snapshot((BigDecimal)prior.get("quantity"),(BigDecimal)prior.get("unit_cost"),(BigDecimal)prior.get("subtotal"),(BigDecimal)prior.get("discount"),(BigDecimal)prior.get("net"),(BigDecimal)prior.get("tax"),(BigDecimal)prior.get("gross"));
            } else {
                amount=SourceAmounts.price(line.quantity(),line.unitCost(),line.discountPct(),line.discountAmount(),(BigDecimal)tax.get("rate_pct"),((Number)source.get("quantity_scale")).intValue());
            }
            if("SERIAL".equals(source.get("tracking")) && line.quantity().stripTrailingZeros().scale()>0) throw new DomainException(400,"Serial quantity must be integral");
            db.update("insert into purchase_order_lines(workspace_id,entity_id,revision_id,request_line_id,line_key,previous_line_id,product_id,uom_id,tracking,quantity,quantity_scale,unit_cost,subtotal,discount,net,tax,gross,tax_version_id,tax_rate_pct,input_account_id,expense_account_id,discount_pct,discount_amount,product_category,product_sku,product_name,uom_code,product_version,setting_version,uom_version,tax_version_version,service_profile,active) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true) on conflict (workspace_id,entity_id,revision_id,line_key) do update set request_line_id=excluded.request_line_id,previous_line_id=excluded.previous_line_id,product_id=excluded.product_id,uom_id=excluded.uom_id,tracking=excluded.tracking,quantity=excluded.quantity,quantity_scale=excluded.quantity_scale,unit_cost=excluded.unit_cost,subtotal=excluded.subtotal,discount=excluded.discount,net=excluded.net,tax=excluded.tax,gross=excluded.gross,tax_version_id=excluded.tax_version_id,tax_rate_pct=excluded.tax_rate_pct,input_account_id=excluded.input_account_id,expense_account_id=excluded.expense_account_id,discount_pct=excluded.discount_pct,discount_amount=excluded.discount_amount,product_category=excluded.product_category,product_sku=excluded.product_sku,product_name=excluded.product_name,uom_code=excluded.uom_code,product_version=excluded.product_version,setting_version=excluded.setting_version,uom_version=excluded.uom_version,tax_version_version=excluded.tax_version_version,service_profile=excluded.service_profile,active=true",w,e,rev,line.requestLineId(),line.key().trim(),line.previousLineId(),source.get("product_id"),source.get("uom_id"),source.get("tracking"),amount.quantity(),source.get("quantity_scale"),amount.unitCost(),amount.subtotal(),amount.discount(),amount.net(),amount.tax(),amount.gross(),line.taxVersionId(),tax.get("rate_pct"),tax.get("input_account_id"),config.get("service_expense_account_id"),line.discountPct(),line.discountAmount(),source.get("product_category"),source.get("product_sku"),source.get("product_name"),source.get("uom_code"),source.get("product_version"),source.get("setting_version"),source.get("uom_version"),line.taxVersionVersion(),service?line.serviceProfile():null);
        }
        audit.record(scope.actor(),w,e,old==null?"CREATE":"UPDATE","purchase_orders",Long.toString(result),old==null?1:num(old,"version")+1,"SUCCESS","order draft");
        return result;
    }
    private Map<String,Object> lockedOrder(long w,long e,long orderId,long version) {
        var row=one("select p.*,r.id revision_id,r.config_version,r.supplier_account_version,r.terms_id,r.terms_version from purchase_orders p join purchase_order_revisions r on r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.order_id=p.id and r.revision=p.revision where p.workspace_id=? and p.entity_id=? and p.id=? for update of p,r",w,e,orderId);
        if(num(row,"version")!=version) throw new DomainException(409,"Purchase order changed");
        return row;
    }
    private void validateOrder(long w,long e,long orderId,Map<String,Object> row) {
        long rev=num(row,"revision_id");
        Integer unsupported=db.queryForObject("select count(*) from purchase_order_lines where workspace_id=? and entity_id=? and revision_id=? and active and (service_profile in ('PREPAID','MULTIPERIOD') or (service_profile is not null and uom_code='TERM'))",Integer.class,w,e,rev);
        if(unsupported!=null && unsupported>0) throw new DomainException(400,"Prepaid and multi-period services are blocked by policy","serviceProfile");
        Integer count=db.queryForObject("select count(*) from purchase_order_lines where workspace_id=? and entity_id=? and revision_id=? and active",Integer.class,w,e,rev);
        if(count==null || count==0) throw new DomainException(400,"At least one order line required");
        Integer brokenLineage=db.queryForObject("""
            select count(*) from purchase_order_lines n
            join purchase_order_revisions old_revision on old_revision.workspace_id=n.workspace_id and old_revision.entity_id=n.entity_id
              and old_revision.order_id=? and old_revision.status='APPROVED'
              and old_revision.revision=(select max(r.revision) from purchase_order_revisions r where r.workspace_id=n.workspace_id and r.entity_id=n.entity_id and r.order_id=? and r.status='APPROVED')
            join purchase_order_lines old_line on old_line.workspace_id=n.workspace_id and old_line.entity_id=n.entity_id
              and old_line.revision_id=old_revision.id and old_line.line_key=n.line_key and old_line.active
            where n.workspace_id=? and n.entity_id=? and n.revision_id=? and n.active
              and (n.previous_line_id is distinct from old_line.id or n.request_line_id<>old_line.request_line_id)
            """,Integer.class,orderId,orderId,w,e,rev);
        if(brokenLineage!=null && brokenLineage>0) throw new DomainException(409,"Amended lines must retain their approved source lineage");
        Integer stale=db.queryForObject("""
            select count(*) from purchase_order_lines l
            join purchase_order_revisions pr on pr.workspace_id=l.workspace_id and pr.entity_id=l.entity_id and pr.id=l.revision_id
            left join products p on p.workspace_id=l.workspace_id and p.id=l.product_id
            left join product_entity_settings s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.product_id=l.product_id
            left join uoms u on u.workspace_id=l.workspace_id and u.id=l.uom_id
            left join tax_versions t on t.workspace_id=l.workspace_id and t.entity_id=l.entity_id and t.id=l.tax_version_id
            left join tax_codes tc on tc.workspace_id=t.workspace_id and tc.entity_id=t.entity_id and tc.id=t.tax_code_id
            where l.workspace_id=? and l.entity_id=? and l.revision_id=? and l.active
            and (p.id is null or not p.active or p.version<>l.product_version or p.uom_id<>l.uom_id
              or s.id is null or not s.active or s.version<>l.setting_version
              or u.id is null or not u.active or u.version<>l.uom_version
              or t.id is null or t.version<>l.tax_version_version or t.price_inclusive or tc.id is null or not tc.active
              or pr.business_date<t.valid_from or (t.valid_to is not null and pr.business_date>=t.valid_to))
            """,Integer.class,w,e,rev);
        if(stale!=null && stale>0) throw new DomainException(409,"Order source references changed");
        Integer config=db.queryForObject("select count(*) from procurement_posting_config c join supplier_entity_accounts a on a.workspace_id=c.workspace_id and a.entity_id=c.entity_id join suppliers s on s.workspace_id=a.workspace_id and s.id=a.supplier_id join chart_accounts i on i.workspace_id=c.workspace_id and i.entity_id=c.entity_id and i.id=c.inventory_account_id and i.active and i.account_type='ASSET' join chart_accounts ap on ap.workspace_id=c.workspace_id and ap.entity_id=c.entity_id and ap.id=c.ap_account_id and ap.active and ap.account_type='LIABILITY' join chart_accounts g on g.workspace_id=c.workspace_id and g.entity_id=c.entity_id and g.id=c.grni_account_id and g.active and g.account_type='LIABILITY' join chart_accounts ex on ex.workspace_id=c.workspace_id and ex.entity_id=c.entity_id and ex.id=c.service_expense_account_id and ex.active and ex.account_type='EXPENSE' where c.workspace_id=? and c.entity_id=? and c.version=? and a.id=? and a.version=? and a.active and s.active",Integer.class,w,e,num(row,"config_version"),num(row,"supplier_account_id"),num(row,"supplier_account_version"));
        if(config==null || config!=1) throw new DomainException(409,"Order configuration or supplier changed");
        if(row.get("terms_id")!=null) {
            Integer terms=db.queryForObject("select count(*) from payment_terms where workspace_id=? and entity_id=? and id=? and version=? and active",Integer.class,w,e,row.get("terms_id"),row.get("terms_version"));
            if(terms==null || terms!=1) throw new DomainException(409,"Payment terms changed");
        }
        Integer over=db.queryForObject("""
            with latest_order as (
              select distinct on (order_id) id,order_id from purchase_order_revisions
               where workspace_id=? and entity_id=? and status='APPROVED' order by order_id,revision desc
            ), requested as (
              select rr.request_id,rl.line_key,rl.product_id,sum(ol.quantity) quantity
              from purchase_order_lines ol join purchase_request_lines rl on rl.id=ol.request_line_id
              join purchase_request_revisions rr on rr.id=rl.revision_id
              where ol.workspace_id=? and ol.entity_id=? and ol.revision_id=? and ol.active
              group by rr.request_id,rl.line_key,rl.product_id
            ), posted as (
              select rr.request_id,rl.line_key,rl.product_id,sum(x.slice_end-x.slice_start) quantity
              from procurement_receipts h join procurement_receipt_lines x on x.receipt_id=h.id and x.active
              join purchase_order_lines ol on ol.id=x.order_line_id
              join purchase_request_lines rl on rl.id=ol.request_line_id
              join purchase_request_revisions rr on rr.id=rl.revision_id
              where h.workspace_id=? and h.entity_id=? and h.status='POSTED'
              group by rr.request_id,rl.line_key,rl.product_id
            ), remaining as (
              select rr.request_id,rl.line_key,rl.product_id,sum(ol.quantity-coalesce(x.received,0)) quantity
              from latest_order v join purchase_order_lines ol on ol.revision_id=v.id and ol.active
              join purchase_request_lines rl on rl.id=ol.request_line_id
              join purchase_request_revisions rr on rr.id=rl.revision_id
              left join lateral (select sum(l.slice_end-l.slice_start) received from procurement_receipt_lines l
                join procurement_receipts h on h.id=l.receipt_id and h.status='POSTED'
                where l.order_line_id=ol.id and l.active) x on true
              where v.order_id<>? group by rr.request_id,rl.line_key,rl.product_id
            )
            select count(*) from requested q
              left join purchase_requests p on p.id=q.request_id and p.workspace_id=? and p.entity_id=?
              left join purchase_request_revisions r on r.request_id=p.id and r.revision=p.revision and r.status='APPROVED'
              left join purchase_request_lines l on l.revision_id=r.id and l.active and l.line_key=q.line_key and l.product_id=q.product_id
              left join posted x on x.request_id=q.request_id and x.line_key=q.line_key and x.product_id=q.product_id
              left join remaining y on y.request_id=q.request_id and y.line_key=q.line_key and y.product_id=q.product_id
             where l.id is null or q.quantity+coalesce(x.quantity,0)+coalesce(y.quantity,0)>l.quantity
            """,Integer.class,w,e,w,e,rev,w,e,orderId,w,e);
        if(over!=null && over>0) throw new DomainException(409,"Purchase request capacity exceeded or source revision changed");
    }
    @Transactional
    public void submitOrder(long w,long e,long orderId,long version) {
        var s=write(w,e); var row=lockedOrder(w,e,orderId,version);
        if(!"DRAFT".equals(row.get("status"))) throw new DomainException(409,"Order is not a draft");
        validateOrder(w,e,orderId,row);
        long rev=num(row,"revision_id");
        db.update("insert into purchase_order_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,rev,s.actor().id());
        String payload=db.queryForObject("select jsonb_build_object('revision',to_jsonb(r),'supplier',p.supplier_account_id,'lines',(select coalesce(jsonb_agg(to_jsonb(l) order by l.line_key),'[]'::jsonb) from purchase_order_lines l where l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.revision_id=r.id and l.active))::text from purchase_order_revisions r join purchase_orders p on p.workspace_id=r.workspace_id and p.entity_id=r.entity_id and p.id=r.order_id where r.workspace_id=? and r.entity_id=? and r.id=?",String.class,w,e,rev);
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        db.update("update purchase_order_revisions set status='SUBMITTED',submitted_by=?,submitted_at=now(),payload_hash=? where workspace_id=? and entity_id=? and id=?",s.actor().id(),hash,w,e,rev);
        db.update("update purchase_orders set status='SUBMITTED',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,orderId);
        audit.record(s.actor(),w,e,"SUBMIT","purchase_orders",Long.toString(orderId),version+1,"SUCCESS","order revision");
    }
    @Transactional
    public void decideOrder(long w,long e,long orderId,long version,boolean approve,String reason) {
        var s=access.requireContext(w,e,true,"FINANCE"); var row=lockedOrder(w,e,orderId,version);
        if(!"SUBMITTED".equals(row.get("status"))) throw new DomainException(409,"Order is not submitted");
        long rev=num(row,"revision_id");
        Integer maker=db.queryForObject("select count(*) from purchase_order_contributors where workspace_id=? and entity_id=? and revision_id=? and user_id=?",Integer.class,w,e,rev,s.actor().id());
        if(num(row,"created_by")==s.actor().id() || (maker!=null && maker>0)) throw new DomainException(403,"A revision contributor cannot decide it");
        String why=reason==null?"":reason.trim();
        if(why.length()>500 || (!approve && why.isBlank())) throw new DomainException(400,"Rejection reason required or too long","reason");
        if(approve) validateOrder(w,e,orderId,row);
        String status=approve?"APPROVED":"REJECTED";
        db.update("update purchase_order_revisions set status=?,decided_by=?,decided_at=now(),decision_reason=? where workspace_id=? and entity_id=? and id=?",status,s.actor().id(),why,w,e,rev);
        db.update("update purchase_orders set status=?,version=version+1 where workspace_id=? and entity_id=? and id=?",status,w,e,orderId);
        audit.record(s.actor(),w,e,status,"purchase_orders",Long.toString(orderId),version+1,"SUCCESS",why);
    }
    @Transactional
    public void reviseOrder(long w,long e,long orderId,long version) {
        var scope=write(w,e); var current=lockedOrder(w,e,orderId,version);
        if(!"APPROVED".equals(current.get("status")) && !"REJECTED".equals(current.get("status"))) throw new DomainException(409,"Order cannot be revised");
        var approved=db.queryForList("select * from purchase_order_revisions where workspace_id=? and entity_id=? and order_id=? and status='APPROVED' order by revision desc limit 1",w,e,orderId);
        Map<String,Object> source=approved.isEmpty()?one("select * from purchase_order_revisions where workspace_id=? and entity_id=? and id=?",w,e,num(current,"revision_id")):approved.get(0);
        long newRevision=num(current,"revision")+1;
        long rev=id("insert into purchase_order_revisions(workspace_id,entity_id,order_id,revision,status,currency,terms_id,terms_days,config_version,inventory_account_id,ap_account_id,grni_account_id,expense_account_id,supplier_account_version,terms_version,business_date) select workspace_id,entity_id,order_id,?,'DRAFT',currency,terms_id,terms_days,config_version,inventory_account_id,ap_account_id,grni_account_id,expense_account_id,supplier_account_version,terms_version,business_date from purchase_order_revisions where workspace_id=? and entity_id=? and id=? returning id",newRevision,w,e,num(source,"id"));
        var lines=db.queryForList("select * from purchase_order_lines where workspace_id=? and entity_id=? and revision_id=? and active order by id",w,e,num(source,"id"));
        for(var line:lines) {
            BigDecimal quantity=(BigDecimal)line.get("quantity");
            BigDecimal start=approved.isEmpty()?BigDecimal.ZERO:db.queryForObject("select coalesce(sum(l.slice_end-l.slice_start),0) from procurement_receipt_lines l join procurement_receipts h on h.id=l.receipt_id where l.workspace_id=? and l.entity_id=? and l.order_line_id=? and l.active and h.status='POSTED'",BigDecimal.class,w,e,num(line,"id"));
            if(start.compareTo(quantity)>=0) continue;
            var snapshot=new SourceAmounts.Snapshot(quantity,(BigDecimal)line.get("unit_cost"),(BigDecimal)line.get("subtotal"),(BigDecimal)line.get("discount"),(BigDecimal)line.get("net"),(BigDecimal)line.get("tax"),(BigDecimal)line.get("gross"));
            var slice=SourceAmounts.slice(snapshot,start,quantity);
            db.update("insert into purchase_order_lines(workspace_id,entity_id,revision_id,request_line_id,line_key,previous_line_id,product_id,uom_id,tracking,quantity,quantity_scale,unit_cost,subtotal,discount,net,tax,gross,tax_version_id,tax_rate_pct,input_account_id,expense_account_id,discount_pct,discount_amount,product_category,product_sku,product_name,uom_code,product_version,setting_version,uom_version,tax_version_version,service_profile) select workspace_id,entity_id,?,request_line_id,line_key,?,product_id,uom_id,tracking,?,quantity_scale,unit_cost,?,?,?,?,?,tax_version_id,tax_rate_pct,input_account_id,expense_account_id,NULL,?,product_category,product_sku,product_name,uom_code,product_version,setting_version,uom_version,tax_version_version,service_profile from purchase_order_lines where workspace_id=? and entity_id=? and id=?",rev,approved.isEmpty()?line.get("previous_line_id"):line.get("id"),quantity.subtract(start),slice.subtotal(),slice.discount(),slice.net(),slice.tax(),slice.gross(),slice.discount(),w,e,num(line,"id"));
        }
        db.update("insert into purchase_order_contributors(workspace_id,entity_id,revision_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,rev,scope.actor().id());
        db.update("update purchase_orders set status='DRAFT',revision=?,version=version+1 where workspace_id=? and entity_id=? and id=?",newRevision,w,e,orderId);
        audit.record(scope.actor(),w,e,"REVISE","purchase_orders",Long.toString(orderId),version+1,"SUCCESS","remaining order revision");
    }
    public List<Map<String,Object>> receipts(long w,long e,String q) {
        read(w,e); String search=q==null?"":q.trim();
        if(search.length()>200) throw new DomainException(400,"Search is too long","q");
        return db.queryForList("select id,number,order_id,kind,status,posting_date,version from procurement_receipts where workspace_id=? and entity_id=? and position(lower(?) in lower(number))>0 order by id desc",w,e,search);
    }
    public Map<String,Object> receipt(long w,long e,long receiptId) {
        read(w,e);
        return one("select id,number,order_id,order_version,kind,status,posting_date,version from procurement_receipts where workspace_id=? and entity_id=? and id=?",w,e,receiptId);
    }
    public List<Map<String,Object>> receiptLines(long w,long e,long receiptId) {
        receipt(w,e,receiptId);
        return db.queryForList("select l.id,l.line_key,l.order_line_id,l.warehouse_id,l.warehouse_version,l.slice_start,l.slice_end,p.product_sku,p.product_name,p.tracking from procurement_receipt_lines l join purchase_order_lines p on p.id=l.order_line_id where l.workspace_id=? and l.entity_id=? and l.receipt_id=? and l.active order by l.id",w,e,receiptId);
    }
    public List<Map<String,Object>> receiptTracking(long w,long e,long receiptId) {
        receipt(w,e,receiptId);
        return db.queryForList("select t.receipt_line_id,l.line_key,t.serial,t.lot,t.expires_on,t.quantity from receipt_tracking t join procurement_receipt_lines l on l.id=t.receipt_line_id where t.workspace_id=? and t.entity_id=? and l.receipt_id=? and l.active and t.active order by t.id",w,e,receiptId);
    }
    public List<Map<String,Object>> warehouseChoices(long w,long e) {
        read(w,e);
        return db.queryForList("select id,version,code,name from warehouses where workspace_id=? and entity_id=? and active order by code",w,e);
    }
    @Transactional
    public long saveReceipt(long w,long e,Long receiptId,Long expectedVersion,ReceiptInput input) {
        var scope=access.requireContext(w,e,true,"OPERATIONS");
        if(input==null || input.postingDate()==null || input.kind()==null || !List.of("GOODS","SERVICE").contains(input.kind()) || input.lines()==null || input.lines().isEmpty() || input.lines().size()>100) throw new DomainException(400,"Invalid receipt");
        Map<String,Object> order=lockedOrder(w,e,input.orderId(),input.orderVersion());
        if(!"APPROVED".equals(order.get("status"))) throw new DomainException(409,"Order is not approved");
        LocalDate approvedDate=db.queryForObject("select business_date from purchase_order_revisions where workspace_id=? and entity_id=? and id=?",LocalDate.class,w,e,num(order,"revision_id"));
        if(input.postingDate().isBefore(approvedDate)) throw new DomainException(400,"Receipt date cannot precede approved order date","date");
        Map<String,Object> old=receiptId==null?null:one("select * from procurement_receipts where workspace_id=? and entity_id=? and id=? for update",w,e,receiptId);
        if(old==null && expectedVersion!=null) throw new DomainException(400,"Unexpected version");
        if(old!=null && (expectedVersion==null || num(old,"version")!=expectedVersion || !"DRAFT".equals(old.get("status")))) throw new DomainException(409,"Receipt changed or is posted");
        if(old!=null && (num(old,"order_id")!=input.orderId() || num(old,"order_version")!=input.orderVersion() || !Objects.equals(old.get("kind"),input.kind()))) throw new DomainException(409,"Receipt source cannot change");
        Set<String> keys=new HashSet<>(); Set<Long> sources=new HashSet<>();
        for(var line:input.lines()) {
            if(line==null || !keys.add(text(line.key(),80,"lineKey")) || !sources.add(line.orderLineId())) throw new DomainException(400,"Duplicate receipt line");
        }
        long result;
        if(old==null) {
            result=id("insert into procurement_receipts(workspace_id,entity_id,order_id,order_version,number,kind,status,posting_date,created_by) values (?,?,?,?,?,?,'DRAFT',?,?) returning id",w,e,input.orderId(),input.orderVersion(),number(w,e,"RCPT"),input.kind(),input.postingDate(),scope.actor().id());
        } else {
            result=receiptId;
            db.update("update receipt_tracking set active=false where workspace_id=? and entity_id=? and active and receipt_line_id in (select id from procurement_receipt_lines where workspace_id=? and entity_id=? and receipt_id=?)",w,e,w,e,result);
            db.update("update procurement_receipt_lines set active=false where workspace_id=? and entity_id=? and receipt_id=? and active",w,e,result);
            db.update("update procurement_receipts set posting_date=?,version=version+1 where workspace_id=? and entity_id=? and id=?",input.postingDate(),w,e,result);
        }
        for(var line:input.lines()) {
            Map<String,Object> source=one("select l.* from purchase_order_lines l join purchase_order_revisions r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.revision_id where l.workspace_id=? and l.entity_id=? and l.id=? and l.active and r.order_id=? and r.revision=? and r.status='APPROVED'",w,e,line.orderLineId(),input.orderId(),num(order,"revision"));
            boolean service=List.of("SERVICE_PLAN","IMPLEMENTATION_SERVICE").contains(source.get("product_category"));
            if(service!=input.kind().equals("SERVICE")) throw new DomainException(400,"Receipt kind does not match order line");
            if(service && (!"OPERATIONAL".equals(source.get("service_profile")) || "TERM".equals(source.get("uom_code")))) throw new DomainException(400,"Prepaid and multi-period services are blocked");
            if(service && (line.warehouseId()!=null || line.warehouseVersion()!=null)) throw new DomainException(400,"Service acceptance has no warehouse");
            if(!service) {
                if(line.warehouseId()==null || line.warehouseVersion()==null) throw new DomainException(400,"Warehouse required");
                Integer found=db.queryForObject("select count(*) from warehouses where workspace_id=? and entity_id=? and id=? and version=? and active",Integer.class,w,e,line.warehouseId(),line.warehouseVersion());
                if(found==null || found!=1) throw new DomainException(409,"Warehouse changed");
            }
            BigDecimal qty=line.quantity();
            if(qty==null || qty.signum()<=0 || qty.scale()>6 || qty.scale() < -18 || (long)qty.precision()-qty.scale()>12 || qty.stripTrailingZeros().scale()>((Number)source.get("quantity_scale")).intValue()) throw new DomainException(400,"Invalid receipt quantity","quantity");
            BigDecimal start=db.queryForObject("select coalesce(sum(l.slice_end-l.slice_start),0) from procurement_receipt_lines l join procurement_receipts r on r.id=l.receipt_id where l.workspace_id=? and l.entity_id=? and l.order_line_id=? and l.active and r.status='POSTED'",BigDecimal.class,w,e,line.orderLineId());
            var snapshot=new SourceAmounts.Snapshot((BigDecimal)source.get("quantity"),(BigDecimal)source.get("unit_cost"),(BigDecimal)source.get("subtotal"),(BigDecimal)source.get("discount"),(BigDecimal)source.get("net"),(BigDecimal)source.get("tax"),(BigDecimal)source.get("gross"));
            var slice=SourceAmounts.slice(snapshot,start,start.add(qty));
            long lineId=id("insert into procurement_receipt_lines(workspace_id,entity_id,receipt_id,order_line_id,line_key,warehouse_id,warehouse_version,slice_start,slice_end,subtotal,discount,net,tax,gross,active) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,true) on conflict (workspace_id,entity_id,receipt_id,line_key) do update set order_line_id=excluded.order_line_id,warehouse_id=excluded.warehouse_id,warehouse_version=excluded.warehouse_version,slice_start=excluded.slice_start,slice_end=excluded.slice_end,subtotal=excluded.subtotal,discount=excluded.discount,net=excluded.net,tax=excluded.tax,gross=excluded.gross,active=true returning id",w,e,result,line.orderLineId(),line.key().trim(),line.warehouseId(),line.warehouseVersion(),slice.start(),slice.end(),slice.subtotal(),slice.discount(),slice.net(),slice.tax(),slice.gross());
            List<InventoryService.TrackingInput> tracks=line.trackingRows()==null?List.of():line.trackingRows();
            if(tracks.size()>100 || (service && !tracks.isEmpty())) throw new DomainException(400,"Invalid receipt tracking");
            for(var track:tracks) {
                if(track==null || track.quantity()==null || track.quantity().signum()<=0 || track.quantity().scale()>6 || (track.serial()!=null && track.serial().length()>200) || (track.lot()!=null && track.lot().length()>200)) throw new DomainException(400,"Invalid receipt tracking");
                db.update("insert into receipt_tracking(workspace_id,entity_id,receipt_line_id,serial,lot,expires_on,quantity) values (?,?,?,?,?,?,?)",w,e,lineId,track.serial(),track.lot(),track.expiresOn(),track.quantity());
            }
        }
        audit.record(scope.actor(),w,e,old==null?"CREATE":"UPDATE","procurement_receipts",Long.toString(result),old==null?1:num(old,"version")+1,"SUCCESS","receipt draft");
        return result;
    }
    @Transactional
    public ReceiptResult postReceipt(long w,long e,long receiptId,long expectedVersion,String intent) {
        var scope=access.requireContext(w,e,true,"OPERATIONS");
        String key=text(intent,120,"intent");
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((receiptId+":"+expectedVersion+":"+key.length()+":"+key).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var header=one("select * from procurement_receipts where workspace_id=? and entity_id=? and id=? for update",w,e,receiptId);
        if("POSTED".equals(header.get("status"))) {
            if(!hash.equals(header.get("posting_hash"))) throw new DomainException(409,"Receipt posting intent conflicts");
            return new ReceiptResult(receiptId,header.get("journal_id")==null?null:num(header,"journal_id"),true);
        }
        if(num(header,"version")!=expectedVersion) throw new DomainException(409,"Receipt changed");
        long orderId=num(header,"order_id");
        var sourceOrder=one("select p.status,p.revision,p.version,r.currency,r.inventory_account_id,r.grni_account_id,r.expense_account_id from purchase_orders p join purchase_order_revisions r on r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.order_id=p.id and r.revision=p.revision where p.workspace_id=? and p.entity_id=? and p.id=? and r.status='APPROVED' for update of p,r",w,e,orderId);
        if(!"APPROVED".equals(sourceOrder.get("status"))) throw new DomainException(409,"Order is not approved");
        if(num(sourceOrder,"version")!=num(header,"order_version")) throw new DomainException(409,"Order reference changed");
        LocalDate date=db.queryForObject("select posting_date from procurement_receipts where workspace_id=? and entity_id=? and id=?",LocalDate.class,w,e,receiptId);
        LocalDate approvedDate=db.queryForObject("select r.business_date from purchase_order_revisions r where r.workspace_id=? and r.entity_id=? and r.order_id=? and r.revision=? and r.status='APPROVED'",LocalDate.class,w,e,orderId,num(sourceOrder,"revision"));
        if(date.isBefore(approvedDate)) throw new DomainException(409,"Receipt date precedes approved order date");
        var lines=db.queryForList("select l.*,p.product_id,p.tracking,p.product_category,p.service_profile,p.uom_code,p.revision_id,p.quantity source_quantity,p.unit_cost source_cost,p.subtotal source_subtotal,p.discount source_discount,p.net source_net,p.tax source_tax,p.gross source_gross from procurement_receipt_lines l join purchase_order_lines p on p.workspace_id=l.workspace_id and p.entity_id=l.entity_id and p.id=l.order_line_id where l.workspace_id=? and l.entity_id=? and l.receipt_id=? and l.active order by l.id",w,e,receiptId);
        if(lines.isEmpty() || lines.size()>100) throw new DomainException(400,"Receipt requires lines");
        List<InventoryService.ReceiptStockLine> stock=new ArrayList<>();
        BigDecimal total=BigDecimal.ZERO;
        for(var line:lines) {
            long lineId=num(line,"id"),orderLine=num(line,"order_line_id");
            Integer current=db.queryForObject("select count(*) from purchase_order_lines l join purchase_order_revisions r on r.id=l.revision_id where l.id=? and l.active and r.order_id=? and r.revision=? and r.status='APPROVED'",Integer.class,orderLine,orderId,num(sourceOrder,"revision"));
            if(current==null || current!=1) throw new DomainException(409,"Order revision changed");
            BigDecimal start=db.queryForObject("select coalesce(sum(l.slice_end-l.slice_start),0) from procurement_receipt_lines l join procurement_receipts h on h.id=l.receipt_id where l.workspace_id=? and l.entity_id=? and l.order_line_id=? and l.active and h.status='POSTED'",BigDecimal.class,w,e,orderLine);
            if(start.compareTo((BigDecimal)line.get("slice_start"))!=0) throw new DomainException(409,"Receipt capacity changed; review draft");
            boolean service="SERVICE".equals(header.get("kind"));
            boolean physicalCategory=List.of("LAB_UNIT","LAB_SUPPLY","REPLACEMENT_PART").contains(line.get("product_category"));
            if(service==physicalCategory) throw new DomainException(409,"Receipt kind conflicts with approved source");
            var snapshot=new SourceAmounts.Snapshot((BigDecimal)line.get("source_quantity"),(BigDecimal)line.get("source_cost"),(BigDecimal)line.get("source_subtotal"),(BigDecimal)line.get("source_discount"),(BigDecimal)line.get("source_net"),(BigDecimal)line.get("source_tax"),(BigDecimal)line.get("source_gross"));
            var slice=SourceAmounts.slice(snapshot,(BigDecimal)line.get("slice_start"),(BigDecimal)line.get("slice_end"));
            if(slice.subtotal().compareTo((BigDecimal)line.get("subtotal"))!=0 || slice.discount().compareTo((BigDecimal)line.get("discount"))!=0 || slice.net().compareTo((BigDecimal)line.get("net"))!=0 || slice.tax().compareTo((BigDecimal)line.get("tax"))!=0 || slice.gross().compareTo((BigDecimal)line.get("gross"))!=0) throw new DomainException(409,"Receipt amounts differ from approved source");
            if(service && (!"OPERATIONAL".equals(line.get("service_profile")) || "TERM".equals(line.get("uom_code")))) throw new DomainException(400,"Prepaid and multi-period services are blocked");
            BigDecimal net=(BigDecimal)line.get("net"); total=total.add(net);
            if(!service) {
                Integer active=db.queryForObject("select count(*) from warehouses where workspace_id=? and entity_id=? and id=? and version=? and active",Integer.class,w,e,line.get("warehouse_id"),line.get("warehouse_version"));
                if(active==null || active!=1) throw new DomainException(409,"Warehouse changed");
                var tracks=db.query("select serial,lot,expires_on,quantity from receipt_tracking where workspace_id=? and entity_id=? and receipt_line_id=? and active order by id",(r,n)->new InventoryService.TrackingInput(r.getString(1),r.getString(2),r.getObject(3,LocalDate.class),r.getBigDecimal(4)),w,e,lineId);
                stock.add(new InventoryService.ReceiptStockLine(lineId,num(line,"product_id"),num(line,"warehouse_id"),(String)line.get("tracking"),((BigDecimal)line.get("slice_end")).subtract((BigDecimal)line.get("slice_start")),net,tracks));
            }
        }
        List<Ledger.Line> journal=new ArrayList<>();
        if(total.signum()>0) {
            long debit=num(sourceOrder,"SERVICE".equals(header.get("kind"))?"expense_account_id":"inventory_account_id");
            journal.add(new Ledger.Line(debit,total,BigDecimal.ZERO));
            journal.add(new Ledger.Line(num(sourceOrder,"grni_account_id"),BigDecimal.ZERO,total));
        }
        var posted=posting.post(scope,"RECEIPT",Long.toString(receiptId),"RECEIPT:"+receiptId,date,(String)sourceOrder.get("currency"),"Procurement receipt "+header.get("number"),journal);
        if("GOODS".equals(header.get("kind"))) inventory.receive(scope,receiptId,date,stock);
        db.update("update procurement_receipts set status='POSTED',posting_hash=?,posted_by=?,posted_at=now(),journal_id=?,version=version+1 where workspace_id=? and entity_id=? and id=?",hash,scope.actor().id(),posted.journalId(),w,e,receiptId);
        audit.record(scope.actor(),w,e,"POST","procurement_receipts",Long.toString(receiptId),expectedVersion+1,"SUCCESS","receipt posting");
        return new ReceiptResult(receiptId,posted.journalId(),false);
    }
}
