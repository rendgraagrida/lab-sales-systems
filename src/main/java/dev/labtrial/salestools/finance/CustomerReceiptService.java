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
public class CustomerReceiptService {
    public record Allocation(long invoiceId, BigDecimal amount) {}
    public record Draft(String receiptKey, long customerAccountId, long customerVersion, long configVersion,
                        long paymentConfigVersion, LocalDate paymentDate, String currency, BigDecimal amount,
                        List<Allocation> allocations) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final PostingService posting;
    private final SalesPostingConfigService config;
    public CustomerReceiptService(JdbcTemplate db, AccessControl access, AuditLog audit, PostingService posting, SalesPostingConfigService config) {
        this.db=db; this.access=access; this.audit=audit; this.posting=posting; this.config=config;
    }
    private static long n(Object o) { return ((Number)o).longValue(); }
    private static LocalDate date(Object o) { return o instanceof LocalDate d ? d : ((java.sql.Date)o).toLocalDate(); }
    private static boolean positive(BigDecimal v) { return v!=null && v.signum()>0 && v.scale()<=2 && v.scale()>=-18 && (long)v.precision()-v.scale()<=16; }
    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private Map<String,Object> row(long w,long e,long id) {
        var rows=db.queryForList("select * from customer_receipts where workspace_id=? and entity_id=? and id=?",w,e,id);
        if(rows.isEmpty()) throw new DomainException(404,"Receipt not found");
        return rows.get(0);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(long w,long e,String q,String status) {
        access.requireContext(w,e,false,"FINANCE");
        if(!List.of("","DRAFT","POSTED").contains(status)) throw new DomainException(400,"Invalid receipt status");
        return db.queryForList("select * from customer_receipts where workspace_id=? and entity_id=? and receipt_key ilike ? and (?='' or status=?) order by id desc",w,e,"%"+(q==null?"":q)+"%",status,status);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(long w,long e,long id) { access.requireContext(w,e,false,"FINANCE"); return row(w,e,id); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> allocations(long w,long e,long id) {
        access.requireContext(w,e,false,"FINANCE"); row(w,e,id);
        return db.queryForList("select * from customer_receipt_allocations where workspace_id=? and entity_id=? and receipt_id=? and active order by id",w,e,id);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> aging(long w,long e,LocalDate asOf) {
        access.requireContext(w,e,false,"FINANCE");
        if(asOf==null) throw new DomainException(400,"As-of date required");
        return db.queryForList("""
            select i.id,i.reference,i.customer_account_id,i.currency,i.invoice_date,i.due_date,greatest(cast(? as date)-i.due_date,0) as days_overdue,
              coalesce((select sum(l.gross) from sales_invoice_lines l where l.workspace_id=i.workspace_id and l.entity_id=i.entity_id and l.invoice_id=i.id and l.active),0)
              - coalesce((select sum(a.amount) from customer_receipt_allocations a join customer_receipts p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.receipt_id
                where a.workspace_id=i.workspace_id and a.entity_id=i.entity_id and a.invoice_id=i.id and a.active and p.status='POSTED' and p.payment_date<=?
                and not exists(select 1 from sales_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.receipt_id=p.id and r.reversal_date<=?)),0) as open_amount
            from sales_invoices i where i.workspace_id=? and i.entity_id=? and i.status='POSTED' and i.invoice_date<=?
              and not exists(select 1 from sales_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?) order by i.due_date,i.id
            """,asOf,asOf,asOf,w,e,asOf,asOf);
    }
    @Transactional
    public long save(long w,long e,Long id,long version,Draft d) {
        var scope=access.requireContext(w,e,true,"FINANCE");
        if(d==null || d.receiptKey()==null || d.receiptKey().isBlank() || d.receiptKey().length()>120 || d.customerAccountId()<=0 || d.paymentDate()==null || d.currency()==null || d.currency().length()!=3 || !positive(d.amount()) || d.allocations()==null || d.allocations().isEmpty() || d.allocations().size()>100) throw new DomainException(400,"Invalid receipt details");
        var ids=new HashSet<Long>();
        for(var a:d.allocations()) if(a==null || a.invoiceId()<=0 || !positive(a.amount()) || !ids.add(a.invoiceId())) throw new DomainException(400,"Invalid or duplicate allocation");
        var canonical=new StringBuilder().append(d.customerAccountId()).append(':').append(d.customerVersion()).append(':').append(d.configVersion()).append(':').append(d.paymentConfigVersion()).append(':').append(d.paymentDate()).append(':').append(d.currency()).append(':').append(d.amount().stripTrailingZeros().toPlainString());
        for(var a:d.allocations()) canonical.append('|').append(a.invoiceId()).append(':').append(a.amount().stripTrailingZeros().toPlainString());
        String creationHash=hash(canonical.toString());
        if(id==null) {
            var prior=db.queryForList("select id,creation_hash from customer_receipts where workspace_id=? and entity_id=? and create_key=?",w,e,d.receiptKey().trim());
            if(!prior.isEmpty()) { if(creationHash.equals(prior.get(0).get("creation_hash"))) return n(prior.get(0).get("id")); throw new DomainException(409,"Receipt creation key conflicts"); }
        }
        var customer=db.queryForList("select version,active from customer_entity_accounts where workspace_id=? and entity_id=? and id=?",w,e,d.customerAccountId());
        if(customer.isEmpty()) throw new DomainException(404,"Customer account not found");
        if(!Boolean.TRUE.equals(customer.get(0).get("active")) || n(customer.get(0).get("version"))!=d.customerVersion()) throw new DomainException(409,"Customer account changed");
        var accounts=config.require(scope);
        if(accounts.version()!=d.configVersion() || accounts.paymentVersion()!=d.paymentConfigVersion()) throw new DomainException(409,"Posting configuration changed");
        Long ar=null;
        for(var a:d.allocations()) {
            var invoices=db.queryForList("select ar_account_id from sales_invoices where workspace_id=? and entity_id=? and id=? and customer_account_id=? and currency=? and status='POSTED'",w,e,a.invoiceId(),d.customerAccountId(),d.currency());
            if(invoices.isEmpty()) throw new DomainException(404,"Invoice not found");
            long candidate=n(invoices.get(0).get("ar_account_id"));
            if(ar!=null && ar.longValue()!=candidate) throw new DomainException(409,"Invoice AR account snapshots differ");
            ar=candidate;
        }
        if(ar==accounts.bank()) throw new DomainException(409,"Bank and historical AR accounts must differ");
        boolean creating=id==null;
        if(creating) {
            if(!db.queryForList("select id from customer_receipts where workspace_id=? and entity_id=? and receipt_key=?",w,e,d.receiptKey().trim()).isEmpty()) throw new DomainException(409,"Receipt reference already exists");
            id=db.queryForObject("insert into customer_receipts(workspace_id,entity_id,customer_account_id,receipt_key,create_key,creation_hash,status,payment_date,currency,amount,bank_account_id,ar_account_id,customer_account_version,config_version,payment_config_version,created_by) values (?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?) returning id",Long.class,w,e,d.customerAccountId(),d.receiptKey().trim(),d.receiptKey().trim(),creationHash,d.paymentDate(),d.currency(),d.amount(),accounts.bank(),ar,d.customerVersion(),d.configVersion(),d.paymentConfigVersion(),scope.actor().id());
        } else {
            var old=row(w,e,id);
            if(!"DRAFT".equals(old.get("status")) || n(old.get("version"))!=version) throw new DomainException(409,"Receipt changed");
            if(!db.queryForList("select id from customer_receipts where workspace_id=? and entity_id=? and receipt_key=? and id<>?",w,e,d.receiptKey().trim(),id).isEmpty()) throw new DomainException(409,"Receipt reference already exists");
            db.update("update customer_receipts set customer_account_id=?,receipt_key=?,payment_date=?,currency=?,amount=?,bank_account_id=?,ar_account_id=?,customer_account_version=?,config_version=?,payment_config_version=?,version=version+1 where workspace_id=? and entity_id=? and id=?",d.customerAccountId(),d.receiptKey().trim(),d.paymentDate(),d.currency(),d.amount(),accounts.bank(),ar,d.customerVersion(),d.configVersion(),d.paymentConfigVersion(),w,e,id);
            db.update("update customer_receipt_allocations set active=false where workspace_id=? and entity_id=? and receipt_id=? and active",w,e,id);
        }
        for(var a:d.allocations()) db.update("insert into customer_receipt_allocations(workspace_id,entity_id,receipt_id,invoice_id,amount) values (?,?,?,?,?) on conflict(workspace_id,entity_id,receipt_id,invoice_id) do update set amount=excluded.amount,active=true",w,e,id,a.invoiceId(),a.amount());
        audit.record(scope.actor(),w,e,"SAVE","CUSTOMER_RECEIPT",id.toString(),creating?1:version+1,"SUCCESS","receipt draft");
        return id;
    }
    @Transactional
    public boolean post(long w,long e,long id,long version) {
        var scope=access.requireContext(w,e,true,"FINANCE"); lockEntity(w,e); var p=row(w,e,id); String intent=hash(id+":"+version);
        if("POSTED".equals(p.get("status"))) { if(intent.equals(p.get("posting_hash"))) return true; throw new DomainException(409,"Receipt already posted with different intent"); }
        if(n(p.get("version"))!=version) throw new DomainException(409,"Receipt changed");
        var accounts=config.require(scope);
        var customers=db.queryForList("select version,active from customer_entity_accounts where workspace_id=? and entity_id=? and id=?",w,e,p.get("customer_account_id"));
        if(customers.size()!=1 || !Boolean.TRUE.equals(customers.get(0).get("active")) || n(customers.get(0).get("version"))!=n(p.get("customer_account_version")) || accounts.version()!=n(p.get("config_version")) || accounts.paymentVersion()!=n(p.get("payment_config_version")) || accounts.bank()!=n(p.get("bank_account_id"))) throw new DomainException(409,"Customer or posting configuration changed");
        var allocations=db.queryForList("select invoice_id,amount from customer_receipt_allocations where workspace_id=? and entity_id=? and receipt_id=? and active order by invoice_id",w,e,id);
        if(allocations.isEmpty() || allocations.size()>100) throw new DomainException(400,"Allocation required");
        LocalDate when=date(p.get("payment_date")); BigDecimal sum=BigDecimal.ZERO;
        for(var a:allocations) {
            long invoice=n(a.get("invoice_id")); BigDecimal value=(BigDecimal)a.get("amount"); sum=sum.add(value);
            var candidates=db.queryForList("select ar_account_id from sales_invoices i where i.workspace_id=? and i.entity_id=? and i.id=? and i.customer_account_id=? and i.currency=? and i.status='POSTED' and i.invoice_date<=? and not exists(select 1 from sales_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id)",w,e,invoice,p.get("customer_account_id"),p.get("currency"),when);
            if(candidates.isEmpty() || n(candidates.get(0).get("ar_account_id"))!=n(p.get("ar_account_id"))) throw new DomainException(409,"Invoice is unavailable or AR accounts differ");
            BigDecimal gross=db.queryForObject("select coalesce(sum(gross),0) from sales_invoice_lines where workspace_id=? and entity_id=? and invoice_id=? and active",BigDecimal.class,w,e,invoice);
            var points=db.queryForList("""
                select payment_date from customer_receipts p join customer_receipt_allocations a on a.workspace_id=p.workspace_id and a.entity_id=p.entity_id and a.receipt_id=p.id
                where p.workspace_id=? and p.entity_id=? and a.invoice_id=? and a.active and p.status='POSTED' and p.payment_date>=?
                union select cast(? as date)
                """,java.sql.Date.class,w,e,invoice,when,when);
            for(var point:points) {
                BigDecimal allocated=db.queryForObject("""
                    select coalesce(sum(a.amount),0) from customer_receipt_allocations a join customer_receipts p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.receipt_id
                    where a.workspace_id=? and a.entity_id=? and a.invoice_id=? and a.active and p.status='POSTED' and p.payment_date<=?
                    and not exists(select 1 from sales_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.receipt_id=p.id and r.reversal_date<=?)
                    """,BigDecimal.class,w,e,invoice,point,point);
                if(allocated.add(value).compareTo(gross)>0) throw new DomainException(409,"Allocation exceeds invoice balance");
            }
        }
        if(sum.compareTo((BigDecimal)p.get("amount"))!=0) throw new DomainException(400,"Allocations must exactly equal receipt amount");
        var result=posting.post(scope,"CUSTOMER_RECEIPT",Long.toString(id),"customer-receipt:"+id,when,(String)p.get("currency"),"Customer receipt "+p.get("receipt_key"),List.of(new Ledger.Line(n(p.get("bank_account_id")),sum,BigDecimal.ZERO),new Ledger.Line(n(p.get("ar_account_id")),BigDecimal.ZERO,sum)));
        db.update("update customer_receipts set status='POSTED',posting_hash=?,journal_id=?,posted_by=?,posted_at=now(),version=version+1 where workspace_id=? and entity_id=? and id=?",intent,result.journalId(),scope.actor().id(),w,e,id);
        audit.record(scope.actor(),w,e,"POST","CUSTOMER_RECEIPT",Long.toString(id),version,"SUCCESS","receipt posted"); return false;
    }
    @Transactional
    public void reverse(long w,long e,long id,LocalDate when,String reason,String reversalKey) {
        var scope=access.requireContext(w,e,true,"FINANCE"); lockEntity(w,e); var p=row(w,e,id);
        if(when==null || reason==null || reason.isBlank() || reason.length()>500 || reversalKey==null || reversalKey.isBlank() || reversalKey.length()>120) throw new DomainException(400,"Reversal key, date and reason required");
        String key=reversalKey.trim(), intent=hash("RECEIPT:"+id+":"+when+":"+reason.trim());
        var prior=db.queryForList("select payload_hash from sales_reversals where workspace_id=? and entity_id=? and reversal_key=?",w,e,key);
        if(!prior.isEmpty()) { if(intent.equals(prior.get(0).get("payload_hash"))) return; throw new DomainException(409,"Reversal key conflicts"); }
        if(!"POSTED".equals(p.get("status"))) throw new DomainException(409,"Receipt is not posted");
        if(when.isBefore(date(p.get("payment_date")))) throw new DomainException(400,"Reversal predates receipt");
        if(!db.queryForList("select id from sales_reversals where workspace_id=? and entity_id=? and receipt_id=?",w,e,id).isEmpty()) throw new DomainException(409,"Receipt already reversed");
        var result=posting.reverse(scope,n(p.get("journal_id")),"customer-receipt-reversal:"+id,when,reason.trim());
        db.update("insert into sales_reversals(workspace_id,entity_id,source_type,receipt_id,reversal_date,reversal_key,payload_hash,reason,journal_id,created_by) values (?,?,'RECEIPT',?,?,?,?,?,?,?)",w,e,id,when,key,intent,reason.trim(),result.journalId(),scope.actor().id());
        audit.record(scope.actor(),w,e,"REVERSE","CUSTOMER_RECEIPT",Long.toString(id),n(p.get("version")),"SUCCESS",reason.trim());
    }
    private void lockEntity(long w,long e) {
        if (db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",w,e).size()!=1)
            throw new DomainException(404,"Entity not found");
    }
}
