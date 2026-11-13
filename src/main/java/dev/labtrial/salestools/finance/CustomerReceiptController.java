package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/finance")
public class CustomerReceiptController implements FormController {
    private final CustomerReceiptService receipts; private final AccessControl access; private final JdbcTemplate db;
    public CustomerReceiptController(CustomerReceiptService receipts,AccessControl access,JdbcTemplate db) { this.receipts=receipts; this.access=access; this.db=db; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e+"/finance"; }
    @ModelAttribute public void validateParameters(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return; String path=request.getRequestURI();
        Set<String> allowed=path.endsWith("/new")||path.endsWith("/edit")?Set.of("version","receiptKey","customerAccountId","customerVersion","configVersion","paymentConfigVersion","paymentDate","currency","amount","invoiceId","allocationAmount"):path.endsWith("/reverse")?Set.of("reversalDate","reason","reversalKey"):Set.of("version");
        for(String key:request.getParameterMap().keySet()) if(!key.equals("_csrf")&&!key.equals("_form")) {
            int count=request.getParameterValues(key).length;
            if(!allowed.contains(key)||count>100||(!key.equals("invoiceId")&&!key.equals("allocationAmount")&&count!=1)) throw new DomainException(400,"Unexpected form field");
        }
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId"); String path=request.getRequestURI();
        int count=1; for(String name:List.of("invoiceId","allocationAmount")) { var v=request.getParameterValues(name); if(v!=null) count=Math.max(count,v.length); }
        if(path.endsWith("/new")) return form(w,e,null,Math.min(count,100),model);
        long id=FormController.path(request,"id"); if(path.endsWith("/edit")) return form(w,e,id,Math.min(count,100),model); return detail(w,e,id,model);
    }
    @GetMapping("/ar") public String ar(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(required=false) LocalDate asOf,Model model) {
        access.requireContext(workspaceId,entityId,false,"FINANCE");
        if(asOf==null) asOf=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,workspaceId);
        model.addAttribute("asOf",asOf); model.addAttribute("invoices",receipts.aging(workspaceId,entityId,asOf)); return "ar-list";
    }
    @GetMapping("/customer-receipts") public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) { model.addAttribute("receipts",receipts.list(workspaceId,entityId,q,status)); model.addAttribute("q",q); model.addAttribute("status",status); return "customer-receipt-list"; }
    @GetMapping("/customer-receipts/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="1") int count,Model model) { return form(workspaceId,entityId,null,count,model); }
    @GetMapping("/customer-receipts/{id}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam(defaultValue="0") int count,Model model) { return form(workspaceId,entityId,id,count,model); }
    private String form(long w,long e,Long id,int count,Model model) {
        access.requireContext(w,e,false,"FINANCE"); if(count<0||count>100) throw new DomainException(400,"Invalid allocation count");
        Map<String,Object> header=id==null?new HashMap<>():receipts.detail(w,e,id);
        if(id==null) for(String field:List.of("version","receipt_key","customer_account_id","customer_account_version","config_version","payment_config_version","payment_date","currency","amount")) header.put(field,null);
        if(id!=null&&!"DRAFT".equals(header.get("status"))) throw new DomainException(409,"Posted receipt cannot be edited");
        var lines=id==null?List.<Map<String,Object>>of():receipts.allocations(w,e,id);
        model.addAttribute("header",header); model.addAttribute("lines",lines); model.addAttribute("lineCount",count==0?Math.max(1,lines.size()):count);
        model.addAttribute("action",base(w,e)+"/customer-receipts"+(id==null?"/new":"/"+id+"/edit"));
        model.addAttribute("customers",db.queryForList("select id,version from customer_entity_accounts where workspace_id=? and entity_id=? and active order by id",w,e));
        model.addAttribute("invoices",db.queryForList("select id,reference,customer_account_id,currency from sales_invoices where workspace_id=? and entity_id=? and status='POSTED' order by id desc",w,e));
        model.addAttribute("entityCurrency",db.queryForObject("select base_currency from legal_entities where workspace_id=? and id=?",String.class,w,e));
        model.addAttribute("configs",db.queryForList("select s.version as config_version,p.version as payment_config_version from sales_posting_config s join payment_posting_config p on p.workspace_id=s.workspace_id and p.entity_id=s.entity_id where s.workspace_id=? and s.entity_id=?",w,e));
        return "customer-receipt-form";
    }
    @PostMapping({"/customer-receipts/new","/customer-receipts/{id}/edit"}) public String save(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable(required=false) Long id,@RequestParam(defaultValue="0") long version,@RequestParam String receiptKey,@RequestParam long customerAccountId,@RequestParam long customerVersion,@RequestParam long configVersion,@RequestParam long paymentConfigVersion,@RequestParam LocalDate paymentDate,@RequestParam String currency,@RequestParam BigDecimal amount,@RequestParam List<Long> invoiceId,@RequestParam List<BigDecimal> allocationAmount) {
        if(invoiceId.isEmpty()||invoiceId.size()>100||invoiceId.size()!=allocationAmount.size()) throw new DomainException(400,"Allocation fields must align");
        var allocations=new ArrayList<CustomerReceiptService.Allocation>(); for(int i=0;i<invoiceId.size();i++) allocations.add(new CustomerReceiptService.Allocation(invoiceId.get(i),allocationAmount.get(i)));
        long result=receipts.save(workspaceId,entityId,id,version,new CustomerReceiptService.Draft(receiptKey,customerAccountId,customerVersion,configVersion,paymentConfigVersion,paymentDate,currency,amount,allocations)); return "redirect:"+base(workspaceId,entityId)+"/customer-receipts/"+result;
    }
    @GetMapping("/customer-receipts/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("receipt",receipts.detail(workspaceId,entityId,id)); model.addAttribute("allocations",receipts.allocations(workspaceId,entityId,id));
        model.addAttribute("reversals",db.queryForList("select * from sales_reversals where workspace_id=? and entity_id=? and receipt_id=?",workspaceId,entityId,id)); return "customer-receipt-detail";
    }
    @PostMapping("/customer-receipts/{id}/post") public String post(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version) { receipts.post(workspaceId,entityId,id,version); return "redirect:"+base(workspaceId,entityId)+"/customer-receipts/"+id; }
    @PostMapping("/customer-receipts/{id}/reverse") public String reverse(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam LocalDate reversalDate,@RequestParam String reason,@RequestParam String reversalKey) { receipts.reverse(workspaceId,entityId,id,reversalDate,reason,reversalKey); return "redirect:"+base(workspaceId,entityId)+"/customer-receipts/"+id; }
}
