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
        if(path.endsWith("/finance/accounts") || path.endsWith("/finance/taxes") || path.endsWith("/finance/payment-config")
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
        Integer valid=db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=? and active and account_type='ASSET'",Integer.class,workspaceId,entityId,bankAccount);
        if(valid==null || valid!=1) throw new DomainException(400,"Select an active asset account","bankAccount");
        int updated;
        if(version==0) updated=db.update("insert into payment_posting_config(workspace_id,entity_id,bank_account_id,updated_by) values (?,?,?,?) on conflict do nothing",workspaceId,entityId,bankAccount,scope.actor().id());
        else updated=db.update("update payment_posting_config set bank_account_id=?,updated_by=?,version=version+1 where workspace_id=? and entity_id=? and version=?",bankAccount,scope.actor().id(),workspaceId,entityId,version);
        if(updated!=1) throw new DomainException(409,"Payment configuration changed");
        audit.record(scope.actor(),workspaceId,entityId,"UPDATE","PAYMENT_CONFIG",Long.toString(entityId),version+1,"SUCCESS","bank account");
        return "redirect:"+base(workspaceId,entityId)+"/finance";
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
