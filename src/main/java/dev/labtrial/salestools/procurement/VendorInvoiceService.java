package dev.labtrial.salestools.procurement;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.Ledger;
import dev.labtrial.salestools.finance.PostingService;
import dev.labtrial.salestools.finance.SourceAmounts;
import dev.labtrial.salestools.identity.AccessControl;
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
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VendorInvoiceService {
    public record Line(String lineKey,long receiptLineId,BigDecimal quantity,BigDecimal unitCost,BigDecimal discountPct,BigDecimal discountAmount,BigDecimal subtotal,BigDecimal discount,BigDecimal net,BigDecimal tax,BigDecimal gross) {}
    public record Draft(String reference,LocalDate invoiceDate,long supplierVersion,long configVersion,List<Line> lines) {}
    public record Outcome(boolean replayed,boolean held,String reason) {}
    private record Part(long lineId,long receiptLineId,SourceAmounts.Slice slice,long grni,long input,long ap) {}
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final PostingService posting;
    public VendorInvoiceService(JdbcTemplate db,AccessControl access,AuditLog audit,PostingService posting) { this.db=db; this.access=access; this.audit=audit; this.posting=posting; }
    private static long n(Object o) { return ((Number)o).longValue(); }
    private static BigDecimal b(Map<String,Object> m,String k) { return (BigDecimal)m.get(k); }
    private static LocalDate date(Object o) { return o instanceof LocalDate d?d:((java.sql.Date)o).toLocalDate(); }
    private static boolean valid(BigDecimal v,int precision,int scale) { return v!=null && v.signum()>=0 && v.scale()<=scale && v.scale()>=-precision && (long)v.precision()-v.scale()<=precision-scale; }
    private Map<String,Object> invoice(long w,long e,long id) { var rows=db.queryForList("select * from vendor_invoices where workspace_id=? and entity_id=? and id=?",w,e,id); if(rows.isEmpty()) throw new DomainException(404,"Invoice not found"); return rows.get(0); }
    private Map<String,Object> source(long w,long e,long id) {
        var rows=db.queryForList("""
            select rl.*,ol.quantity as source_quantity,ol.quantity_scale,ol.tracking,ol.unit_cost,ol.subtotal as source_subtotal,ol.discount as source_discount,ol.net as source_net,ol.tax as source_tax,ol.gross as source_gross,ol.tax_rate_pct,ol.input_account_id,ol.discount_pct as source_discount_pct,
                   r.supplier_account_id,rev.currency,rev.terms_days,rev.grni_account_id,rev.ap_account_id,rev.config_version,rc.posting_date as receipt_date
            from procurement_receipt_lines rl join procurement_receipts rc on rc.workspace_id=rl.workspace_id and rc.entity_id=rl.entity_id and rc.id=rl.receipt_id
            join purchase_orders r on r.workspace_id=rc.workspace_id and r.entity_id=rc.entity_id and r.id=rc.order_id
            join purchase_order_lines ol on ol.workspace_id=rl.workspace_id and ol.entity_id=rl.entity_id and ol.id=rl.order_line_id
            join purchase_order_revisions rev on rev.workspace_id=ol.workspace_id and rev.entity_id=ol.entity_id and rev.id=ol.revision_id
            where rl.workspace_id=? and rl.entity_id=? and rl.id=? and rl.active and rc.status='POSTED' and rev.status='APPROVED'
            """,w,e,id); if(rows.isEmpty()) throw new DomainException(404,"Receipt source not found"); return rows.get(0);
    }
    @Transactional(readOnly=true) public List<Map<String,Object>> list(long w,long e,String q,String status) { access.requireContext(w,e,false,"OPERATIONS","FINANCE"); if(!List.of("","DRAFT","HELD_FOR_REVIEW","POSTED").contains(status)) throw new DomainException(400,"Invalid invoice status"); return db.queryForList("select * from vendor_invoices where workspace_id=? and entity_id=? and reference like ? and (?='' or status=?) order by id desc",w,e,"%"+(q==null?"":q)+"%",status,status); }
    @Transactional(readOnly=true) public Map<String,Object> detail(long w,long e,long id) { access.requireContext(w,e,false,"OPERATIONS","FINANCE"); return invoice(w,e,id); }
    @Transactional(readOnly=true) public List<Map<String,Object>> lines(long w,long e,long id) { access.requireContext(w,e,false,"OPERATIONS","FINANCE"); invoice(w,e,id); return db.queryForList("select l.*,r.receipt_id from vendor_invoice_lines l join procurement_receipt_lines r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.receipt_line_id where l.workspace_id=? and l.entity_id=? and l.invoice_id=? and l.active order by l.id",w,e,id); }
    @Transactional
    public long save(long w,long e,Long id,long version,Draft d) {
        var scope=access.requireContext(w,e,true,"OPERATIONS","FINANCE");
        if(d==null || d.reference()==null || d.reference().isBlank() || d.reference().trim().length()>200 || d.invoiceDate()==null || d.lines()==null || d.lines().isEmpty() || d.lines().size()>100) throw new DomainException(400,"Invalid invoice details");
        var keys=new HashSet<String>(); Map<String,Object> first=null;
        for(Line l:d.lines()) {
            if(l==null || l.lineKey()==null || l.lineKey().isBlank() || l.lineKey().length()>120 || !keys.add(l.lineKey()) || l.receiptLineId()<=0) throw new DomainException(400,"Invalid invoice line");
            var s=source(w,e,l.receiptLineId()); if(first==null) first=s;
            if(n(s.get("supplier_account_id"))!=n(first.get("supplier_account_id")) || !s.get("currency").equals(first.get("currency"))) throw new DomainException(400,"Sources must have the same supplier and currency");
            if(n(s.get("ap_account_id"))!=n(first.get("ap_account_id"))) throw new DomainException(409,"Inconsistent historical AP account snapshots");
            if(d.invoiceDate().isBefore(date(s.get("receipt_date")))) throw new DomainException(400,"Invoice date precedes receipt");
            if(!valid(l.quantity(),18,6) || l.quantity().signum()<=0 || l.quantity().stripTrailingZeros().scale()>n(s.get("quantity_scale")) || !valid(l.unitCost(),18,6) || (l.discountPct()!=null && (!valid(l.discountPct(),9,4) || l.discountPct().compareTo(new BigDecimal("100"))>0)) || (l.discountAmount()!=null && !valid(l.discountAmount(),18,2)) || (l.discountPct()!=null && l.discountAmount()!=null)) throw new DomainException(400,"Invalid invoice quantity or price");
            if("SERIAL".equals(s.get("tracking")) && l.quantity().stripTrailingZeros().scale()>0) throw new DomainException(400,"Serial quantities must be whole units");
            if(!valid(l.subtotal(),18,2) || !valid(l.discount(),18,2) || !valid(l.net(),18,2) || !valid(l.tax(),18,2) || !valid(l.gross(),18,2)) throw new DomainException(400,"Invalid invoice amounts");
        }
        long supplier=n(first.get("supplier_account_id")); String currency=(String)first.get("currency"); int terms=((Number)first.get("terms_days")).intValue(); String reference=d.reference().trim();
        var supplierRow=db.queryForList("select version,active from supplier_entity_accounts where workspace_id=? and entity_id=? and id=?",w,e,supplier);
        if(supplierRow.isEmpty()) throw new DomainException(404,"Supplier not found");
        if(!Boolean.TRUE.equals(supplierRow.get(0).get("active")) || n(supplierRow.get(0).get("version"))!=d.supplierVersion() || n(first.get("config_version"))!=d.configVersion()) throw new DomainException(409,"Supplier or source configuration changed");
        if(!db.queryForList("select id from vendor_invoices where workspace_id=? and entity_id=? and supplier_account_id=? and reference=? and id<>?",w,e,supplier,reference,id==null?0:id).isEmpty()) throw new DomainException(409,"Invoice reference already exists");
        boolean creating=id==null;
        if(creating) id=db.queryForObject("insert into vendor_invoices(workspace_id,entity_id,supplier_account_id,reference,status,currency,invoice_date,due_date,terms_days,created_by,supplier_account_version,ap_account_id,config_version) values (?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?) returning id",Long.class,w,e,supplier,reference,currency,d.invoiceDate(),d.invoiceDate().plusDays(terms),terms,scope.actor().id(),d.supplierVersion(),n(first.get("ap_account_id")),d.configVersion());
        else { var old=invoice(w,e,id); if("POSTED".equals(old.get("status")) || n(old.get("version"))!=version) throw new DomainException(409,"Invoice changed"); db.update("update vendor_invoices set supplier_account_id=?,reference=?,currency=?,invoice_date=?,due_date=?,terms_days=?,supplier_account_version=?,ap_account_id=?,config_version=?,status='DRAFT',hold_reason=null,version=version+1 where workspace_id=? and entity_id=? and id=?",supplier,reference,currency,d.invoiceDate(),d.invoiceDate().plusDays(terms),terms,d.supplierVersion(),n(first.get("ap_account_id")),d.configVersion(),w,e,id); db.update("update vendor_invoice_lines set active=false where workspace_id=? and entity_id=? and invoice_id=? and active",w,e,id); }
        for(Line l:d.lines()) db.update("""
            insert into vendor_invoice_lines(workspace_id,entity_id,invoice_id,receipt_line_id,line_key,quantity,unit_cost,discount_pct,discount_amount,subtotal,discount,net,tax,gross,active) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,true)
            on conflict(workspace_id,entity_id,invoice_id,line_key) do update set receipt_line_id=excluded.receipt_line_id,quantity=excluded.quantity,unit_cost=excluded.unit_cost,discount_pct=excluded.discount_pct,discount_amount=excluded.discount_amount,subtotal=excluded.subtotal,discount=excluded.discount,net=excluded.net,tax=excluded.tax,gross=excluded.gross,active=true
            """,w,e,id,l.receiptLineId(),l.lineKey(),l.quantity(),l.unitCost(),l.discountPct(),l.discountAmount(),l.subtotal(),l.discount(),l.net(),l.tax(),l.gross());
        audit.record(scope.actor(),w,e,"SAVE","VENDOR_INVOICE",id.toString(),creating?1:version+1,"SUCCESS","invoice draft"); return id;
    }
    private static String hash(String v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    @Transactional
    public Outcome post(long w,long e,long id,long version) {
        var scope=access.requireContext(w,e,true,"FINANCE"); var inv=invoice(w,e,id); String intent=hash(id+":"+version);
        if("POSTED".equals(inv.get("status"))) { if(intent.equals(inv.get("posting_hash"))) return new Outcome(true,false,null); throw new DomainException(409,"Invoice already posted with different intent"); }
        if(n(inv.get("version"))!=version) throw new DomainException(409,"Invoice changed");
        var supplier=db.queryForList("select version,active from supplier_entity_accounts where workspace_id=? and entity_id=? and id=?",w,e,inv.get("supplier_account_id"));
        if(supplier.size()!=1 || !Boolean.TRUE.equals(supplier.get(0).get("active")) || n(supplier.get(0).get("version"))!=n(inv.get("supplier_account_version"))) throw new DomainException(409,"Supplier account changed");
        var lines=db.queryForList("select * from vendor_invoice_lines where workspace_id=? and entity_id=? and invoice_id=? and active order by id",w,e,id); if(lines.isEmpty() || lines.size()>100) throw new DomainException(400,"Invoice needs lines");
        List<Part> parts=new ArrayList<>(); String reason=null;
        for(var line:lines) {
            long receipt=n(line.get("receipt_line_id")); var s=source(w,e,receipt); BigDecimal start=b(s,"slice_start"),end=b(s,"slice_end"),remaining=b(line,"quantity");
            var snap=new SourceAmounts.Snapshot(b(s,"source_quantity"),b(s,"unit_cost"),b(s,"source_subtotal"),b(s,"source_discount"),b(s,"source_net"),b(s,"source_tax"),b(s,"source_gross"));
            var used=db.queryForList("""
                select a.slice_start,a.slice_end from invoice_source_allocations a join vendor_invoice_lines l on l.workspace_id=a.workspace_id and l.entity_id=a.entity_id and l.id=a.invoice_line_id
                join vendor_invoices i on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id
                where a.workspace_id=? and a.entity_id=? and a.receipt_line_id=? and a.active and l.active and i.status='POSTED'
                  and not exists(select 1 from procurement_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?)
                order by a.slice_start
                """,w,e,receipt,date(inv.get("invoice_date")));
            List<BigDecimal[]> occupied=new ArrayList<>(); for(var u:used) occupied.add(new BigDecimal[]{b(u,"slice_start"),b(u,"slice_end")}); for(var p:parts) if(p.receiptLineId()==receipt) occupied.add(new BigDecimal[]{p.slice().start(),p.slice().end()}); occupied.sort((a,c)->a[0].compareTo(c[0]));
            var lineParts=new ArrayList<Part>(); BigDecimal cursor=start;
            for(int j=0;j<=occupied.size() && remaining.signum()>0;j++) {
                BigDecimal stop=j==occupied.size()?end:occupied.get(j)[0].min(end);
                if(cursor.compareTo(stop)<0) { BigDecimal until=cursor.add(remaining).min(stop); var slice=SourceAmounts.slice(snap,cursor,until); lineParts.add(new Part(n(line.get("id")),receipt,slice,n(s.get("grni_account_id")),n(s.get("input_account_id")),n(s.get("ap_account_id")))); remaining=remaining.subtract(until.subtract(cursor)); cursor=until; }
                if(j<occupied.size()) cursor=cursor.max(occupied.get(j)[1]); if(cursor.compareTo(end)>=0) break;
            }
            if(remaining.signum()>0) { reason="Quantity exceeds available receipt entitlement"; break; }
            BigDecimal sub=BigDecimal.ZERO,disc=BigDecimal.ZERO,net=BigDecimal.ZERO,tax=BigDecimal.ZERO,gross=BigDecimal.ZERO;
            for(var p:lineParts) { sub=sub.add(p.slice().subtotal()); disc=disc.add(p.slice().discount()); net=net.add(p.slice().net()); tax=tax.add(p.slice().tax()); gross=gross.add(p.slice().gross()); }
            BigDecimal actualPct=b(line,"discount_pct"), actualFixed=b(line,"discount_amount"), sourcePct=b(s,"source_discount_pct");
            if(actualFixed!=null && actualFixed.compareTo(b(line,"discount"))!=0) { reason="Invoice discount method differs from the actual discount"; break; }
            if(actualPct!=null && (sourcePct==null || actualPct.compareTo(sourcePct)!=0)
                    && b(line,"subtotal").multiply(actualPct).divide(new BigDecimal("100")).setScale(2,java.math.RoundingMode.HALF_UP).compareTo(b(line,"discount"))!=0) {
                reason="Invoice discount method differs from the actual discount"; break;
            }
            if(b(line,"unit_cost").compareTo(b(s,"unit_cost"))!=0 || b(line,"subtotal").compareTo(sub)!=0 || b(line,"discount").compareTo(disc)!=0 || b(line,"net").compareTo(net)!=0 || b(line,"tax").compareTo(tax)!=0 || b(line,"gross").compareTo(gross)!=0) { reason="Invoice amounts differ from approved receipt entitlement"; break; }
            parts.addAll(lineParts);
        }
        if(reason!=null) { db.update("update vendor_invoices set status='HELD_FOR_REVIEW',hold_reason=?,version=version+1 where workspace_id=? and entity_id=? and id=?",reason,w,e,id); audit.record(scope.actor(),w,e,"MATCH","VENDOR_INVOICE",Long.toString(id),version,"HELD",reason); return new Outcome(false,true,reason); }
        var debits=new TreeMap<Long,BigDecimal>(); var credits=new TreeMap<Long,BigDecimal>();
        for(var p:parts) { var x=p.slice(); db.update("insert into invoice_source_allocations(workspace_id,entity_id,invoice_line_id,receipt_line_id,slice_start,slice_end,subtotal,discount,net,tax,gross) values (?,?,?,?,?,?,?,?,?,?,?)",w,e,p.lineId(),p.receiptLineId(),x.start(),x.end(),x.subtotal(),x.discount(),x.net(),x.tax(),x.gross()); debits.merge(p.grni(),x.net(),BigDecimal::add); debits.merge(p.input(),x.tax(),BigDecimal::add); credits.merge(p.ap(),x.gross(),BigDecimal::add); }
        var journalLines=new ArrayList<Ledger.Line>(); for(var x:debits.entrySet()) if(x.getValue().signum()>0) journalLines.add(new Ledger.Line(x.getKey(),x.getValue(),BigDecimal.ZERO)); for(var x:credits.entrySet()) if(x.getValue().signum()>0) journalLines.add(new Ledger.Line(x.getKey(),BigDecimal.ZERO,x.getValue()));
        var result=posting.post(scope,"VENDOR_INVOICE",Long.toString(id),"vendor-invoice:"+id,date(inv.get("invoice_date")),(String)inv.get("currency"),"Vendor invoice "+inv.get("reference"),journalLines);
        db.update("update vendor_invoices set status='POSTED',hold_reason=null,posting_hash=?,journal_id=?,posted_by=?,posted_at=now(),version=version+1 where workspace_id=? and entity_id=? and id=?",intent,result.journalId(),scope.actor().id(),w,e,id); audit.record(scope.actor(),w,e,"POST","VENDOR_INVOICE",Long.toString(id),version,"SUCCESS","matched invoice"); return new Outcome(false,false,null);
    }
    @Transactional
    public void reverse(long w,long e,long id,LocalDate reversalDate,String reason,String reversalKey) {
        var scope=access.requireContext(w,e,true,"FINANCE"); var inv=invoice(w,e,id); if(!"POSTED".equals(inv.get("status"))) throw new DomainException(409,"Invoice is not posted"); if(reversalDate==null || reversalDate.isBefore(date(inv.get("invoice_date"))) || reason==null || reason.isBlank() || reason.length()>500 || reversalKey==null || reversalKey.isBlank() || reversalKey.length()>120) throw new DomainException(400,"Valid reversal key, date and reason required");
        String key=reversalKey.trim(), intent=hash("INVOICE:"+id+":"+reversalDate+":"+reason.trim());
        var prior=db.queryForList("select payload_hash from procurement_reversals where workspace_id=? and entity_id=? and reversal_key=?",w,e,key);
        if(!prior.isEmpty()) { if(intent.equals(prior.get(0).get("payload_hash"))) return; throw new DomainException(409,"Reversal key conflicts"); }
        if(!db.queryForList("select id from procurement_reversals where workspace_id=? and entity_id=? and invoice_id=?",w,e,id).isEmpty()) throw new DomainException(409,"Invoice already reversed");
        if(!db.queryForList("select a.id from supplier_payment_allocations a join supplier_payments p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.payment_id where a.workspace_id=? and a.entity_id=? and a.invoice_id=? and a.active and p.status='POSTED' and not exists(select 1 from procurement_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.payment_id=p.id and r.reversal_date<=?)",w,e,id,reversalDate).isEmpty()) throw new DomainException(409,"Reverse active payments first");
        Long journal; if(inv.get("journal_id")==null) journal=posting.post(scope,"INVOICE_REVERSAL",Long.toString(id),"vendor-invoice-reversal:"+id,reversalDate,(String)inv.get("currency"),reason.trim(),List.of()).journalId(); else journal=posting.reverse(scope,n(inv.get("journal_id")),"vendor-invoice-reversal:"+id,reversalDate,reason.trim()).journalId();
        db.update("insert into procurement_reversals(workspace_id,entity_id,source_type,invoice_id,reversal_date,reason,journal_id,created_by,reversal_key,payload_hash) values (?,?,'INVOICE',?,?,?,?,?,?,?)",w,e,id,reversalDate,reason.trim(),journal,scope.actor().id(),key,intent); audit.record(scope.actor(),w,e,"REVERSE","VENDOR_INVOICE",Long.toString(id),n(inv.get("version")),"SUCCESS",reason.trim());
    }
}
