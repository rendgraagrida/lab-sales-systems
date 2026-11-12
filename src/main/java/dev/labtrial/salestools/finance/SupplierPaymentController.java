package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/finance")
public class SupplierPaymentController implements FormController {
    private final SupplierPaymentService payments; private final AccessControl access; private final JdbcTemplate db;
    public SupplierPaymentController(SupplierPaymentService payments,AccessControl access,JdbcTemplate db) { this.payments=payments; this.access=access; this.db=db; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e+"/finance"; }
    @ModelAttribute public void validateParameters(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return; String path=request.getRequestURI();
        Set<String> allowed=path.endsWith("/new")||path.endsWith("/edit")?Set.of("version","paymentKey","supplierAccountId","supplierVersion","bankAccountVersion","configVersion","paymentDate","currency","amount","invoiceId","allocationAmount"):path.endsWith("/reverse")?Set.of("reversalDate","reason","reversalKey"):Set.of("version");
        for(String key:request.getParameterMap().keySet()) if(!key.equals("_csrf")&&!key.equals("_form")) { int count=request.getParameterValues(key).length; if(!allowed.contains(key)||count>100||(!key.equals("invoiceId")&&!key.equals("allocationAmount")&&count!=1)) throw new DomainException(400,"Unexpected form field"); }
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) { long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId"); String path=request.getRequestURI(); int count=1; for(String name:List.of("invoiceId","allocationAmount")) { var values=request.getParameterValues(name); if(values!=null) count=Math.max(count,values.length); } if(path.endsWith("/new")) return form(w,e,null,Math.min(100,count),model); long id=FormController.path(request,"id"); if(path.endsWith("/edit")) return form(w,e,id,Math.min(100,count),model); return detail(w,e,id,model); }
    @GetMapping("/ap") public String ap(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(required=false) LocalDate asOf,Model model) { access.requireContext(workspaceId,entityId,false,"FINANCE"); if(asOf==null) asOf=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,workspaceId); model.addAttribute("asOf",asOf); model.addAttribute("invoices",payments.aging(workspaceId,entityId,asOf)); return "ap-list"; }
    @GetMapping("/supplier-payments") public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) { model.addAttribute("payments",payments.list(workspaceId,entityId,q,status)); model.addAttribute("q",q); model.addAttribute("status",status); return "supplier-payment-list"; }
    @GetMapping("/supplier-payments/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="1") int count,Model model) { return form(workspaceId,entityId,null,count,model); }
    @GetMapping("/supplier-payments/{id}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam(defaultValue="0") int count,Model model) { return form(workspaceId,entityId,id,count,model); }
    private String form(long w,long e,Long id,int count,Model model) {
        access.requireContext(w,e,false,"FINANCE"); if(count<0||count>100) throw new DomainException(400,"Allocation count must be between 1 and 100");
        Map<String,Object> header;
        if(id==null) {
            header=new java.util.HashMap<>();
            for(String field:List.of("version","payment_key","supplier_account_id","supplier_account_version","bank_account_version","config_version","payment_date","currency","amount")) header.put(field,null);
        } else header=payments.detail(w,e,id);
        var lines=id==null?List.<Map<String,Object>>of():payments.allocations(w,e,id);
        if(id!=null&&"POSTED".equals(header.get("status"))) throw new DomainException(409,"Posted payment cannot be edited");
        model.addAttribute("header",header); model.addAttribute("lines",lines); model.addAttribute("lineCount",count==0?Math.max(1,lines.size()):count); model.addAttribute("action",base(w,e)+"/supplier-payments"+(id==null?"/new":"/"+id+"/edit"));
        model.addAttribute("suppliers",db.queryForList("select id,version from supplier_entity_accounts where workspace_id=? and entity_id=? order by id",w,e));
        model.addAttribute("invoices",db.queryForList("select id,reference from vendor_invoices where workspace_id=? and entity_id=? and status='POSTED' order by id desc",w,e));
        model.addAttribute("entityCurrency",db.queryForObject("select base_currency from legal_entities where workspace_id=? and id=?",String.class,w,e));
        model.addAttribute("configs",db.queryForList("select p.version as config_version,a.version as bank_account_version from payment_posting_config p join chart_accounts a on a.workspace_id=p.workspace_id and a.entity_id=p.entity_id and a.id=p.bank_account_id where p.workspace_id=? and p.entity_id=?",w,e)); return "supplier-payment-form";
    }
    @PostMapping({"/supplier-payments/new","/supplier-payments/{id}/edit"}) public String save(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable(required=false) Long id,@RequestParam(defaultValue="0") long version,@RequestParam String paymentKey,@RequestParam long supplierAccountId,@RequestParam long supplierVersion,@RequestParam long bankAccountVersion,@RequestParam long configVersion,@RequestParam LocalDate paymentDate,@RequestParam String currency,@RequestParam BigDecimal amount,@RequestParam List<Long> invoiceId,@RequestParam List<BigDecimal> allocationAmount) {
        if(invoiceId.size()!=allocationAmount.size()||invoiceId.isEmpty()||invoiceId.size()>100) throw new DomainException(400,"Allocation fields must align"); var allocations=new ArrayList<SupplierPaymentService.Allocation>(); for(int i=0;i<invoiceId.size();i++) allocations.add(new SupplierPaymentService.Allocation(invoiceId.get(i),allocationAmount.get(i)));
        long result=payments.save(workspaceId,entityId,id,version,new SupplierPaymentService.Draft(paymentKey,supplierAccountId,supplierVersion,bankAccountVersion,configVersion,paymentDate,currency,amount,allocations)); return "redirect:"+base(workspaceId,entityId)+"/supplier-payments/"+result;
    }
    @GetMapping("/supplier-payments/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) { access.requireContext(workspaceId,entityId,false,"FINANCE"); model.addAttribute("payment",payments.detail(workspaceId,entityId,id)); model.addAttribute("allocations",payments.allocations(workspaceId,entityId,id)); model.addAttribute("reversals",db.queryForList("select * from procurement_reversals where workspace_id=? and entity_id=? and payment_id=?",workspaceId,entityId,id)); return "supplier-payment-detail"; }
    @PostMapping("/supplier-payments/{id}/post") public String post(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version) { payments.post(workspaceId,entityId,id,version); return "redirect:"+base(workspaceId,entityId)+"/supplier-payments/"+id; }
    @PostMapping("/supplier-payments/{id}/reverse") public String reverse(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam LocalDate reversalDate,@RequestParam String reason,@RequestParam String reversalKey) { payments.reverse(workspaceId,entityId,id,reversalDate,reason,reversalKey); return "redirect:"+base(workspaceId,entityId)+"/supplier-payments/"+id; }
}
