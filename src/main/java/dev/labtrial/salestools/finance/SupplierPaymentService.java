package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierPaymentService {
    public record Allocation(long invoiceId, BigDecimal amount) {}
    public record Draft(String paymentKey, long supplierAccountId, long supplierVersion, long bankAccountVersion,
                        long configVersion, LocalDate paymentDate, String currency, BigDecimal amount, List<Allocation> allocations) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final PostingService posting;
    public SupplierPaymentService(JdbcTemplate db, AccessControl access, AuditLog audit, PostingService posting) {
        this.db=db; this.access=access; this.audit=audit; this.posting=posting;
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static boolean amount(BigDecimal v) { return v!=null && v.signum()>0 && v.scale()<=2 && v.scale()>=-18 && (long)v.precision()-v.scale()<=16; }
    private static void validate(Draft d) {
        if (d==null || d.paymentKey()==null || d.paymentKey().isBlank() || d.paymentKey().length()>120 || d.supplierAccountId()<=0 || d.paymentDate()==null || d.currency()==null || d.currency().length()!=3 || !amount(d.amount()) || d.allocations()==null || d.allocations().isEmpty() || d.allocations().size()>100) throw new DomainException(400,"Invalid payment details");
        var ids=new HashSet<Long>();
        for (Allocation a:d.allocations()) if (a==null || a.invoiceId()<=0 || !amount(a.amount()) || !ids.add(a.invoiceId())) throw new DomainException(400,"Invalid or duplicate invoice allocation");
    }
    private Map<String,Object> row(long w,long e,long id) {
        var rows=db.queryForList("select * from supplier_payments where workspace_id=? and entity_id=? and id=?",w,e,id);
        if(rows.isEmpty()) throw new DomainException(404,"Payment not found"); return rows.get(0);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(long w,long e,String query,String status) {
        access.requireContext(w,e,false,"FINANCE");
        if(!List.of("","DRAFT","POSTED").contains(status)) throw new DomainException(400,"Invalid payment status");
        return db.queryForList("select * from supplier_payments where workspace_id=? and entity_id=? and payment_key ilike ? and (?='' or status=?) order by id desc",w,e,"%"+(query==null?"":query)+"%",status,status);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(long w,long e,long id) { access.requireContext(w,e,false,"FINANCE"); return row(w,e,id); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> allocations(long w,long e,long id) { access.requireContext(w,e,false,"FINANCE"); row(w,e,id); return db.queryForList("select * from supplier_payment_allocations where workspace_id=? and entity_id=? and payment_id=? and active order by id",w,e,id); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> aging(long w,long e,LocalDate asOf) {
        access.requireContext(w,e,false,"FINANCE"); if(asOf==null) throw new DomainException(400,"As-of date required");
        return db.queryForList("""
            select i.id,i.reference,i.supplier_account_id,i.currency,i.invoice_date,i.due_date,
              coalesce((select sum(l.gross) from vendor_invoice_lines l where l.workspace_id=i.workspace_id and l.entity_id=i.entity_id and l.invoice_id=i.id and l.active),0)
              - coalesce((select sum(a.amount) from supplier_payment_allocations a join supplier_payments p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.payment_id where a.workspace_id=i.workspace_id and a.entity_id=i.entity_id and a.invoice_id=i.id and a.active and p.status='POSTED' and p.payment_date<=? and not exists(select 1 from procurement_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.payment_id=p.id and r.reversal_date<=?)),0) as open_amount
            from vendor_invoices i where i.workspace_id=? and i.entity_id=? and i.status='POSTED' and i.invoice_date<=?
              and not exists(select 1 from procurement_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?) order by i.due_date,i.id
            """,asOf,asOf,w,e,asOf,asOf);
    }
    @Transactional
    public long save(long w,long e,Long id,long version,Draft draft) {
        var scope=access.requireContext(w,e,true,"FINANCE"); validate(draft);
        var entity=db.queryForList("select base_currency from legal_entities where workspace_id=? and id=?",w,e);
        if(entity.isEmpty() || !draft.currency().equals(entity.get(0).get("base_currency"))) throw new DomainException(400,"Currency must match entity");
        var suppliers=db.queryForList("select version,active from supplier_entity_accounts where workspace_id=? and entity_id=? and id=?",w,e,draft.supplierAccountId());
        if(suppliers.isEmpty()) throw new DomainException(404,"Supplier not found");
        if(!Boolean.TRUE.equals(suppliers.get(0).get("active")) || number(suppliers.get(0).get("version"))!=draft.supplierVersion()) throw new DomainException(409,"Supplier account changed");
        Long ap=null;
        for(Allocation a:draft.allocations()) {
            var invoice=db.queryForList("select ap_account_id from vendor_invoices where workspace_id=? and entity_id=? and id=? and supplier_account_id=? and currency=?",w,e,a.invoiceId(),draft.supplierAccountId(),draft.currency());
            if(invoice.isEmpty()) throw new DomainException(404,"Invoice not found");
            long account=number(invoice.get(0).get("ap_account_id"));
            if(ap!=null && ap.longValue()!=account) throw new DomainException(409,"Inconsistent historical AP account snapshots");
            if(ap==null) ap=account;
        }
        var config=db.queryForList("""
            select p.bank_account_id,p.version as config_version,a.version as bank_version,a.active as bank_active,
                   (select c.inventory_account_id from procurement_posting_config c where c.workspace_id=p.workspace_id and c.entity_id=p.entity_id) as inventory_account_id,
                   exists(select 1 from tax_versions t where t.workspace_id=p.workspace_id and t.entity_id=p.entity_id and t.input_account_id=p.bank_account_id) as tax_role_conflict
            from payment_posting_config p join chart_accounts a on a.workspace_id=p.workspace_id and a.entity_id=p.entity_id and a.id=p.bank_account_id
            where p.workspace_id=? and p.entity_id=?
            """,w,e);
        if(config.size()!=1) throw new DomainException(409,"Payment and procurement accounts must be configured");
        if(number(config.get(0).get("config_version"))!=draft.configVersion() || number(config.get(0).get("bank_version"))!=draft.bankAccountVersion() || !Boolean.TRUE.equals(config.get(0).get("bank_active"))) throw new DomainException(409,"Payment configuration changed");
        long bank=number(config.get(0).get("bank_account_id"));
        Long mappedInventory=config.get(0).get("inventory_account_id")==null ? null : number(config.get(0).get("inventory_account_id"));
        if(bank==ap) throw new DomainException(400,"Bank and AP accounts must differ");
        if(mappedInventory!=null && bank==mappedInventory.longValue()) throw new DomainException(400,"Bank and Inventory accounts must differ");
        if(Boolean.TRUE.equals(config.get(0).get("tax_role_conflict"))) throw new DomainException(400,"Bank and Input Tax accounts must differ");
        boolean creating=id==null;
        if(creating) {
            if(!db.queryForList("select id from supplier_payments where workspace_id=? and entity_id=? and payment_key=?",w,e,draft.paymentKey().trim()).isEmpty()) throw new DomainException(409,"Payment reference already exists");
            id=db.queryForObject("insert into supplier_payments(workspace_id,entity_id,supplier_account_id,payment_key,status,payment_date,currency,amount,bank_account_id,ap_account_id,created_by,supplier_account_version,bank_account_version,config_version) values (?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?) returning id",Long.class,w,e,draft.supplierAccountId(),draft.paymentKey().trim(),draft.paymentDate(),draft.currency(),draft.amount(),bank,ap,scope.actor().id(),draft.supplierVersion(),draft.bankAccountVersion(),draft.configVersion());
        } else {
            var old=row(w,e,id); if(!"DRAFT".equals(old.get("status")) || number(old.get("version"))!=version) throw new DomainException(409,"Payment changed");
            if(!db.queryForList("select id from supplier_payments where workspace_id=? and entity_id=? and payment_key=? and id<>?",w,e,draft.paymentKey().trim(),id).isEmpty()) throw new DomainException(409,"Payment reference already exists");
            db.update("update supplier_payments set supplier_account_id=?,payment_key=?,payment_date=?,currency=?,amount=?,bank_account_id=?,ap_account_id=?,supplier_account_version=?,bank_account_version=?,config_version=?,version=version+1 where workspace_id=? and entity_id=? and id=?",draft.supplierAccountId(),draft.paymentKey().trim(),draft.paymentDate(),draft.currency(),draft.amount(),bank,ap,draft.supplierVersion(),draft.bankAccountVersion(),draft.configVersion(),w,e,id);
            db.update("update supplier_payment_allocations set active=false where workspace_id=? and entity_id=? and payment_id=? and active",w,e,id);
        }
        for(Allocation a:draft.allocations()) db.update("insert into supplier_payment_allocations(workspace_id,entity_id,payment_id,invoice_id,amount,active) values (?,?,?,?,?,true) on conflict(workspace_id,entity_id,payment_id,invoice_id) do update set amount=excluded.amount,active=true",w,e,id,a.invoiceId(),a.amount());
        audit.record(scope.actor(),w,e,"SAVE","SUPPLIER_PAYMENT",id.toString(),creating?1:version+1,"SUCCESS","payment draft"); return id;
    }
    private static String hash(String s) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    @Transactional
    public boolean post(long w,long e,long id,long version) {
        var scope=access.requireContext(w,e,true,"FINANCE"); var p=row(w,e,id);
        String intent=hash(id+":"+version);
        if("POSTED".equals(p.get("status"))) { if(intent.equals(p.get("posting_hash"))) return true; throw new DomainException(409,"Payment already posted with different intent"); }
        if(number(p.get("version"))!=version) throw new DomainException(409,"Payment changed");
        var current=db.queryForList("""
            select s.version as supplier_version,s.active as supplier_active,
                   a.version as bank_version,a.active as bank_active,p.version as config_version,p.bank_account_id,
                   (select c.inventory_account_id from procurement_posting_config c where c.workspace_id=s.workspace_id and c.entity_id=s.entity_id) as inventory_account_id,
                   exists(select 1 from tax_versions t where t.workspace_id=s.workspace_id and t.entity_id=s.entity_id and t.input_account_id=p.bank_account_id) as tax_role_conflict
            from supplier_entity_accounts s join payment_posting_config p on p.workspace_id=s.workspace_id and p.entity_id=s.entity_id
            join chart_accounts a on a.workspace_id=p.workspace_id and a.entity_id=p.entity_id and a.id=p.bank_account_id
            where s.workspace_id=? and s.entity_id=? and s.id=?
            """,w,e,p.get("supplier_account_id"));
        if(current.size()!=1 || !Boolean.TRUE.equals(current.get(0).get("supplier_active")) || !Boolean.TRUE.equals(current.get(0).get("bank_active"))
                || number(current.get(0).get("supplier_version"))!=number(p.get("supplier_account_version"))
                || number(current.get(0).get("bank_version"))!=number(p.get("bank_account_version"))
                || number(current.get(0).get("config_version"))!=number(p.get("config_version"))
                || number(current.get(0).get("bank_account_id"))!=number(p.get("bank_account_id")))
            throw new DomainException(409,"Supplier or payment configuration changed");
        Long currentInventory=current.get(0).get("inventory_account_id")==null ? null : number(current.get(0).get("inventory_account_id"));
        if(currentInventory!=null && number(p.get("bank_account_id"))==currentInventory.longValue())
            throw new DomainException(400,"Bank and Inventory accounts must differ");
        if(Boolean.TRUE.equals(current.get(0).get("tax_role_conflict"))) throw new DomainException(400,"Bank and Input Tax accounts must differ");
        var allocations=db.queryForList("select invoice_id,amount from supplier_payment_allocations where workspace_id=? and entity_id=? and payment_id=? and active order by invoice_id",w,e,id);
        if(allocations.isEmpty() || allocations.size()>100) throw new DomainException(400,"Allocation required");
        BigDecimal sum=BigDecimal.ZERO; LocalDate date=((java.sql.Date)p.get("payment_date")).toLocalDate();
        for(var a:allocations) {
            long invoice=number(a.get("invoice_id")); BigDecimal value=(BigDecimal)a.get("amount"); sum=sum.add(value);
            var candidates=db.queryForList("select i.ap_account_id from vendor_invoices i where i.workspace_id=? and i.entity_id=? and i.id=? and i.supplier_account_id=? and i.currency=? and i.status='POSTED' and i.invoice_date<=? and not exists(select 1 from procurement_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id)",w,e,invoice,p.get("supplier_account_id"),p.get("currency"),date);
            if(candidates.isEmpty()) throw new DomainException(409,"Invoice is not open for payment");
            long invoiceAp=number(candidates.get(0).get("ap_account_id"));
            if(invoiceAp!=number(p.get("ap_account_id"))) throw new DomainException(409,"Inconsistent historical AP account snapshots");
            if(invoiceAp==number(p.get("bank_account_id"))) throw new DomainException(400,"Bank and AP accounts must differ");
            BigDecimal gross=db.queryForObject("select coalesce(sum(gross),0) from vendor_invoice_lines where workspace_id=? and entity_id=? and invoice_id=? and active",BigDecimal.class,w,e,invoice);
            var dates=db.queryForList("""
                select payment_date from supplier_payments p join supplier_payment_allocations a
                  on a.workspace_id=p.workspace_id and a.entity_id=p.entity_id and a.payment_id=p.id
                where p.workspace_id=? and p.entity_id=? and a.invoice_id=? and a.active and p.status='POSTED' and p.payment_date>=?
                union select cast(? as date)
                """,java.sql.Date.class,w,e,invoice,date,date);
            for(var point:dates) {
                BigDecimal allocated=db.queryForObject("""
                    select coalesce(sum(a.amount),0) from supplier_payment_allocations a join supplier_payments p
                      on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.payment_id
                    where a.workspace_id=? and a.entity_id=? and a.invoice_id=? and a.active and p.status='POSTED' and p.payment_date<=?
                      and not exists(select 1 from procurement_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.payment_id=p.id and r.reversal_date<=?)
                    """,BigDecimal.class,w,e,invoice,point,point);
                if(allocated.add(value).compareTo(gross)>0) throw new DomainException(409,"Allocation exceeds invoice balance");
            }
        }
        if(sum.compareTo((BigDecimal)p.get("amount"))!=0) throw new DomainException(400,"Allocations must exactly equal the payment amount");
        BigDecimal zero=BigDecimal.ZERO; long bank=number(p.get("bank_account_id")), ap=number(p.get("ap_account_id"));
        var result=posting.post(scope,"SUPPLIER_PAYMENT",Long.toString(id),"supplier-payment:"+id,date,(String)p.get("currency"),"Supplier payment "+p.get("payment_key"),List.of(new Ledger.Line(ap,sum,zero),new Ledger.Line(bank,zero,sum)));
        db.update("update supplier_payments set status='POSTED',posting_hash=?,journal_id=?,posted_by=?,posted_at=now(),version=version+1 where workspace_id=? and entity_id=? and id=?",intent,result.journalId(),scope.actor().id(),w,e,id);
        audit.record(scope.actor(),w,e,"POST","SUPPLIER_PAYMENT",Long.toString(id),version,"SUCCESS","payment posted"); return false;
    }
    @Transactional
    public void reverse(long w,long e,long id,LocalDate date,String reason,String reversalKey) {
        var scope=access.requireContext(w,e,true,"FINANCE"); var p=row(w,e,id);
        if(!"POSTED".equals(p.get("status"))) throw new DomainException(409,"Payment is not posted");
        if(date==null || reason==null || reason.isBlank() || reason.length()>500 || reversalKey==null || reversalKey.isBlank() || reversalKey.length()>120) throw new DomainException(400,"Reversal key, date and reason required");
        String key=reversalKey.trim(), intent=hash("PAYMENT:"+id+":"+date+":"+reason.trim());
        var prior=db.queryForList("select payload_hash from procurement_reversals where workspace_id=? and entity_id=? and reversal_key=?",w,e,key);
        if(!prior.isEmpty()) { if(intent.equals(prior.get(0).get("payload_hash"))) return; throw new DomainException(409,"Reversal key conflicts"); }
        if(!db.queryForList("select id from procurement_reversals where workspace_id=? and entity_id=? and payment_id=?",w,e,id).isEmpty()) throw new DomainException(409,"Payment already reversed");
        var result=posting.reverse(scope,number(p.get("journal_id")),"supplier-payment-reversal:"+id,date,reason.trim());
        db.update("insert into procurement_reversals(workspace_id,entity_id,source_type,payment_id,reversal_date,reason,journal_id,created_by,reversal_key,payload_hash) values (?,?,'PAYMENT',?,?,?,?,?,?,?)",w,e,id,date,reason.trim(),result.journalId(),scope.actor().id(),key,intent);
        audit.record(scope.actor(),w,e,"REVERSE","SUPPLIER_PAYMENT",Long.toString(id),number(p.get("version")),"SUCCESS",reason.trim());
    }
}
