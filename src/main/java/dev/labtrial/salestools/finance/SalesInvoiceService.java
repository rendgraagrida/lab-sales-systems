package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
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
public class SalesInvoiceService {
    public enum SourceType { SHIPMENT, SERVICE_OBLIGATION, SERVICE_TERM }
    public record Line(String lineKey,SourceType sourceType,long sourceId,BigDecimal start,BigDecimal end) {}
    public record Draft(String reference,LocalDate invoiceDate,List<Line> lines) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final PostingService posting;
    private final SalesPostingConfigService config;
    private final ServiceRevenueService revenue;
    public SalesInvoiceService(JdbcTemplate db,AccessControl access,AuditLog audit,PostingService posting,SalesPostingConfigService config,ServiceRevenueService revenue) {
        this.db=db; this.access=access; this.audit=audit; this.posting=posting; this.config=config; this.revenue=revenue;
    }
    private static long n(Object o) { return ((Number)o).longValue(); }
    private static BigDecimal b(Map<String,Object> row,String key) { return (BigDecimal)row.get(key); }
    private static LocalDate date(Object o) { return o instanceof LocalDate d?d:((java.sql.Date)o).toLocalDate(); }
    private static boolean intervalValue(BigDecimal value) {
        return value!=null && value.signum()>=0 && value.scale()<=6 && value.scale()>=-18
                && (long)value.precision()-value.scale()<=12;
    }
    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private Map<String,Object> invoice(long w,long e,long id) {
        var rows=db.queryForList("select * from sales_invoices where workspace_id=? and entity_id=? and id=?",w,e,id);
        if(rows.isEmpty()) throw new DomainException(404,"Invoice not found"); return rows.get(0);
    }
    private Map<String,Object> source(long w,long e,SourceType type,long id) {
        String sql=switch(type) {
            case SHIPMENT -> """
                select sl.slice_start,sl.slice_end,sl.ar_account_id,sl.unbilled_account_id,sl.output_account_id,
                  s.shipment_date,o.customer_account_id,o.currency,o.terms_days,
                  ol.quantity,ol.quantity_scale,ol.unit_price,ol.subtotal,ol.discount,ol.net,ol.tax,ol.gross
                from shipment_lines sl join shipments s on s.workspace_id=sl.workspace_id and s.entity_id=sl.entity_id and s.id=sl.shipment_id
                join sales_order_lines ol on ol.workspace_id=sl.workspace_id and ol.entity_id=sl.entity_id and ol.id=sl.order_line_id
                join sales_orders o on o.workspace_id=ol.workspace_id and o.entity_id=ol.entity_id and o.id=ol.order_id and o.id=s.order_id
                where sl.workspace_id=? and sl.entity_id=? and sl.id=? and sl.active and s.status='POSTED' and o.sealed
                """;
            case SERVICE_OBLIGATION -> """
                select a.*,o.customer_account_id,a.accepted_date source_date from sales_service_obligations a
                join sales_order_lines l on l.workspace_id=a.workspace_id and l.entity_id=a.entity_id and l.id=a.order_line_id
                join sales_orders o on o.workspace_id=l.workspace_id and o.entity_id=l.entity_id and o.id=l.order_id and o.sealed
                where a.workspace_id=? and a.entity_id=? and a.id=? and a.kind='ONBOARDING' and a.status<>'CANCELLED'
                """;
            case SERVICE_TERM -> """
                select t.*,t.net_fee net,c.customer_account_id,t.accepted_date source_date from service_contract_terms t
                join service_contract_assets ca on ca.workspace_id=t.workspace_id and ca.entity_id=t.entity_id and ca.id=t.contract_asset_id
                join service_contracts c on c.workspace_id=ca.workspace_id and c.entity_id=ca.entity_id and c.id=ca.contract_id
                where t.workspace_id=? and t.entity_id=? and t.id=? and t.status='VALIDATED'
                """;
        };
        var rows=db.queryForList(sql,w,e,id);
        if(rows.size()!=1) throw new DomainException(404,"Eligible invoice source not found");
        return rows.get(0);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(long w,long e,String q,String status) {
        access.requireContext(w,e,false,"FINANCE");
        if(!List.of("","DRAFT","POSTED").contains(status)) throw new DomainException(400,"Invalid invoice status");
        return db.queryForList("select * from sales_invoices where workspace_id=? and entity_id=? and reference ilike ? and (?='' or status=?) order by id desc",w,e,"%"+(q==null?"":q)+"%",status,status);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> detail(long w,long e,long id) { access.requireContext(w,e,false,"FINANCE"); return invoice(w,e,id); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> lines(long w,long e,long id) {
        access.requireContext(w,e,false,"FINANCE"); invoice(w,e,id);
        return db.queryForList("select * from sales_invoice_lines where workspace_id=? and entity_id=? and invoice_id=? and active order by id",w,e,id);
    }
    @Transactional
    public long save(long w,long e,Long id,long version,Draft draft) {
        var scope=access.requireContext(w,e,true,"FINANCE");
        if(draft==null || draft.reference()==null || draft.reference().isBlank() || draft.reference().length()>120 || draft.invoiceDate()==null || draft.lines()==null || draft.lines().isEmpty() || draft.lines().size()>100) throw new DomainException(400,"Invalid invoice details");
        var keys=new HashSet<String>(); var canonical=new StringBuilder(draft.invoiceDate().toString());
        for(var line:draft.lines()) {
            if(line==null || line.sourceType()==null || line.lineKey()==null || line.lineKey().isBlank() || line.lineKey().length()>120 || !keys.add(line.lineKey().trim()) || line.sourceId()<=0 || line.start()==null || line.end()==null || line.start().signum()<0 || line.end().signum()<0) throw new DomainException(400,"Invalid invoice line");
            if(line.sourceType()==SourceType.SHIPMENT && (!intervalValue(line.start()) || !intervalValue(line.end()))) throw new DomainException(400,"Invalid shipment interval");
            if(line.sourceType()!=SourceType.SHIPMENT && (line.start().scale()>2 || line.end().scale()>2 || line.start().scale() < -18 || line.end().scale() < -18 || (long)line.start().precision()-line.start().scale()>16 || (long)line.end().precision()-line.end().scale()>16)) throw new DomainException(400,"Invalid service fee interval");
            String key=line.lineKey().trim(); canonical.append('|').append(key.length()).append(':').append(key).append(':');
            if(line.sourceType()!=SourceType.SHIPMENT) canonical.append(line.sourceType()).append(':');
            canonical.append(line.sourceId()).append(':').append(line.start().stripTrailingZeros().toPlainString()).append(':').append(line.end().stripTrailingZeros().toPlainString());
        }
        String creationHash=hash(canonical.toString());
        if(id==null) {
            var prior=db.queryForList("select id,creation_hash from sales_invoices where workspace_id=? and entity_id=? and create_key=?",w,e,draft.reference().trim());
            if(!prior.isEmpty()) { if(creationHash.equals(prior.get(0).get("creation_hash"))) return n(prior.get(0).get("id")); throw new DomainException(409,"Invoice creation key conflicts"); }
        }
        var sources=new ArrayList<Map<String,Object>>(); var slices=new ArrayList<SourceAmounts.Slice>();
        Map<String,Object> first=null;
        for(var line:draft.lines()) {
            var s=source(w,e,line.sourceType(),line.sourceId());
            boolean goods=line.sourceType()==SourceType.SHIPMENT;
            if(draft.invoiceDate().isBefore(date(s.get(goods?"shipment_date":"source_date")))) throw new DomainException(400,"Invoice predates source");
            if(first==null) first=s;
            else if(n(first.get("customer_account_id"))!=n(s.get("customer_account_id")) || !first.get("currency").equals(s.get("currency")) || n(first.get("terms_days"))!=n(s.get("terms_days")) || n(first.get("ar_account_id"))!=n(s.get("ar_account_id"))) throw new DomainException(409,"Customer, currency, terms or AR account differ");
            SourceAmounts.Slice slice;
            if(goods) {
                if(line.start().compareTo(b(s,"slice_start"))<0 || line.end().compareTo(b(s,"slice_end"))>0 || line.start().stripTrailingZeros().scale()>n(s.get("quantity_scale")) || line.end().stripTrailingZeros().scale()>n(s.get("quantity_scale"))) throw new DomainException(400,"Interval is outside delivered source");
                var snapshot=new SourceAmounts.Snapshot(b(s,"quantity"),b(s,"unit_price"),b(s,"subtotal"),b(s,"discount"),b(s,"net"),b(s,"tax"),b(s,"gross"));
                slice=SourceAmounts.slice(snapshot,line.start(),line.end());
            } else slice=SourceAmounts.feeSlice(b(s,"net"),b(s,"discount"),b(s,"tax"),line.start(),line.end());
            sources.add(s); slices.add(slice);
        }
        for(int x=0;x<draft.lines().size();x++) for(int y=0;y<x;y++) {
            var a=draft.lines().get(x); var c=draft.lines().get(y);
            if(a.sourceType()==c.sourceType() && a.sourceId()==c.sourceId() && a.start().compareTo(c.end())<0 && c.start().compareTo(a.end())<0) throw new DomainException(400,"Invoice intervals overlap");
        }
        boolean creating=id==null;
        if(!db.queryForList("select id from sales_invoices where workspace_id=? and entity_id=? and reference=? and id<>?",w,e,draft.reference().trim(),id==null?0:id).isEmpty()) throw new DomainException(409,"Invoice reference already exists");
        if(creating) {
            id=db.queryForObject("insert into sales_invoices(workspace_id,entity_id,reference,create_key,creation_hash,customer_account_id,currency,invoice_date,due_date,terms_days,ar_account_id,status,created_by) values (?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?) returning id",Long.class,w,e,draft.reference().trim(),draft.reference().trim(),creationHash,first.get("customer_account_id"),first.get("currency"),draft.invoiceDate(),draft.invoiceDate().plusDays(n(first.get("terms_days"))),first.get("terms_days"),first.get("ar_account_id"),scope.actor().id());
        } else {
            var old=invoice(w,e,id); if(!"DRAFT".equals(old.get("status")) || n(old.get("version"))!=version) throw new DomainException(409,"Invoice changed");
            db.update("update sales_invoices set reference=?,customer_account_id=?,currency=?,invoice_date=?,due_date=?,terms_days=?,ar_account_id=?,version=version+1 where workspace_id=? and entity_id=? and id=?",draft.reference().trim(),first.get("customer_account_id"),first.get("currency"),draft.invoiceDate(),draft.invoiceDate().plusDays(n(first.get("terms_days"))),first.get("terms_days"),first.get("ar_account_id"),w,e,id);
            db.update("update sales_invoice_lines set active=false where workspace_id=? and entity_id=? and invoice_id=? and active",w,e,id);
        }
        for(int i=0;i<draft.lines().size();i++) {
            var line=draft.lines().get(i); var s=sources.get(i); var a=slices.get(i);
            boolean goods=line.sourceType()==SourceType.SHIPMENT;
            db.update("""
                insert into sales_invoice_lines(workspace_id,entity_id,invoice_id,line_key,source_type,shipment_line_id,service_obligation_id,service_term_id,slice_start,slice_end,fee_start,fee_end,subtotal,discount,net,tax,gross,unbilled_account_id,deferred_account_id,output_account_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(workspace_id,entity_id,invoice_id,line_key) do update set source_type=excluded.source_type,shipment_line_id=excluded.shipment_line_id,service_obligation_id=excluded.service_obligation_id,service_term_id=excluded.service_term_id,slice_start=excluded.slice_start,slice_end=excluded.slice_end,fee_start=excluded.fee_start,fee_end=excluded.fee_end,subtotal=excluded.subtotal,discount=excluded.discount,net=excluded.net,tax=excluded.tax,gross=excluded.gross,unbilled_account_id=excluded.unbilled_account_id,deferred_account_id=excluded.deferred_account_id,output_account_id=excluded.output_account_id,active=true
                """,w,e,id,line.lineKey().trim(),line.sourceType().name(),goods?line.sourceId():null,
                    line.sourceType()==SourceType.SERVICE_OBLIGATION?line.sourceId():null,line.sourceType()==SourceType.SERVICE_TERM?line.sourceId():null,
                    goods?a.start():null,goods?a.end():null,goods?null:a.start(),goods?null:a.end(),a.subtotal(),a.discount(),a.net(),a.tax(),a.gross(),s.get("unbilled_account_id"),goods?null:s.get("deferred_account_id"),s.get("output_account_id"));
        }
        audit.record(scope.actor(),w,e,"SAVE","SALES_INVOICE",id.toString(),creating?1:version+1,"SUCCESS","invoice draft"); return id;
    }
    @Transactional
    public boolean post(long w,long e,long id,long version) {
        var scope=access.requireContext(w,e,true,"FINANCE"); lockEntity(w,e); var inv=invoice(w,e,id); String intent=hash(id+":"+version);
        if("POSTED".equals(inv.get("status"))) { if(intent.equals(inv.get("posting_hash"))) return true; throw new DomainException(409,"Invoice already posted with different intent"); }
        if(n(inv.get("version"))!=version) throw new DomainException(409,"Invoice changed");
        var lines=db.queryForList("select * from sales_invoice_lines where workspace_id=? and entity_id=? and invoice_id=? and active order by id",w,e,id);
        if(lines.isEmpty()) throw new DomainException(400,"Invoice lines required");
        if(lines.stream().anyMatch(l->"SHIPMENT".equals(l.get("source_type")))) config.require(scope);
        if(lines.stream().anyMatch(l->!"SHIPMENT".equals(l.get("source_type")))) config.requireService(scope);
        var credits=new TreeMap<Long,BigDecimal>(); BigDecimal gross=BigDecimal.ZERO;
        var groups=new TreeMap<String,Map<String,Object>>(); var amounts=new TreeMap<String,BigDecimal>();
        LocalDate when=date(inv.get("invoice_date"));
        for(var line:lines) {
            SourceType type=SourceType.valueOf((String)line.get("source_type")); boolean goods=type==SourceType.SHIPMENT;
            String column=goods?"shipment_line_id":type==SourceType.SERVICE_TERM?"service_term_id":"service_obligation_id";
            long sourceId=n(line.get(column)); var s=source(w,e,type,sourceId);
            if(when.isBefore(date(s.get(goods?"shipment_date":"source_date"))) || n(s.get("customer_account_id"))!=n(inv.get("customer_account_id")) || !s.get("currency").equals(inv.get("currency")) || n(s.get("terms_days"))!=n(inv.get("terms_days")) || n(s.get("ar_account_id"))!=n(inv.get("ar_account_id"))) throw new DomainException(409,"Invoice source changed");
            String start=goods?"slice_start":"fee_start",end=goods?"slice_end":"fee_end";
            var overlaps=db.queryForList("""
                select l.id from sales_invoice_lines l join sales_invoices i on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id
                where l.workspace_id=? and l.entity_id=? and l.%s=? and l.active and i.status='POSTED' and l.%s<? and l.%s>?
                  and not exists(select 1 from sales_reversals empty_reversal where empty_reversal.workspace_id=i.workspace_id and empty_reversal.entity_id=i.entity_id and empty_reversal.invoice_id=i.id and empty_reversal.reversal_date=i.invoice_date)
                  and not exists(select 1 from sales_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?)
                """.formatted(column,start,end),w,e,sourceId,line.get(end),line.get(start),when);
            if(!overlaps.isEmpty()) throw new DomainException(409,"Source interval already invoiced");
            SourceAmounts.Slice expected=goods
                ? SourceAmounts.slice(new SourceAmounts.Snapshot(b(s,"quantity"),b(s,"unit_price"),b(s,"subtotal"),b(s,"discount"),b(s,"net"),b(s,"tax"),b(s,"gross")),b(line,start),b(line,end))
                : SourceAmounts.feeSlice(b(s,"net"),b(s,"discount"),b(s,"tax"),b(line,start),b(line,end));
            if(expected.net().compareTo(b(line,"net"))!=0 || expected.tax().compareTo(b(line,"tax"))!=0 || expected.discount().compareTo(b(line,"discount"))!=0 || expected.subtotal().compareTo(b(line,"subtotal"))!=0 || expected.gross().compareTo(b(line,"gross"))!=0 || n(line.get("unbilled_account_id"))!=n(s.get("unbilled_account_id")) || !java.util.Objects.equals(line.get("output_account_id"),s.get("output_account_id")) || (!goods && n(line.get("deferred_account_id"))!=n(s.get("deferred_account_id"))) || (goods && (b(line,start).compareTo(b(s,"slice_start"))<0 || b(line,end).compareTo(b(s,"slice_end"))>0 || b(line,start).stripTrailingZeros().scale()>n(s.get("quantity_scale")) || b(line,end).stripTrailingZeros().scale()>n(s.get("quantity_scale")))))
                throw new DomainException(409,"Invoice source snapshot changed");
            gross=gross.add(b(line,"gross"));
            if(b(line,"tax").signum()>0) credits.merge(n(line.get("output_account_id")),b(line,"tax"),BigDecimal::add);
            if(goods) credits.merge(n(line.get("unbilled_account_id")),b(line,"net"),BigDecimal::add);
            else {
                String group=type.name()+":"+String.format("%020d",sourceId);
                groups.putIfAbsent(group,line); amounts.merge(group,b(line,"net"),BigDecimal::add);
            }
        }
        var effects=new TreeMap<String,ServiceRevenueService.BillingEffect>();
        for(var entry:groups.entrySet()) {
            var line=entry.getValue(); boolean isTerm="SERVICE_TERM".equals(line.get("source_type"));
            long sourceId=n(line.get(isTerm?"service_term_id":"service_obligation_id"));
            var effect=revenue.billingEffect(scope,isTerm,sourceId,when,amounts.get(entry.getKey()));
            effects.put(entry.getKey(),effect);
            credits.merge(effect.unbilledAccount(),effect.unbilledDelta().negate(),BigDecimal::add);
            credits.merge(effect.deferredAccount(),effect.deferredDelta(),BigDecimal::add);
        }
        var journal=new ArrayList<Ledger.Line>();
        if(gross.signum()>0) journal.add(new Ledger.Line(n(inv.get("ar_account_id")),gross,BigDecimal.ZERO));
        credits.forEach((account,value)-> { if(value.signum()!=0) journal.add(new Ledger.Line(account,value.signum()<0?value.negate():BigDecimal.ZERO,value.signum()>0?value:BigDecimal.ZERO)); });
        var result=posting.post(scope,"SALES_INVOICE",Long.toString(id),"sales-invoice:"+id,when,(String)inv.get("currency"),"Sales invoice "+inv.get("reference"),journal);
        db.update("update sales_invoices set status='POSTED',posting_hash=?,journal_id=?,posted_by=?,posted_at=now(),version=version+1 where workspace_id=? and entity_id=? and id=?",intent,result.journalId(),scope.actor().id(),w,e,id);
        for(var entry:effects.entrySet()) revenue.recordBilling(scope,entry.getValue(),when,"invoice:"+id+":"+entry.getKey(),n(groups.get(entry.getKey()).get("id")),null,result.journalId());
        audit.record(scope.actor(),w,e,"POST","SALES_INVOICE",Long.toString(id),version,"SUCCESS","invoice posted"); return false;
    }
    @Transactional
    public void reverse(long w,long e,long id,LocalDate when,String reason,String reversalKey) {
        var scope=access.requireContext(w,e,true,"FINANCE"); lockEntity(w,e); var inv=invoice(w,e,id);
        if(when==null || reason==null || reason.isBlank() || reason.length()>500 || reversalKey==null || reversalKey.isBlank() || reversalKey.length()>120) throw new DomainException(400,"Reversal key, date and reason required");
        String key=reversalKey.trim(), intent=hash("INVOICE:"+id+":"+when+":"+reason.trim());
        var prior=db.queryForList("select payload_hash from sales_reversals where workspace_id=? and entity_id=? and reversal_key=?",w,e,key);
        if(!prior.isEmpty()) { if(intent.equals(prior.get(0).get("payload_hash"))) return; throw new DomainException(409,"Reversal key conflicts"); }
        if(!"POSTED".equals(inv.get("status"))) throw new DomainException(409,"Invoice is not posted");
        if(when.isBefore(date(inv.get("invoice_date")))) throw new DomainException(400,"Reversal predates invoice");
        if(!db.queryForList("select id from sales_reversals where workspace_id=? and entity_id=? and invoice_id=?",w,e,id).isEmpty()) throw new DomainException(409,"Invoice already reversed");
        var cash=db.queryForList("""
            select p.id from customer_receipt_allocations a join customer_receipts p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.receipt_id
            where a.workspace_id=? and a.entity_id=? and a.invoice_id=? and a.active and p.status='POSTED'
              and not exists(select 1 from sales_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.receipt_id=p.id and r.reversal_date<=greatest(p.payment_date,cast(? as date)))
            """,w,e,id,when);
        if(!cash.isEmpty()) throw new DomainException(409,"Reverse all relevant customer receipts before correcting invoice");
        var lines=db.queryForList("select * from sales_invoice_lines where workspace_id=? and entity_id=? and invoice_id=? and active order by id",w,e,id);
        var groups=new TreeMap<String,Map<String,Object>>(); var amounts=new TreeMap<String,BigDecimal>();
        var debits=new TreeMap<Long,BigDecimal>(); BigDecimal gross=BigDecimal.ZERO;
        for(var line:lines) {
            gross=gross.add(b(line,"gross"));
            if(b(line,"tax").signum()>0) debits.merge(n(line.get("output_account_id")),b(line,"tax"),BigDecimal::add);
            if("SHIPMENT".equals(line.get("source_type"))) debits.merge(n(line.get("unbilled_account_id")),b(line,"net"),BigDecimal::add);
            else {
                boolean isTerm="SERVICE_TERM".equals(line.get("source_type")); long sourceId=n(line.get(isTerm?"service_term_id":"service_obligation_id"));
                String group=line.get("source_type")+":"+String.format("%020d",sourceId);
                groups.putIfAbsent(group,line); amounts.merge(group,b(line,"net"),BigDecimal::add);
            }
        }
        var effects=new TreeMap<String,ServiceRevenueService.BillingEffect>();
        for(var entry:groups.entrySet()) {
            var line=entry.getValue(); boolean isTerm="SERVICE_TERM".equals(line.get("source_type"));
            var effect=revenue.billingEffect(scope,isTerm,n(line.get(isTerm?"service_term_id":"service_obligation_id")),when,amounts.get(entry.getKey()).negate());
            effects.put(entry.getKey(),effect);
            debits.merge(effect.unbilledAccount(),effect.unbilledDelta(),BigDecimal::add);
            debits.merge(effect.deferredAccount(),effect.deferredDelta().negate(),BigDecimal::add);
        }
        PostingService.Result result;
        if(groups.isEmpty()) result=inv.get("journal_id")==null ? posting.post(scope,"SALES_INVOICE_REVERSAL",Long.toString(id),"sales-invoice-reversal:"+id,when,(String)inv.get("currency"),reason.trim(),List.of()) : posting.reverse(scope,n(inv.get("journal_id")),"sales-invoice-reversal:"+id,when,reason.trim());
        else {
            var journal=new ArrayList<Ledger.Line>();
            if(gross.signum()>0) journal.add(new Ledger.Line(n(inv.get("ar_account_id")),BigDecimal.ZERO,gross));
            debits.forEach((account,value)-> { if(value.signum()!=0) journal.add(new Ledger.Line(account,value.signum()>0?value:BigDecimal.ZERO,value.signum()<0?value.negate():BigDecimal.ZERO)); });
            result=inv.get("journal_id")==null ? posting.post(scope,"SALES_INVOICE_REVERSAL",Long.toString(id),"sales-invoice-reversal:"+id,when,(String)inv.get("currency"),reason.trim(),journal)
                : posting.reverseAdjusted(scope,n(inv.get("journal_id")),"sales-invoice-reversal:"+id,when,reason.trim(),journal);
        }
        long reversal=db.queryForObject("insert into sales_reversals(workspace_id,entity_id,source_type,invoice_id,reversal_date,reversal_key,payload_hash,reason,journal_id,created_by) values (?,?,'INVOICE',?,?,?,?,?,?,?) returning id",Long.class,w,e,id,when,key,intent,reason.trim(),result.journalId(),scope.actor().id());
        for(var entry:effects.entrySet()) revenue.recordBilling(scope,entry.getValue(),when,"reversal:"+id+":"+entry.getKey(),n(groups.get(entry.getKey()).get("id")),reversal,result.journalId());
        audit.record(scope.actor(),w,e,"REVERSE","SALES_INVOICE",Long.toString(id),n(inv.get("version")),"SUCCESS",reason.trim());
    }
    private void lockEntity(long w,long e) {
        if (db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",w,e).size()!=1)
            throw new DomainException(404,"Entity not found");
    }
}
