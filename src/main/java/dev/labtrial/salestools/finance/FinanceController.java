package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.baseline.BaselineService;
import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}")
public class FinanceController implements FormController {
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final Ledger ledger; private final BaselineService baseline;
    public FinanceController(JdbcTemplate db, AccessControl access, AuditLog audit, Ledger ledger, BaselineService baseline) { this.db=db; this.access=access; this.audit=audit; this.ledger=ledger; this.baseline=baseline; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e; }
    private void context(Model model,long w,long e) { model.addAttribute("workspaceId",w); model.addAttribute("entityId",e); model.addAttribute("contextBase",base(w,e)); }
    @Override
    public String redisplayForm(HttpServletRequest request, Model model) {
        long w=FormController.path(request,"workspaceId"), e=FormController.path(request,"entityId");
        String path=request.getRequestURI();
        if(path.endsWith("/finance/periods/new")) return periodForm(w,e,model);
        if(path.endsWith("/finance/openings/new")) {
            int count=2;
            for(String name:List.of("accountId","debit","credit")) {
                String[] values=request.getParameterValues(name);
                if(values!=null) count=Math.max(count,values.length);
            }
            return openingForm(w,e,Math.min(count,100),model);
        }
        if(path.endsWith("/initialize")) return initializeForm(w,e,model);
        if(path.endsWith("/finance/accounts") || path.endsWith("/finance/taxes") || path.endsWith("/finance/payment-config") || path.endsWith("/finance/procurement-config")
                || path.matches(".*/finance/accounts/[0-9]+") || path.matches(".*/finance/periods/[0-9]+/(state|edit)")
                || path.matches(".*/finance/taxes/[0-9]+/versions") || path.matches(".*/finance/tax-versions/[0-9]+/end"))
            return finance(w,e,model);
        throw new DomainException(400,"Unexpected request");
    }
    @org.springframework.web.bind.annotation.ModelAttribute
    public void rejectUnexpectedParameters(jakarta.servlet.http.HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return;
        String path=request.getRequestURI();
        java.util.Set<String> allowed;
        if(path.endsWith("/finance/periods/new")) allowed=java.util.Set.of("code","startsOn","endsOn");
        else if(path.endsWith("/state")) allowed=java.util.Set.of("version","status");
        else if(path.matches(".*/finance/periods/[0-9]+/edit")) allowed=java.util.Set.of("version","startsOn","endsOn");
        else if(path.endsWith("/finance/accounts")) allowed=java.util.Set.of("code","name","accountType");
        else if(path.matches(".*/finance/accounts/[0-9]+")) allowed=java.util.Set.of("version","name","active");
        else if(path.endsWith("/finance/taxes")) allowed=java.util.Set.of("code","name","validFrom","validTo","ratePct","inclusive","inputAccount","outputAccount");
        else if(path.matches(".*/finance/taxes/[0-9]+/versions")) allowed=java.util.Set.of("version","validFrom","validTo","ratePct","inclusive","inputAccount","outputAccount");
        else if(path.matches(".*/finance/tax-versions/[0-9]+/end")) allowed=java.util.Set.of("version","validTo");
        else if(path.endsWith("/finance/payment-config")) allowed=java.util.Set.of("version","bankAccount");
        else if(path.endsWith("/finance/procurement-config")) allowed=java.util.Set.of("version","inventoryAccount","apAccount","grniAccount","adjustmentGainAccount","adjustmentLossAccount","serviceExpenseAccount");
        else if(path.endsWith("/finance/openings/new")) allowed=java.util.Set.of("sourceKey","revision","date","currency","description","accountId","debit","credit");
        else if(path.endsWith("/initialize")) allowed=java.util.Set.of("baselineKey","version");
        else throw new DomainException(400,"Unexpected request");
        for(String name:request.getParameterMap().keySet()) {
            if(!name.equals("_csrf") && !name.equals("_form") && !allowed.contains(name)) throw new DomainException(400,"Unexpected form field");
            if(!java.util.Set.of("accountId","debit","credit").contains(name) && request.getParameterValues(name).length!=1) throw new DomainException(400,"Duplicate form field");
        }
    }
    @GetMapping("/finance")
    public String finance(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        access.requireContext(workspaceId,entityId,false,"FINANCE"); context(model,workspaceId,entityId);
        model.addAttribute("accounts",db.queryForList("select * from chart_accounts where workspace_id=? and entity_id=? order by code",workspaceId,entityId));
        model.addAttribute("periods",db.queryForList("select * from fiscal_periods where workspace_id=? and entity_id=? order by starts_on",workspaceId,entityId));
        model.addAttribute("taxes",db.queryForList("select t.code,t.name,v.* from tax_codes t join tax_versions v on v.tax_code_id=t.id where t.workspace_id=? and t.entity_id=? order by t.code,v.valid_from",workspaceId,entityId));
        model.addAttribute("taxCodes",db.queryForList("select * from tax_codes where workspace_id=? and entity_id=? order by code",workspaceId,entityId));
        model.addAttribute("paymentConfig",db.queryForList("select * from payment_posting_config where workspace_id=? and entity_id=?",workspaceId,entityId));
        model.addAttribute("procurementConfig",db.queryForList("select * from procurement_posting_config where workspace_id=? and entity_id=?",workspaceId,entityId));
        model.addAttribute("journals",db.queryForList("select id,number,posting_date,currency,description,state from journal_entries where workspace_id=? and entity_id=? order by id desc",workspaceId,entityId));
        return "finance";
    }
    @GetMapping("/finance/periods/new") public String periodForm(@PathVariable long workspaceId,@PathVariable long entityId,Model model) { access.requireContext(workspaceId,entityId,false,"FINANCE"); context(model,workspaceId,entityId); return "period-form"; }
    @PostMapping("/finance/periods/new") @Transactional
    public String createPeriod(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam String code,@RequestParam LocalDate startsOn,@RequestParam LocalDate endsOn) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(code.isBlank() || code.length()>80) throw new DomainException(400,"Invalid period","code");
        if(startsOn.isAfter(endsOn)) throw new DomainException(400,"Invalid period","startsOn","endsOn");
        try { Long id=db.queryForObject("insert into fiscal_periods(workspace_id,entity_id,code,starts_on,ends_on,status,created_by) values (?,?,?,?,?,'OPEN',?) returning id",Long.class,workspaceId,entityId,code,startsOn,endsOn,scope.actor().id()); audit.record(scope.actor(),workspaceId,entityId,"CREATE","PERIOD",Long.toString(id),1,"SUCCESS",""); }
        catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Period overlaps or code already exists"); }
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/periods/{id}/state") @Transactional
    public String state(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version,@RequestParam String status) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(!List.of("OPEN","CLOSED").contains(status)) throw new DomainException(400,"Invalid period state","status");
        int n=db.update("update fiscal_periods set status=?,version=version+1 where workspace_id=? and entity_id=? and id=? and version=? and status<>?",status,workspaceId,entityId,id,version,status);
        if(n!=1) throw new DomainException(409,"Period changed or not found");
        audit.record(scope.actor(),workspaceId,entityId,"STATE","PERIOD",Long.toString(id),version+1,"SUCCESS",status);
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/accounts") @Transactional
    public String account(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam String code,@RequestParam String name,@RequestParam String accountType) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(code.isBlank() || code.length()>80) throw new DomainException(400,"Invalid account","code");
        if(name.isBlank() || name.length()>200) throw new DomainException(400,"Invalid account","name");
        if(!List.of("ASSET","LIABILITY","EQUITY","INCOME","EXPENSE").contains(accountType)) throw new DomainException(400,"Invalid account","accountType");
        try { Long id=db.queryForObject("insert into chart_accounts(workspace_id,entity_id,code,name,account_type,created_by) values (?,?,?,?,?,?) returning id",Long.class,workspaceId,entityId,code,name,accountType,scope.actor().id()); audit.record(scope.actor(),workspaceId,entityId,"CREATE","ACCOUNT",Long.toString(id),1,"SUCCESS",""); }
        catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Account already exists"); }
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/taxes") @Transactional
    public String tax(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam String code,@RequestParam String name,@RequestParam LocalDate validFrom,@RequestParam(required=false) LocalDate validTo,@RequestParam BigDecimal ratePct,@RequestParam(defaultValue="false") boolean inclusive,@RequestParam long inputAccount,@RequestParam long outputAccount) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(code.isBlank() || code.length()>80) throw new DomainException(400,"Invalid tax configuration","code");
        if(name.isBlank() || name.length()>200) throw new DomainException(400,"Invalid tax configuration","name");
        if(ratePct.signum()<0 || ratePct.compareTo(new BigDecimal("100"))>0 || ratePct.scale()>4) throw new DomainException(400,"Invalid tax configuration","ratePct");
        if(validTo!=null && !validTo.isAfter(validFrom)) throw new DomainException(400,"Invalid tax configuration","validFrom","validTo");
        validateTaxAccounts(workspaceId,entityId,inputAccount,outputAccount);
        try { Long id=db.queryForObject("insert into tax_codes(workspace_id,entity_id,code,name,created_by) values (?,?,?,?,?) returning id",Long.class,workspaceId,entityId,code,name,scope.actor().id()); db.update("insert into tax_versions(workspace_id,entity_id,tax_code_id,valid_from,valid_to,rate_pct,price_inclusive,rounding_mode,input_account_id,output_account_id,created_by) values (?,?,?,?,?,?,?,'HALF_UP',?,?,?)",workspaceId,entityId,id,validFrom,validTo,ratePct,inclusive,inputAccount,outputAccount,scope.actor().id()); audit.record(scope.actor(),workspaceId,entityId,"CREATE","TAX",Long.toString(id),1,"SUCCESS",""); }
        catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Invalid or conflicting tax configuration"); }
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/accounts/{id}") @Transactional
    public String editAccount(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version,@RequestParam String name,@RequestParam boolean active) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(name.isBlank() || name.length()>200) throw new DomainException(400,"Invalid account name","name");
        if(db.update("update chart_accounts set name=?,active=?,version=version+1 where workspace_id=? and entity_id=? and id=? and version=?",name,active,workspaceId,entityId,id,version)!=1) throw new DomainException(409,"Account changed or not found");
        audit.record(scope.actor(),workspaceId,entityId,"UPDATE","ACCOUNT",Long.toString(id),version+1,"SUCCESS","name,active");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/periods/{id}/edit") @Transactional
    public String editPeriod(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version,@RequestParam LocalDate startsOn,@RequestParam LocalDate endsOn) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(startsOn.isAfter(endsOn)) throw new DomainException(400,"Invalid period dates","startsOn","endsOn");
        try {
            var period=db.queryForList("select starts_on,ends_on from fiscal_periods where workspace_id=? and entity_id=? and id=? and version=? for update",workspaceId,entityId,id,version);
            if(period.size()!=1) throw new DomainException(409,"Period changed or not found");
            if(db.queryForObject("select count(*) from journal_entries where workspace_id=? and entity_id=? and posting_date between ? and ? and state='POSTED'",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted entries");
            if(db.queryForObject("select count(*) from procurement_receipts where workspace_id=? and entity_id=? and posting_date between ? and ? and status='POSTED'",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted receipts");
            if(db.queryForObject("select count(*) from vendor_invoices where workspace_id=? and entity_id=? and invoice_date between ? and ? and status='POSTED'",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted invoices");
            if(db.queryForObject("select count(*) from procurement_reversals where workspace_id=? and entity_id=? and reversal_date between ? and ?",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted reversals");
            if(db.queryForObject("select count(*) from stock_movements where workspace_id=? and entity_id=? and posting_date between ? and ?",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted stock movements");
            if(db.queryForObject("select count(*) from shipments where workspace_id=? and entity_id=? and shipment_date between ? and ? and status='POSTED'",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted shipments");
            if(db.queryForObject("select count(*) from sales_invoices where workspace_id=? and entity_id=? and invoice_date between ? and ? and status='POSTED'",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted customer invoices");
            if(db.queryForObject("select count(*) from customer_receipts where workspace_id=? and entity_id=? and payment_date between ? and ? and status='POSTED'",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted customer receipts");
            if(db.queryForObject("select count(*) from sales_reversals where workspace_id=? and entity_id=? and reversal_date between ? and ?",Integer.class,workspaceId,entityId,period.get(0).get("starts_on"),period.get(0).get("ends_on"))>0) throw new DomainException(409,"Period contains posted customer reversals");
            db.update("update fiscal_periods set starts_on=?,ends_on=?,version=version+1 where workspace_id=? and entity_id=? and id=?",startsOn,endsOn,workspaceId,entityId,id);
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Period overlaps another period"); }
        audit.record(scope.actor(),workspaceId,entityId,"UPDATE","PERIOD",Long.toString(id),version+1,"SUCCESS","dates");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/taxes/{id}/versions") @Transactional
    public String taxVersion(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version,@RequestParam LocalDate validFrom,@RequestParam(required=false) LocalDate validTo,@RequestParam BigDecimal ratePct,@RequestParam(defaultValue="false") boolean inclusive,@RequestParam long inputAccount,@RequestParam long outputAccount) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        if(ratePct.signum()<0 || ratePct.compareTo(new BigDecimal("100"))>0 || ratePct.scale()>4) throw new DomainException(400,"Invalid tax version","ratePct");
        if(validTo!=null && !validTo.isAfter(validFrom)) throw new DomainException(400,"Invalid tax version","validFrom","validTo");
        validateTaxAccounts(workspaceId,entityId,inputAccount,outputAccount);
        try {
            if(db.update("update tax_codes set version=version+1 where workspace_id=? and entity_id=? and id=? and version=? and active",workspaceId,entityId,id,version)!=1) throw new DomainException(409,"Tax code changed or not found");
            db.update("insert into tax_versions(workspace_id,entity_id,tax_code_id,valid_from,valid_to,rate_pct,price_inclusive,rounding_mode,input_account_id,output_account_id,created_by) values (?,?,?,?,?,?,?,'HALF_UP',?,?,?)",workspaceId,entityId,id,validFrom,validTo,ratePct,inclusive,inputAccount,outputAccount,scope.actor().id());
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Tax version overlaps or account is invalid"); }
        audit.record(scope.actor(),workspaceId,entityId,"CREATE_VERSION","TAX",Long.toString(id),version+1,"SUCCESS","");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/tax-versions/{id}/end") @Transactional
    public String endTaxVersion(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version,@RequestParam LocalDate validTo) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        try {
            var rows=db.queryForList("select valid_from,valid_to from tax_versions where workspace_id=? and entity_id=? and id=? and version=? for update",workspaceId,entityId,id,version);
            if(rows.size()!=1) throw new DomainException(409,"Tax version changed or not found");
            if(db.queryForObject("select count(*) from journal_entries where workspace_id=? and entity_id=? and state='POSTED' and posting_date>=? and posting_date>=? and (?::date is null or posting_date<?::date)",Integer.class,workspaceId,entityId,validTo,rows.get(0).get("valid_from"),rows.get(0).get("valid_to"),rows.get(0).get("valid_to"))>0) throw new DomainException(409,"Tax version may be referenced by posted history");
            int changed=db.update("update tax_versions set valid_to=?,version=version+1 where workspace_id=? and entity_id=? and id=? and version=? and valid_from<?",validTo,workspaceId,entityId,id,version,validTo);
            if(changed!=1) throw new DomainException(409,"Tax version changed or date is invalid");
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Tax version dates overlap"); }
        audit.record(scope.actor(),workspaceId,entityId,"UPDATE","TAX_VERSION",Long.toString(id),version+1,"SUCCESS","valid_to");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/payment-config") @Transactional
    public String paymentConfig(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam long version,@RequestParam long bankAccount) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        Integer valid=db.queryForObject("""
            select count(*) from chart_accounts a
            where a.workspace_id=? and a.entity_id=? and a.id=? and a.active and a.account_type='ASSET'
              and not exists(select 1 from procurement_posting_config p where p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.inventory_account_id=a.id)
              and not exists(select 1 from tax_versions t where t.workspace_id=a.workspace_id and t.entity_id=a.entity_id and t.input_account_id=a.id)
              and not exists(select 1 from sales_posting_config s where s.workspace_id=a.workspace_id and s.entity_id=a.entity_id and a.id in (s.ar_account_id,s.unbilled_account_id))
            """,Integer.class,workspaceId,entityId,bankAccount);
        if(valid==null || valid!=1) throw new DomainException(400,"Select an active asset account distinct from Inventory, Input Tax, AR and Unbilled","bankAccount");
        int updated;
        if(version==0) updated=db.update("insert into payment_posting_config(workspace_id,entity_id,bank_account_id,updated_by) values (?,?,?,?) on conflict do nothing",workspaceId,entityId,bankAccount,scope.actor().id());
        else updated=db.update("update payment_posting_config set bank_account_id=?,updated_by=?,version=version+1 where workspace_id=? and entity_id=? and version=?",bankAccount,scope.actor().id(),workspaceId,entityId,version);
        if(updated!=1) throw new DomainException(409,"Payment configuration changed");
        audit.record(scope.actor(),workspaceId,entityId,"UPDATE","PAYMENT_CONFIG",Long.toString(entityId),version+1,"SUCCESS","bank account");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    @PostMapping("/finance/procurement-config") @Transactional
    public String procurementConfig(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam long version,
            @RequestParam String inventoryAccount,@RequestParam String apAccount,@RequestParam String grniAccount,
            @RequestParam String adjustmentGainAccount,@RequestParam String adjustmentLossAccount,@RequestParam String serviceExpenseAccount) {
        var scope=access.requireContext(workspaceId,entityId,true,"FINANCE");
        String[] refs={inventoryAccount,apAccount,grniAccount,adjustmentGainAccount,adjustmentLossAccount,serviceExpenseAccount};
        long[] ids=new long[refs.length];
        String[] types={"ASSET","LIABILITY","LIABILITY","INCOME","EXPENSE","EXPENSE"};
        String[] fields={"inventoryAccount","apAccount","grniAccount","adjustmentGainAccount","adjustmentLossAccount","serviceExpenseAccount"};
        for(int i=0;i<ids.length;i++) {
            String[] parts=refs[i].split(":",-1);
            long accountVersion;
            try {
                if(parts.length!=2) throw new NumberFormatException();
                ids[i]=Long.parseLong(parts[0]); accountVersion=Long.parseLong(parts[1]);
            } catch(NumberFormatException ex) { throw new DomainException(400,"Select an account",fields[i]); }
            Integer count=db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=? and version=? and active and account_type=?",Integer.class,workspaceId,entityId,ids[i],accountVersion,types[i]);
            if(count==null || count!=1) throw new DomainException(400,"Account changed or is unavailable",fields[i]);
        }
        if(ids[0]==ids[1] || ids[0]==ids[2] || ids[1]==ids[2])
            throw new DomainException(400,"Control accounts must be distinct","inventoryAccount","apAccount","grniAccount");
        Integer bankConflict=db.queryForObject("select count(*) from payment_posting_config where workspace_id=? and entity_id=? and bank_account_id=?",Integer.class,workspaceId,entityId,ids[0]);
        if(bankConflict!=null && bankConflict>0)
            throw new DomainException(400,"Inventory and Bank accounts must differ","inventoryAccount");
        Integer taxConflict=db.queryForObject("select count(*) from tax_versions where workspace_id=? and entity_id=? and (input_account_id=? or output_account_id in (?,?))",Integer.class,workspaceId,entityId,ids[0],ids[1],ids[2]);
        if(taxConflict!=null && taxConflict>0)
            throw new DomainException(400,"Procurement control accounts must differ from tax accounts","inventoryAccount","apAccount","grniAccount");
        Boolean salesConflict=db.queryForObject("""
            select exists(select 1 from sales_posting_config s where s.workspace_id=? and s.entity_id=?
              and (? in (s.ar_account_id,s.unbilled_account_id) or ?=s.revenue_account_id
                or ?=s.cogs_account_id or ?=s.cogs_account_id))
            """,Boolean.class,workspaceId,entityId,ids[0],ids[3],ids[4],ids[5]);
        if(Boolean.TRUE.equals(salesConflict)) throw new DomainException(400,"Procurement roles must be distinct from AR, Unbilled, goods revenue and COGS","inventoryAccount","adjustmentGainAccount","adjustmentLossAccount","serviceExpenseAccount");
        Boolean serviceConflict=db.queryForObject("""
            select exists(select 1 from service_posting_config s where s.workspace_id=? and s.entity_id=?
              and (?=s.deferred_account_id or ?=s.deferred_account_id
                or ? in (s.implementation_revenue_account_id,s.service_revenue_account_id)))
            """,Boolean.class,workspaceId,entityId,ids[1],ids[2],ids[3]);
        if(Boolean.TRUE.equals(serviceConflict)) throw new DomainException(400,"Procurement roles conflict with service posting accounts","apAccount","grniAccount","adjustmentGainAccount");
        var current=db.queryForList("select * from procurement_posting_config where workspace_id=? and entity_id=? for update",workspaceId,entityId);
        if((version==0 && !current.isEmpty()) || (version!=0 && (current.isEmpty() || ((Number)current.get(0).get("version")).longValue()!=version))) throw new DomainException(409,"Procurement configuration changed");
        if(!current.isEmpty()) {
            String[] columns={"inventory_account_id","ap_account_id","grni_account_id"};
            for(int i=0;i<3;i++) {
                long previous=((Number)current.get(0).get(columns[i])).longValue();
                if(previous!=ids[i]) {
                    if(i==0&&Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from sales_orders where workspace_id=? and entity_id=?)",Boolean.class,workspaceId,entityId)))
                        throw new DomainException(409,"Inventory mapping is referenced by an immutable booked sales order");
                    Integer sourced=db.queryForObject("select (select count(*) from purchase_order_revisions where workspace_id=? and entity_id=? and status='APPROVED')+(select count(*) from vendor_invoices where workspace_id=? and entity_id=? and status='POSTED')",Integer.class,workspaceId,entityId,workspaceId,entityId);
                    if(sourced!=null && sourced>0) throw new DomainException(409,"Control mapping is referenced by approved or posted sources");
                    BigDecimal balance=db.queryForObject("select coalesce(sum(l.debit-l.credit),0) from journal_lines l join journal_entries j on j.workspace_id=l.workspace_id and j.entity_id=l.entity_id and j.id=l.journal_id where l.workspace_id=? and l.entity_id=? and l.account_id=? and j.state='POSTED'",BigDecimal.class,workspaceId,entityId,previous);
                    if(balance.signum()!=0) throw new DomainException(409,"Previous control account has a nonzero balance; existing entries were not changed");
                }
            }
        }
        BigDecimal inventory=db.queryForObject("select coalesce(sum(value),0) from inventory_valuation_pools where workspace_id=? and entity_id=?",BigDecimal.class,workspaceId,entityId);
        BigDecimal ap=db.queryForObject("select coalesce((select sum(l.gross) from vendor_invoice_lines l join vendor_invoices i on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id where l.active and i.workspace_id=? and i.entity_id=? and i.status='POSTED' and not exists(select 1 from procurement_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id)),0)-coalesce((select sum(a.amount) from supplier_payment_allocations a join supplier_payments p on p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.id=a.payment_id where a.active and p.workspace_id=? and p.entity_id=? and p.status='POSTED' and not exists(select 1 from procurement_reversals r where r.workspace_id=p.workspace_id and r.entity_id=p.entity_id and r.payment_id=p.id)),0)",BigDecimal.class,workspaceId,entityId,workspaceId,entityId);
        BigDecimal grni=db.queryForObject("select coalesce((select sum(l.net) from procurement_receipt_lines l join procurement_receipts r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.id=l.receipt_id where l.active and r.workspace_id=? and r.entity_id=? and r.status='POSTED'),0)-coalesce((select sum(l.net) from vendor_invoice_lines l join vendor_invoices i on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id where l.active and i.workspace_id=? and i.entity_id=? and i.status='POSTED' and not exists(select 1 from procurement_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id)),0)",BigDecimal.class,workspaceId,entityId,workspaceId,entityId);
        BigDecimal[] expected={inventory,ap.negate(),grni.negate()};
        for(int i=0;i<3;i++) {
            BigDecimal actual=db.queryForObject("select coalesce(sum(l.debit-l.credit),0) from journal_lines l join journal_entries j on j.workspace_id=l.workspace_id and j.entity_id=l.entity_id and j.id=l.journal_id where l.workspace_id=? and l.entity_id=? and l.account_id=? and j.state='POSTED'",BigDecimal.class,workspaceId,entityId,ids[i]);
            if(actual.compareTo(expected[i])!=0) throw new DomainException(409,"Control account does not reconcile with its source; existing entries were not changed");
        }
        int changed=version==0 ? db.update("insert into procurement_posting_config(workspace_id,entity_id,inventory_account_id,ap_account_id,grni_account_id,adjustment_gain_account_id,adjustment_loss_account_id,service_expense_account_id,updated_by) values (?,?,?,?,?,?,?,?,?) on conflict do nothing",workspaceId,entityId,ids[0],ids[1],ids[2],ids[3],ids[4],ids[5],scope.actor().id())
                : db.update("update procurement_posting_config set inventory_account_id=?,ap_account_id=?,grni_account_id=?,adjustment_gain_account_id=?,adjustment_loss_account_id=?,service_expense_account_id=?,updated_by=?,version=version+1 where workspace_id=? and entity_id=? and version=?",ids[0],ids[1],ids[2],ids[3],ids[4],ids[5],scope.actor().id(),workspaceId,entityId,version);
        if(changed!=1) throw new DomainException(409,"Procurement configuration changed");
        audit.record(scope.actor(),workspaceId,entityId,"UPDATE","PROCUREMENT_CONFIG",Long.toString(entityId),version+1,"SUCCESS","procurement posting accounts");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
    }
    private void validateTaxAccounts(long w,long e,long input,long output) {
        Integer validInput=db.queryForObject("""
            select count(*) from chart_accounts a
            where a.workspace_id=? and a.entity_id=? and a.id=? and a.active and a.account_type='ASSET'
              and not exists(select 1 from procurement_posting_config p where p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.inventory_account_id=a.id)
              and not exists(select 1 from payment_posting_config p where p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and p.bank_account_id=a.id)
              and not exists(select 1 from sales_posting_config s where s.workspace_id=a.workspace_id and s.entity_id=a.entity_id and a.id in (s.ar_account_id,s.unbilled_account_id))
            """,Integer.class,w,e,input);
        if(validInput==null || validInput!=1) throw new DomainException(400,"Input Tax must be an active asset account distinct from Bank, Inventory, AR and Unbilled","inputAccount");
        Integer validOutput=db.queryForObject("""
            select count(*) from chart_accounts a
            where a.workspace_id=? and a.entity_id=? and a.id=? and a.active and a.account_type='LIABILITY'
              and not exists(select 1 from procurement_posting_config p where p.workspace_id=a.workspace_id and p.entity_id=a.entity_id and a.id in (p.ap_account_id,p.grni_account_id))
              and not exists(select 1 from service_posting_config s where s.workspace_id=a.workspace_id and s.entity_id=a.entity_id and s.deferred_account_id=a.id)
            """,Integer.class,w,e,output);
        if(validOutput==null || validOutput!=1) throw new DomainException(400,"Output Tax must be an active liability account distinct from AP, GRNI and Deferred","outputAccount");
    }
    @GetMapping("/finance/openings/new") public String openingForm(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="2") int count,Model model) {
        access.requireContext(workspaceId,entityId,false,"FINANCE"); context(model,workspaceId,entityId);
        if(count<2 || count>100) throw new DomainException(400,"Invalid line count");
        model.addAttribute("lineCount",count);
        model.addAttribute("accounts",db.queryForList("select id,code,name from chart_accounts where workspace_id=? and entity_id=? and active order by code",workspaceId,entityId));
        model.addAttribute("currency",db.queryForObject("select base_currency from legal_entities where workspace_id=? and id=?",String.class,workspaceId,entityId)); return "opening-form";
    }
    @PostMapping("/finance/openings/new") public String opening(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam String sourceKey,@RequestParam long revision,@RequestParam LocalDate date,@RequestParam String currency,@RequestParam String description,@RequestParam List<Long> accountId,@RequestParam List<BigDecimal> debit,@RequestParam List<BigDecimal> credit) {
        if(accountId.size()!=debit.size() || accountId.size()!=credit.size()) throw new DomainException(400,"Invalid journal lines","accountId","debit","credit");
        var lines=new java.util.ArrayList<Ledger.Line>();
        for(int i=0;i<accountId.size();i++) {
            if(accountId.get(i)==null) throw new DomainException(400,"Invalid account","accountId["+i+"]");
            lines.add(new Ledger.Line(accountId.get(i),debit.get(i),credit.get(i)));
        }
        var result=ledger.postOpening(workspaceId,entityId,new Ledger.Opening(sourceKey,revision,date,currency,description,lines));
        return "redirect:"+base(workspaceId,entityId)+"/finance/journals/"+result.journalId();
    }
    @GetMapping("/finance/journals/{id}") public String journal(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        access.requireContext(workspaceId,entityId,false,"FINANCE"); context(model,workspaceId,entityId);
        var entries=db.queryForList("select * from journal_entries where workspace_id=? and entity_id=? and id=?",workspaceId,entityId,id);
        if(entries.size()!=1) throw new DomainException(404,"Journal not found");
        model.addAttribute("journal",entries.get(0)); model.addAttribute("lines",db.queryForList("select a.code,a.name,l.debit,l.credit from journal_lines l join chart_accounts a on a.id=l.account_id and a.workspace_id=l.workspace_id and a.entity_id=l.entity_id where l.workspace_id=? and l.entity_id=? and l.journal_id=? order by l.id",workspaceId,entityId,id)); return "journal";
    }
    @GetMapping("/initialize") public String initializeForm(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        access.requireContext(workspaceId,entityId,false,"FINANCE"); context(model,workspaceId,entityId); model.addAttribute("version",db.queryForObject("select version from scenario_workspaces where id=?",Long.class,workspaceId)); return "initialize";
    }
    @PostMapping("/initialize") public String initialize(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam String baselineKey,@RequestParam long version) { baseline.initialize(workspaceId,entityId,baselineKey,version); return "redirect:"+base(workspaceId,entityId)+"/finance"; }
}
