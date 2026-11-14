package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceRevenueService {
    public record OpeningStatement(long termId, LocalDate cutoff, BigDecimal billed, BigDecimal earned,
                                   String billingReference, String settlementReference) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final PostingService posting;

    public ServiceRevenueService(JdbcTemplate db, AccessControl access, PostingService posting) {
        this.db=db; this.access=access; this.posting=posting;
    }

    private AccessControl.Scope checked(AccessControl.Scope supplied) {
        if (supplied==null || supplied.entityId()==null || supplied.actor()==null)
            throw new DomainException(400,"Entity scope required");
        var scope=access.requireContext(supplied.workspaceId(),supplied.entityId(),true,
                "FINANCE","OPERATIONS","INSIDE_SALES","SALES_MANAGER");
        if (scope.actor().id()!=supplied.actor().id()) throw new DomainException(403,"Invalid service scope");
        if (db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",scope.workspaceId(),scope.entityId()).size()!=1)
            throw new DomainException(404,"Entity not found");
        return scope;
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static LocalDate date(Object value) { return value instanceof LocalDate d ? d : ((java.sql.Date)value).toLocalDate(); }
    private static boolean money(BigDecimal value) {
        return value!=null && value.signum()>=0 && value.scale()<=2 && value.scale()>=-18
                && (long)value.precision()-value.scale()<=16;
    }
    private Map<String,Object> term(AccessControl.Scope s,long id) {
        var rows=db.queryForList("select * from service_contract_terms where workspace_id=? and entity_id=? and id=? for update",s.workspaceId(),s.entityId(),id);
        if (rows.size()!=1) throw new DomainException(404,"Service term not found");
        return rows.get(0);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void ensureSchedule(AccessControl.Scope supplied,long termId) {
        var s=checked(supplied); var term=term(s,termId);
        if (!"VALIDATED".equals(term.get("status"))) throw new DomainException(409,"Service term is not validated");
        var existing=db.queryForList("select id,sealed,source_hash from revenue_schedules where workspace_id=? and entity_id=? and term_id=? for update",s.workspaceId(),s.entityId(),termId);
        if (!existing.isEmpty()) {
            if (existing.size()!=1 || !Boolean.TRUE.equals(existing.get(0).get("sealed")) || !term.get("source_hash").equals(existing.get(0).get("source_hash")))
                throw new DomainException(409,"Service schedule conflicts with validated term");
            return;
        }
        LocalDate start=date(term.get("coverage_start")), end=date(term.get("coverage_end"));
        BigDecimal fee=(BigDecimal)term.get("net_fee");
        if (!end.isAfter(start) || !money(fee)) throw new DomainException(409,"Invalid service term snapshot");
        var ends=new ArrayList<LocalDate>();
        for (int n=1;;n++) {
            LocalDate boundary=start.plusMonths(n);
            if (!boundary.isBefore(end)) { ends.add(end); break; }
            ends.add(boundary);
        }
        int count=ends.size(); BigDecimal regular=fee.divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);
        BigDecimal last=fee.subtract(regular.multiply(BigDecimal.valueOf(count-1L)));
        if (last.signum()<0) {
            regular=fee.movePointRight(2).divideToIntegralValue(BigDecimal.valueOf(count)).movePointLeft(2).setScale(2);
            last=fee.subtract(regular.multiply(BigDecimal.valueOf(count-1L)));
        }
        long schedule=db.queryForObject("insert into revenue_schedules(workspace_id,entity_id,term_id,schedule_version,source_hash,coverage_start,coverage_end,net_fee,sealed,created_by) values (?,?,?,1,?,?,?,?,false,?) returning id",Long.class,s.workspaceId(),s.entityId(),termId,term.get("source_hash"),start,end,fee,s.actor().id());
        LocalDate from=start;
        for (int i=0;i<count;i++) {
            LocalDate until=ends.get(i);
            db.update("insert into revenue_schedule_lines(workspace_id,entity_id,schedule_id,period_no,period_start,period_end,amount) values (?,?,?,?,?,?,?)",s.workspaceId(),s.entityId(),schedule,i+1,from,until,i==count-1?last:regular);
            from=until;
        }
        db.update("update revenue_schedules set sealed=true where workspace_id=? and entity_id=? and id=? and not sealed",s.workspaceId(),s.entityId(),schedule);
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static boolean text(String value) { return value!=null && !value.isBlank() && value.length()<=120; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void recordOpening(AccessControl.Scope supplied,long openingSourceId,OpeningStatement statement) {
        var s=checked(supplied);
        access.requireContext(s.workspaceId(),s.entityId(),true,"FINANCE");
        if (statement==null || statement.termId()<=0 || statement.cutoff()==null || !money(statement.billed())
                || !money(statement.earned()) || !text(statement.billingReference()) || !text(statement.settlementReference()))
            throw new DomainException(400,"Invalid service opening statement");
        var source=db.queryForList("select source_key,revision,posting_date from opening_balance_sources where workspace_id=? and entity_id=? and id=? and status='POSTED'",s.workspaceId(),s.entityId(),openingSourceId);
        if (source.size()!=1) throw new DomainException(404,"Opening source not found");
        var t=term(s,statement.termId());
        BigDecimal net=(BigDecimal)t.get("net_fee");
        if (!"OPENING".equals(t.get("source_kind")) || !"VALIDATED".equals(t.get("status"))
                || !(source.get(0).get("source_key")+":"+number(source.get(0),"revision")).equals(t.get("opening_reference"))
                || !date(source.get(0).get("posting_date")).equals(statement.cutoff())
                || statement.billed().compareTo(net)>0 || statement.earned().compareTo(net)>0)
            throw new DomainException(409,"Opening statement does not match the validated term and opening source");
        if (statement.billed().compareTo(net)!=0)
            throw new DomainException(409,"Partial service opening billing requires explicit fee interval authority and is unsupported");
        String billingReference=statement.billingReference().trim(), settlementReference=statement.settlementReference().trim();
        String payload=hash(statement.termId()+"|"+openingSourceId+"|"+statement.cutoff()+"|"
                +statement.billed().setScale(2)+"|"+statement.earned().setScale(2)+"|"
                +billingReference.length()+":"+billingReference+settlementReference.length()+":"+settlementReference);
        var prior=db.queryForList("select payload_hash from service_opening_balances where workspace_id=? and entity_id=? and term_id=?",s.workspaceId(),s.entityId(),statement.termId());
        if (!prior.isEmpty()) {
            if (prior.size()==1 && payload.equals(prior.get(0).get("payload_hash"))) return;
            throw new DomainException(409,"Service opening statement conflicts with existing source");
        }
        ensureSchedule(s,statement.termId());
        var covered=db.queryForList("""
            select l.id,l.amount from revenue_schedule_lines l join revenue_schedules r
              on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.schedule_id
            where r.workspace_id=? and r.entity_id=? and r.term_id=? and r.sealed and l.period_end<=?
            order by l.period_no
            """,s.workspaceId(),s.entityId(),statement.termId(),statement.cutoff());
        BigDecimal total=BigDecimal.ZERO;
        for (var line:covered) total=total.add((BigDecimal)line.get("amount"));
        if (total.compareTo(statement.earned())!=0)
            throw new DomainException(409,"Opening earned amount must equal all schedule periods covered at cutoff");
        long opening=db.queryForObject("""
            insert into service_opening_balances(workspace_id,entity_id,term_id,opening_source_id,cutoff,net_fee,billed,earned,billing_reference,settlement_reference,payload_hash,created_by)
            values (?,?,?,?,?,?,?,?,?,?,?,?) returning id
            """,Long.class,s.workspaceId(),s.entityId(),statement.termId(),openingSourceId,statement.cutoff(),net,statement.billed(),statement.earned(),statement.billingReference().trim(),statement.settlementReference().trim(),payload,s.actor().id());
        for (var line:covered) db.update("insert into service_opening_periods(workspace_id,entity_id,opening_balance_id,schedule_line_id) values (?,?,?,?)",
                s.workspaceId(),s.entityId(),opening,number(line,"id"));
        BigDecimal unbilled=statement.earned().subtract(statement.billed()).max(BigDecimal.ZERO);
        BigDecimal deferred=statement.billed().subtract(statement.earned()).max(BigDecimal.ZERO);
        db.update("""
            insert into service_balance_postings(workspace_id,entity_id,term_id,effective_date,event_kind,event_key,payload_hash,unbilled_delta,deferred_delta,opening_balance_id,created_by)
            values (?,?,?,?,'OPENING',?,?,?,?,?,?)
            """,s.workspaceId(),s.entityId(),statement.termId(),statement.cutoff(),"opening:"+opening,payload,unbilled,deferred,opening,s.actor().id());
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void requireCancelable(AccessControl.Scope supplied,Long obligationId,Long termId,LocalDate cancellationDate) {
        var s=checked(supplied);
        if ((obligationId==null)==(termId==null) || cancellationDate==null)
            throw new DomainException(400,"Exactly one service source and cancellation date are required");
        long w=s.workspaceId(), e=s.entityId();
        if (termId!=null) {
            var t=term(s,termId);
            if (!"VALIDATED".equals(t.get("status")) || !cancellationDate.isBefore(date(t.get("coverage_start"))))
                throw new DomainException(409,"Only future validated coverage can be cancelled");
            boolean earned=Boolean.TRUE.equals(db.queryForObject("""
                select exists(select 1 from revenue_recognitions r join revenue_schedule_lines l
                  on l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.id=r.schedule_line_id
                  join revenue_schedules s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.id=l.schedule_id
                  where s.workspace_id=? and s.entity_id=? and s.term_id=?)
                  or exists(select 1 from service_opening_balances where workspace_id=? and entity_id=? and term_id=? and (earned<>0 or billed<>0))
                """,Boolean.class,w,e,termId,w,e,termId));
            if (earned) throw new DomainException(409,"Recognized or opening service coverage cannot be cancelled");
        } else {
            var rows=db.queryForList("select kind,status from sales_service_obligations where workspace_id=? and entity_id=? and id=? for update",w,e,obligationId);
            if (rows.size()!=1) throw new DomainException(404,"Service obligation not found");
            if ("CANCELLED".equals(rows.get(0).get("status")) || Boolean.TRUE.equals(db.queryForObject(
                    "select exists(select 1 from service_completions where workspace_id=? and entity_id=? and obligation_id=?)",
                    Boolean.class,w,e,obligationId))) throw new DomainException(409,"Completed or cancelled obligation cannot be cancelled");
        }
        String column=termId==null?"service_obligation_id":"service_term_id";
        long sourceId=termId==null?obligationId:termId;
        boolean billed=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from sales_invoice_lines l join sales_invoices i
              on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id
              where l.workspace_id=? and l.entity_id=? and l.%s=? and l.active and i.status='POSTED'
                and not exists(select 1 from sales_reversals r where r.workspace_id=i.workspace_id
                  and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?))
            """.formatted(column),Boolean.class,w,e,sourceId,cancellationDate));
        if (billed) throw new DomainException(409,"Correct all service invoices and cash before cancellation");
    }

    private record Balance(BigDecimal earned,BigDecimal billed,BigDecimal unbilled,BigDecimal deferred) {}
    private Balance balances(AccessControl.Scope s,boolean isTerm,long id,LocalDate when) {
        long w=s.workspaceId(),e=s.entityId();
        BigDecimal earned;
        if (isTerm) {
            earned=db.queryForObject("""
                select coalesce((select sum(r.amount) from revenue_recognitions r join revenue_schedule_lines l
                  on l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.id=r.schedule_line_id
                  join revenue_schedules z on z.workspace_id=l.workspace_id and z.entity_id=l.entity_id and z.id=l.schedule_id
                  where z.workspace_id=? and z.entity_id=? and z.term_id=? and r.posting_date<=?),0)
                  +coalesce((select sum(earned) from service_opening_balances where workspace_id=? and entity_id=? and term_id=? and cutoff<=?),0)
                """,BigDecimal.class,w,e,id,when,w,e,id,when);
        } else {
            earned=db.queryForObject("select coalesce(sum(amount),0) from service_completions where workspace_id=? and entity_id=? and obligation_id=? and posting_date<=?",BigDecimal.class,w,e,id,when);
        }
        String column=isTerm?"service_term_id":"service_obligation_id";
        BigDecimal billed=db.queryForObject("""
            select coalesce(sum(l.net),0) from sales_invoice_lines l join sales_invoices i
              on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id
            where l.workspace_id=? and l.entity_id=? and l.%s=? and l.active and i.status='POSTED' and i.invoice_date<=?
              and not exists(select 1 from sales_reversals r where r.workspace_id=i.workspace_id
                and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?)
            """.formatted(column),BigDecimal.class,w,e,id,when,when);
        if (isTerm) billed=billed.add(db.queryForObject("select coalesce(sum(billed),0) from service_opening_balances where workspace_id=? and entity_id=? and term_id=? and cutoff<=?",BigDecimal.class,w,e,id,when));
        return new Balance(earned,billed,earned.subtract(billed).max(BigDecimal.ZERO),billed.subtract(earned).max(BigDecimal.ZERO));
    }

    private static void deltaLine(List<Ledger.Line> lines,long account,BigDecimal delta,boolean debitNormal) {
        if (delta.signum()==0) return;
        boolean debit=(delta.signum()>0)==debitNormal;
        lines.add(new Ledger.Line(account,debit?delta.abs():BigDecimal.ZERO,debit?BigDecimal.ZERO:delta.abs()));
    }

    private void reconcileFuture(AccessControl.Scope s,boolean isTerm,long sourceId,LocalDate after,
                                  String eventKey,String currency,long unbilledAccount,long deferredAccount) {
        long w=s.workspaceId(),e=s.entityId(); String column=isTerm?"term_id":"obligation_id";
        BigDecimal net=db.queryForObject("select "+(isTerm?"net_fee":"net")+" from "+(isTerm?"service_contract_terms":"sales_service_obligations")+" where workspace_id=? and entity_id=? and id=?",BigDecimal.class,w,e,sourceId);
        var points=db.queryForList("select distinct effective_date from service_balance_postings where workspace_id=? and entity_id=? and "+column+"=? and effective_date>? order by effective_date",java.sql.Date.class,w,e,sourceId,after);
        for (var point:points) {
            LocalDate when=point.toLocalDate(); Balance target=balances(s,isTerm,sourceId,when);
            if (target.earned().signum()<0 || target.billed().signum()<0 || target.earned().compareTo(net)>0 || target.billed().compareTo(net)>0)
                throw new DomainException(409,"Future service balance exceeds its approved entitlement");
            var actual=db.queryForMap("select coalesce(sum(unbilled_delta),0) u,coalesce(sum(deferred_delta),0) d from service_balance_postings where workspace_id=? and entity_id=? and "+column+"=? and effective_date<=?",w,e,sourceId,when);
            BigDecimal u=target.unbilled().subtract((BigDecimal)actual.get("u")), d=target.deferred().subtract((BigDecimal)actual.get("d"));
            if (u.signum()==0 && d.signum()==0) continue;
            if (u.compareTo(d)!=0) throw new DomainException(409,"Service timeline cannot be reconciled");
            var journal=new ArrayList<Ledger.Line>(); deltaLine(journal,unbilledAccount,u,true); deltaLine(journal,deferredAccount,d,false);
            var result=posting.postServiceReclassification(s,eventKey+":"+when,eventKey+":reclass:"+when,when,currency,journal);
            String payload=hash(eventKey+"|"+when+"|"+u+"|"+d);
            db.update("insert into service_balance_postings(workspace_id,entity_id,"+column+",effective_date,event_kind,event_key,payload_hash,unbilled_delta,deferred_delta,journal_id,created_by) values (?,?,?,?,'RECLASSIFICATION',?,?,?,?,?,?)",
                    w,e,sourceId,when,eventKey,payload,u,d,result.journalId(),s.actor().id());
        }
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void completeOnboarding(AccessControl.Scope supplied,long obligationId,Long installationEventId,
                                   LocalDate completionDate,LocalDate postingDate,String intentKey,
                                   String reference,String reason) {
        var s=checked(supplied); long w=s.workspaceId(),e=s.entityId();
        if (obligationId<=0 || completionDate==null || postingDate==null || postingDate.isBefore(completionDate)
                || !text(intentKey) || reference==null || reference.isBlank() || reference.length()>200
                || reason==null || reason.isBlank() || reason.length()>200)
            throw new DomainException(400,"Invalid onboarding completion or evidence");
        String key=intentKey.trim(), evidence=reference.trim(), explanation=reason.trim();
        String payload=hash(obligationId+"|"+installationEventId+"|"+completionDate+"|"+postingDate+"|"
                +evidence.length()+":"+evidence+explanation.length()+":"+explanation);
        var prior=db.queryForList("select payload_hash from service_completions where workspace_id=? and entity_id=? and intent_key=?",w,e,key);
        if (!prior.isEmpty()) {
            if (payload.equals(prior.get(0).get("payload_hash"))) return;
            throw new DomainException(409,"Completion intent conflicts");
        }
        var rows=db.queryForList("select * from sales_service_obligations where workspace_id=? and entity_id=? and id=? for update",w,e,obligationId);
        if (rows.size()!=1) throw new DomainException(404,"Service obligation not found");
        var o=rows.get(0);
        if (!"ONBOARDING".equals(o.get("kind")) || !"READY".equals(o.get("status"))
                || completionDate.isBefore(date(o.get("accepted_date"))) || o.get("asset_id")==null)
            throw new DomainException(409,"Onboarding source is not ready");
        if (installationEventId!=null && !Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from installation_events where workspace_id=? and entity_id=? and id=? and asset_id=? and business_date=? and completion_reference=? and reason=?)
            """,Boolean.class,w,e,installationEventId,o.get("asset_id"),completionDate,evidence,explanation)))
            throw new DomainException(409,"Installation event does not match onboarding");
        if (installationEventId==null && !Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from installation_events where workspace_id=? and entity_id=? and asset_id=? and business_date<=?)
            """,Boolean.class,w,e,o.get("asset_id"),completionDate)))
            throw new DomainException(409,"Onboarding requires an installed asset");
        if (Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from service_completions where workspace_id=? and entity_id=? and obligation_id=?)",Boolean.class,w,e,obligationId)))
            throw new DomainException(409,"Onboarding already completed");
        BigDecimal amount=(BigDecimal)o.get("net"); Balance before=balances(s,false,obligationId,postingDate);
        if (before.earned().signum()!=0 || before.billed().compareTo(amount)>0) throw new DomainException(409,"Invalid onboarding financial balance");
        BigDecimal newU=amount.subtract(before.billed()).max(BigDecimal.ZERO), newD=before.billed().subtract(amount).max(BigDecimal.ZERO);
        BigDecimal du=newU.subtract(before.unbilled()),dd=newD.subtract(before.deferred());
        var lines=new ArrayList<Ledger.Line>();
        deltaLine(lines,number(o,"unbilled_account_id"),du,true); deltaLine(lines,number(o,"deferred_account_id"),dd,false);
        if (amount.signum()>0) lines.add(new Ledger.Line(number(o,"revenue_account_id"),BigDecimal.ZERO,amount));
        String eventKey="service-completion:"+obligationId;
        var result=posting.post(s,"SERVICE_COMPLETION",Long.toString(obligationId),eventKey,postingDate,(String)o.get("currency"),"Onboarding completion",lines);
        long completion=db.queryForObject("""
            insert into service_completions(workspace_id,entity_id,obligation_id,installation_event_id,completion_date,posting_date,amount,journal_id,intent_key,payload_hash,completion_reference,reason,created_by)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
            """,Long.class,w,e,obligationId,installationEventId,completionDate,postingDate,amount,result.journalId(),key,payload,evidence,explanation,s.actor().id());
        db.update("""
            insert into service_balance_postings(workspace_id,entity_id,obligation_id,effective_date,event_kind,event_key,payload_hash,unbilled_delta,deferred_delta,journal_id,completion_id,created_by)
            values (?,?,?,?,'COMPLETION',?,?,?,?,?,?,?)
            """,w,e,obligationId,postingDate,eventKey,payload,du,dd,result.journalId(),completion,s.actor().id());
        reconcileFuture(s,false,obligationId,postingDate,eventKey,(String)o.get("currency"),number(o,"unbilled_account_id"),number(o,"deferred_account_id"));
    }

    @Transactional
    public int recognize(long w,long e,LocalDate postingDate,String intentKey) {
        var s=checked(access.requireContext(w,e,true,"FINANCE"));
        if (postingDate==null || !text(intentKey)) throw new DomainException(400,"Posting date and recognition intent are required");
        LocalDate businessDate=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
        String key=intentKey.trim(), payload=hash(postingDate+"|"+businessDate);
        var prior=db.queryForList("select id,payload_hash from revenue_posting_runs where workspace_id=? and entity_id=? and intent_key=?",w,e,key);
        if (!prior.isEmpty()) {
            if (!payload.equals(prior.get(0).get("payload_hash"))) throw new DomainException(409,"Recognition intent conflicts");
            return db.queryForObject("select count(*) from revenue_recognitions where workspace_id=? and entity_id=? and run_id=?",Integer.class,w,e,number(prior.get(0),"id"));
        }
        var periods=db.queryForList("select status from fiscal_periods where workspace_id=? and entity_id=? and ? between starts_on and ends_on for update",w,e,postingDate);
        if (periods.size()!=1 || !"OPEN".equals(periods.get(0).get("status")))
            throw new DomainException(409,"Recognition requires an open fiscal period");
        long run=db.queryForObject("insert into revenue_posting_runs(workspace_id,entity_id,intent_key,payload_hash,posting_date,business_date,created_by) values (?,?,?,?,?,?,?) returning id",Long.class,w,e,key,payload,postingDate,businessDate,s.actor().id());
        var eligible=db.queryForList("""
            select l.id,l.amount,l.period_end,t.id term_id,t.net_fee,t.unbilled_account_id,t.deferred_account_id,t.revenue_account_id,t.currency
            from revenue_schedule_lines l join revenue_schedules z on z.workspace_id=l.workspace_id and z.entity_id=l.entity_id and z.id=l.schedule_id and z.sealed
            join service_contract_terms t on t.workspace_id=z.workspace_id and t.entity_id=z.entity_id and t.id=z.term_id and t.status='VALIDATED'
            where l.workspace_id=? and l.entity_id=? and l.period_end<=? and l.period_end<=?
              and not exists(select 1 from service_opening_periods o where o.workspace_id=l.workspace_id and o.entity_id=l.entity_id and o.schedule_line_id=l.id)
              and not exists(select 1 from revenue_recognitions r where r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.schedule_line_id=l.id)
            order by t.id,l.period_no
            """,w,e,businessDate,postingDate);
        int count=0;
        for (var line:eligible) {
            long termId=number(line,"term_id"); term(s,termId);
            BigDecimal amount=(BigDecimal)line.get("amount"), net=(BigDecimal)line.get("net_fee");
            Balance before=balances(s,true,termId,postingDate);
            BigDecimal earned=before.earned().add(amount);
            if (earned.compareTo(net)>0 || before.billed().compareTo(net)>0) throw new DomainException(409,"Recognition exceeds approved service fee");
            BigDecimal u=earned.subtract(before.billed()).max(BigDecimal.ZERO),d=before.billed().subtract(earned).max(BigDecimal.ZERO);
            BigDecimal du=u.subtract(before.unbilled()),dd=d.subtract(before.deferred());
            var journal=new ArrayList<Ledger.Line>();
            deltaLine(journal,number(line,"unbilled_account_id"),du,true); deltaLine(journal,number(line,"deferred_account_id"),dd,false);
            if (amount.signum()>0) journal.add(new Ledger.Line(number(line,"revenue_account_id"),BigDecimal.ZERO,amount));
            long lineId=number(line,"id"); String eventKey="service-recognition:"+lineId;
            var result=posting.post(s,"SERVICE_RECOGNITION",Long.toString(lineId),eventKey,postingDate,(String)line.get("currency"),"Service revenue recognition",journal);
            long recognition=db.queryForObject("insert into revenue_recognitions(workspace_id,entity_id,schedule_line_id,run_id,amount,posting_date,journal_id,created_by) values (?,?,?,?,?,?,?,?) returning id",Long.class,w,e,lineId,run,amount,postingDate,result.journalId(),s.actor().id());
            db.update("""
                insert into service_balance_postings(workspace_id,entity_id,term_id,effective_date,event_kind,event_key,payload_hash,unbilled_delta,deferred_delta,journal_id,recognition_id,created_by)
                values (?,?,?,?,'RECOGNITION',?,?,?,?,?,?,?)
                """,w,e,termId,postingDate,eventKey,hash(eventKey+"|"+postingDate+"|"+amount),du,dd,result.journalId(),recognition,s.actor().id());
            reconcileFuture(s,true,termId,postingDate,eventKey,(String)line.get("currency"),number(line,"unbilled_account_id"),number(line,"deferred_account_id"));
            count++;
        }
        return count;
    }

    record BillingEffect(boolean term,long sourceId,BigDecimal unbilledDelta,BigDecimal deferredDelta,
                         long unbilledAccount,long deferredAccount,String currency) {}

    BillingEffect billingEffect(AccessControl.Scope supplied,boolean isTerm,long sourceId,LocalDate when,BigDecimal netChange) {
        var s=checked(supplied); long w=s.workspaceId(),e=s.entityId();
        Map<String,Object> source;
        if (isTerm) source=term(s,sourceId);
        else {
            var rows=db.queryForList("select * from sales_service_obligations where workspace_id=? and entity_id=? and id=? for update",w,e,sourceId);
            if (rows.size()!=1) throw new DomainException(404,"Service obligation not found");
            source=rows.get(0);
        }
        if (isTerm && "OPENING".equals(source.get("source_kind"))) {
            var openings=db.queryForList("select cutoff,billed,net_fee from service_opening_balances where workspace_id=? and entity_id=? and term_id=?",w,e,sourceId);
            if (openings.size()!=1 || when.isBefore(date(openings.get(0).get("cutoff"))))
                throw new DomainException(409,"Opening service history before cutoff is unavailable");
            BigDecimal openingBilled=(BigDecimal)openings.get(0).get("billed");
            BigDecimal openingNet=(BigDecimal)openings.get(0).get("net_fee");
            if (netChange.signum()>0 && openingBilled.signum()>0 && openingBilled.compareTo(openingNet)<0)
                throw new DomainException(409,"Opening statement lacks billed fee intervals required for partial billing");
        }
        BigDecimal net=(BigDecimal)source.get(isTerm?"net_fee":"net");
        Balance before=balances(s,isTerm,sourceId,when);
        BigDecimal billed=before.billed().add(netChange);
        if (billed.signum()<0 || billed.compareTo(net)>0 || before.earned().compareTo(net)>0)
            throw new DomainException(409,"Billing exceeds service entitlement");
        BigDecimal u=before.earned().subtract(billed).max(BigDecimal.ZERO);
        BigDecimal d=billed.subtract(before.earned()).max(BigDecimal.ZERO);
        return new BillingEffect(isTerm,sourceId,u.subtract(before.unbilled()),d.subtract(before.deferred()),
                number(source,"unbilled_account_id"),number(source,"deferred_account_id"),(String)source.get("currency"));
    }

    void recordBilling(AccessControl.Scope supplied,BillingEffect effect,LocalDate date,String key,
                       long invoiceLineId,Long reversalId,Long journalId) {
        var s=checked(supplied); String column=effect.term()?"term_id":"obligation_id";
        String event=reversalId==null?"INVOICE":"REVERSAL";
        db.update("insert into service_balance_postings(workspace_id,entity_id,"+column+",effective_date,event_kind,event_key,payload_hash,unbilled_delta,deferred_delta,journal_id,invoice_line_id,reversal_id,created_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                s.workspaceId(),s.entityId(),effect.sourceId(),date,event,key,hash(key+"|"+date+"|"+effect.unbilledDelta()+"|"+effect.deferredDelta()),
                effect.unbilledDelta(),effect.deferredDelta(),journalId,invoiceLineId,reversalId,s.actor().id());
        reconcileFuture(s,effect.term(),effect.sourceId(),date,key,effect.currency(),effect.unbilledAccount(),effect.deferredAccount());
    }
}
