package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesPostingConfigService {
    public record Accounts(long version, long ar, long unbilled, long revenue, long cogs,
                           long inventory, long bank, long procurementVersion, long paymentVersion) {}
    public record Reference(long id, long version) {}
    public record Draft(long version, Reference ar, Reference unbilled, Reference revenue, Reference cogs) {}
    public record ServiceAccounts(long version, long deferred, long implementationRevenue, long serviceRevenue,
                                  long ar, long unbilled) {}
    public record ServiceDraft(long version, Reference deferred, Reference implementationRevenue, Reference serviceRevenue) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;

    public SalesPostingConfigService(JdbcTemplate db, AccessControl access, AuditLog audit) {
        this.db = db; this.access = access; this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Accounts require(AccessControl.Scope supplied) {
        if (supplied == null || supplied.entityId() == null || supplied.actor() == null)
            throw new DomainException(400, "Entity scope required");
        var scope = access.requireContext(supplied.workspaceId(), supplied.entityId(), true,
                "INSIDE_SALES", "SALES_MANAGER", "OPERATIONS", "FINANCE");
        if (scope.actor().id() != supplied.actor().id()) throw new DomainException(403, "Invalid sales scope");
        var rows = db.query("""
            select s.version,s.ar_account_id,s.unbilled_account_id,s.revenue_account_id,s.cogs_account_id,
                   p.inventory_account_id,b.bank_account_id,p.version,b.version
            from sales_posting_config s
            join procurement_posting_config p on p.workspace_id=s.workspace_id and p.entity_id=s.entity_id
            join payment_posting_config b on b.workspace_id=s.workspace_id and b.entity_id=s.entity_id
            where s.workspace_id=? and s.entity_id=? for update of s,p,b
            """, (r,n) -> new Accounts(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),
                    r.getLong(6),r.getLong(7),r.getLong(8),r.getLong(9)), scope.workspaceId(),scope.entityId());
        if (rows.size() != 1) throw new DomainException(409, "Configure sales, procurement and Bank posting accounts first");
        Accounts a = rows.get(0);
        validateAccounts(scope.workspaceId(),scope.entityId(),a.ar(),a.unbilled(),a.revenue(),a.cogs());
        Integer existing = db.queryForObject("""
            select count(*) from chart_accounts where workspace_id=? and entity_id=? and active
              and account_type='ASSET' and id in (?,?)
            """, Integer.class,scope.workspaceId(),scope.entityId(),a.inventory(),a.bank());
        if (existing == null || existing != 2 || a.inventory() == a.bank())
            throw new DomainException(409, "Inventory and Bank must be distinct active asset accounts");
        return a;
    }

    public List<Map<String,Object>> current(long w,long e) {
        access.requireContext(w,e,false,"FINANCE");
        return db.queryForList("select * from sales_posting_config where workspace_id=? and entity_id=?",w,e);
    }

    public List<Map<String,Object>> currentService(long w,long e) {
        access.requireContext(w,e,false,"FINANCE");
        return db.queryForList("select * from service_posting_config where workspace_id=? and entity_id=?",w,e);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ServiceAccounts requireService(AccessControl.Scope supplied) {
        if (supplied == null || supplied.entityId() == null || supplied.actor() == null)
            throw new DomainException(400,"Entity scope required");
        var scope=access.requireContext(supplied.workspaceId(),supplied.entityId(),true,
                "INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE");
        if (scope.actor().id()!=supplied.actor().id()) throw new DomainException(403,"Invalid sales scope");
        var goods=db.queryForList("select ar_account_id,unbilled_account_id from sales_posting_config where workspace_id=? and entity_id=? for update",
                scope.workspaceId(),scope.entityId());
        if (goods.size()!=1) throw new DomainException(409,"Configure AR and Unbilled posting accounts first");
        long ar=number(goods.get(0),"ar_account_id"), unbilled=number(goods.get(0),"unbilled_account_id");
        Integer assetCount=db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and active and account_type='ASSET' and id in (?,?)",
                Integer.class,scope.workspaceId(),scope.entityId(),ar,unbilled);
        if (ar==unbilled || assetCount==null || assetCount!=2)
            throw new DomainException(409,"AR and Unbilled must be distinct active asset accounts");
        var rows = db.queryForList("select * from service_posting_config where workspace_id=? and entity_id=? for update",
                scope.workspaceId(),scope.entityId());
        if (rows.size()!=1) throw new DomainException(409,"Configure service posting accounts first");
        var row=rows.get(0);
        long deferred=number(row,"deferred_account_id"), implementation=number(row,"implementation_revenue_account_id"),
                revenue=number(row,"service_revenue_account_id");
        validateServiceAccounts(scope.workspaceId(),scope.entityId(),deferred,implementation,revenue);
        return new ServiceAccounts(number(row,"version"),deferred,implementation,revenue,ar,unbilled);
    }

    @Transactional
    public void saveService(long w,long e,ServiceDraft draft) {
        var scope=access.requireContext(w,e,true,"FINANCE");
        lockEntity(w,e);
        if (draft==null || draft.version()<0) throw new DomainException(400,"Invalid configuration version","version");
        Reference[] refs={draft.deferred(),draft.implementationRevenue(),draft.serviceRevenue()};
        String[] fields={"deferredAccount","implementationRevenueAccount","serviceRevenueAccount"};
        for (int i=0;i<refs.length;i++) {
            Reference ref=refs[i];
            if (ref==null || ref.id()<=0 || ref.version()<=0)
                throw new DomainException(400,"Select an account and its current version",fields[i]);
            Integer count=db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=? and version=? and active",Integer.class,w,e,ref.id(),ref.version());
            if (count==null || count!=1) throw new DomainException(409,"Account reference changed or is unavailable");
        }
        validateServiceAccounts(w,e,refs[0].id(),refs[1].id(),refs[2].id());
        var old=db.queryForList("select * from service_posting_config where workspace_id=? and entity_id=? for update",w,e);
        if (draft.version()==0 ? !old.isEmpty() : old.size()!=1 || number(old.get(0),"version")!=draft.version())
            throw new DomainException(409,"Service posting configuration changed");
        boolean changed=old.isEmpty() || number(old.get(0),"deferred_account_id")!=refs[0].id()
                || number(old.get(0),"implementation_revenue_account_id")!=refs[1].id()
                || number(old.get(0),"service_revenue_account_id")!=refs[2].id();
        if (!old.isEmpty() && changed && Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from sales_service_obligations where workspace_id=? and entity_id=?)
                or exists(select 1 from service_contract_terms where workspace_id=? and entity_id=?)
            """,Boolean.class,w,e,w,e))) throw new DomainException(409,"Service mapping is referenced by an immutable source");
        if (changed && (old.isEmpty() || number(old.get(0),"deferred_account_id")!=refs[0].id())) {
            if (!old.isEmpty()) assertZeroBalance(w,e,number(old.get(0),"deferred_account_id"));
            assertZeroBalance(w,e,refs[0].id());
        }
        int updated=draft.version()==0
            ? db.update("insert into service_posting_config(workspace_id,entity_id,deferred_account_id,implementation_revenue_account_id,service_revenue_account_id,updated_by) values (?,?,?,?,?,?) on conflict do nothing",w,e,refs[0].id(),refs[1].id(),refs[2].id(),scope.actor().id())
            : db.update("update service_posting_config set deferred_account_id=?,implementation_revenue_account_id=?,service_revenue_account_id=?,updated_by=?,version=version+1 where workspace_id=? and entity_id=? and version=?",refs[0].id(),refs[1].id(),refs[2].id(),scope.actor().id(),w,e,draft.version());
        if (updated!=1) throw new DomainException(409,"Service posting configuration changed");
        audit.record(scope.actor(),w,e,"UPDATE","SERVICE_POSTING_CONFIG",Long.toString(e),draft.version()+1,"SUCCESS","Deferred and service revenue mappings");
    }

    private void validateServiceAccounts(long w,long e,long deferred,long implementation,long revenue) {
        if (deferred==implementation || deferred==revenue || implementation==revenue)
            throw new DomainException(400,"Service posting account roles must be distinct");
        Integer count=db.queryForObject("""
            select count(*) from chart_accounts where workspace_id=? and entity_id=? and active
              and ((id=? and account_type='LIABILITY') or (id in (?,?) and account_type='INCOME'))
            """,Integer.class,w,e,deferred,implementation,revenue);
        if (count==null || count!=3) throw new DomainException(400,"Select active Deferred liability and distinct service income accounts");
        Boolean conflict=db.queryForObject("""
            select exists(select 1 from procurement_posting_config p where p.workspace_id=? and p.entity_id=?
                and (p.ap_account_id=? or p.grni_account_id=? or p.adjustment_gain_account_id in (?,?)))
              or exists(select 1 from tax_versions t where t.workspace_id=? and t.entity_id=? and t.output_account_id=?)
              or exists(select 1 from sales_posting_config s where s.workspace_id=? and s.entity_id=? and s.revenue_account_id in (?,?))
            """,Boolean.class,w,e,deferred,deferred,implementation,revenue,w,e,deferred,w,e,implementation,revenue);
        if (Boolean.TRUE.equals(conflict)) throw new DomainException(400,"Service roles conflict with existing posting accounts");
    }

    public List<Map<String,Object>> choices(long w,long e) {
        access.requireContext(w,e,false,"FINANCE");
        return db.queryForList("select id,code,name,account_type,active,version from chart_accounts where workspace_id=? and entity_id=? order by code,id",w,e);
    }

    @Transactional
    public void save(long w,long e,Draft draft) {
        var scope = access.requireContext(w,e,true,"FINANCE");
        lockEntity(w,e);
        if (draft == null || draft.version() < 0) throw new DomainException(400,"Invalid configuration version","version");
        Reference[] refs = {draft.ar(),draft.unbilled(),draft.revenue(),draft.cogs()};
        String[] fields = {"arAccount","unbilledAccount","revenueAccount","cogsAccount"};
        for (int i=0;i<refs.length;i++) {
            Reference r=refs[i];
            if (r == null || r.id() <= 0 || r.version() <= 0)
                throw new DomainException(400,"Select an account and its current version",fields[i]);
            Integer count=db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=? and version=? and active",Integer.class,w,e,r.id(),r.version());
            if (count == null || count != 1) throw new DomainException(409,"Account reference changed or is unavailable");
        }
        validateAccounts(w,e,draft.ar().id(),draft.unbilled().id(),draft.revenue().id(),draft.cogs().id());
        var old=db.queryForList("select * from sales_posting_config where workspace_id=? and entity_id=? for update",w,e);
        if (draft.version()==0 ? !old.isEmpty() : old.size()!=1 || number(old.get(0),"version")!=draft.version())
            throw new DomainException(409,"Sales posting configuration changed");
        String[] columns={"ar_account_id","unbilled_account_id","revenue_account_id","cogs_account_id"};
        if (!old.isEmpty()) {
            boolean changed=false;
            for (int i=0;i<refs.length;i++) changed |= number(old.get(0),columns[i])!=refs[i].id();
            if (changed && Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from sales_orders where workspace_id=? and entity_id=?)",Boolean.class,w,e)))
                throw new DomainException(409,"Sales mapping is referenced by an immutable booked order");
            if (changed) {
                assertZeroBalance(w,e,number(old.get(0),"ar_account_id"));
                assertZeroBalance(w,e,number(old.get(0),"unbilled_account_id"));
            }
        }
        for (int i=0;i<2;i++) if (old.isEmpty() || number(old.get(0),columns[i])!=refs[i].id())
            assertZeroBalance(w,e,refs[i].id());
        int changed = draft.version()==0
            ? db.update("insert into sales_posting_config(workspace_id,entity_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,updated_by) values (?,?,?,?,?,?,?) on conflict do nothing",w,e,draft.ar().id(),draft.unbilled().id(),draft.revenue().id(),draft.cogs().id(),scope.actor().id())
            : db.update("update sales_posting_config set ar_account_id=?,unbilled_account_id=?,revenue_account_id=?,cogs_account_id=?,updated_by=?,version=version+1 where workspace_id=? and entity_id=? and version=?",draft.ar().id(),draft.unbilled().id(),draft.revenue().id(),draft.cogs().id(),scope.actor().id(),w,e,draft.version());
        if (changed != 1) throw new DomainException(409,"Sales posting configuration changed");
        audit.record(scope.actor(),w,e,"UPDATE","SALES_POSTING_CONFIG",Long.toString(e),draft.version()+1,"SUCCESS","AR, Unbilled, goods revenue and COGS mappings");
    }

    private void validateAccounts(long w,long e,long ar,long unbilled,long revenue,long cogs) {
        if (ar==unbilled || ar==revenue || ar==cogs || unbilled==revenue || unbilled==cogs || revenue==cogs)
            throw new DomainException(400,"Sales posting account roles must be distinct","arAccount","unbilledAccount","revenueAccount","cogsAccount");
        Integer count=db.queryForObject("""
            select count(*) from chart_accounts where workspace_id=? and entity_id=? and active
              and ((id in (?,?) and account_type='ASSET') or (id=? and account_type='INCOME') or (id=? and account_type='EXPENSE'))
            """,Integer.class,w,e,ar,unbilled,revenue,cogs);
        if (count==null || count!=4) throw new DomainException(400,"Select active AR/Unbilled assets, goods income and COGS expense accounts","arAccount","unbilledAccount","revenueAccount","cogsAccount");
        Boolean conflict=db.queryForObject("""
            select exists(select 1 from procurement_posting_config p where p.workspace_id=? and p.entity_id=?
                and (p.inventory_account_id in (?,?) or p.adjustment_gain_account_id=?
                  or p.adjustment_loss_account_id=? or p.service_expense_account_id=?))
              or exists(select 1 from payment_posting_config p where p.workspace_id=? and p.entity_id=? and p.bank_account_id in (?,?))
              or exists(select 1 from tax_versions t where t.workspace_id=? and t.entity_id=? and t.input_account_id in (?,?))
            """,Boolean.class,w,e,ar,unbilled,revenue,cogs,cogs,w,e,ar,unbilled,w,e,ar,unbilled);
        if (Boolean.TRUE.equals(conflict)) throw new DomainException(400,"Sales roles must be distinct from Bank, Inventory, tax and adjustment/service accounts","arAccount","unbilledAccount","revenueAccount","cogsAccount");
        Boolean serviceConflict=db.queryForObject("""
            select exists(select 1 from service_posting_config s where s.workspace_id=? and s.entity_id=?
              and ? in (s.implementation_revenue_account_id,s.service_revenue_account_id))
            """,Boolean.class,w,e,revenue);
        if (Boolean.TRUE.equals(serviceConflict)) throw new DomainException(400,"Goods revenue must differ from service revenue","revenueAccount");
    }

    private void assertZeroBalance(long w,long e,long account) {
        BigDecimal balance=db.queryForObject("""
            select coalesce(sum(l.debit-l.credit),0) from journal_lines l
            join journal_entries j on j.workspace_id=l.workspace_id and j.entity_id=l.entity_id and j.id=l.journal_id
            where l.workspace_id=? and l.entity_id=? and l.account_id=? and j.state='POSTED'
            """,BigDecimal.class,w,e,account);
        if (balance.signum()!=0) throw new DomainException(409,"AR or Unbilled opening balance requires a typed source; existing entries were not changed");
    }

    private void lockEntity(long w,long e) {
        if (db.queryForList("select id from legal_entities where workspace_id=? and id=? and active for update",w,e).size()!=1)
            throw new DomainException(404,"Entity not found");
    }

    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
