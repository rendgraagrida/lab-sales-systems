package dev.labtrial.salestools.procurement;

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
@RequestMapping("/w/{workspaceId}/e/{entityId}/procurement/vendor-invoices")
public class VendorInvoiceController implements FormController {
    private final VendorInvoiceService invoices; private final AccessControl access; private final JdbcTemplate db;
    public VendorInvoiceController(VendorInvoiceService invoices,AccessControl access,JdbcTemplate db) { this.invoices=invoices; this.access=access; this.db=db; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e+"/procurement/vendor-invoices"; }
    @ModelAttribute public void validateParameters(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return;
        String uri=request.getRequestURI(); boolean save=uri.endsWith("/new")||uri.endsWith("/edit");
        Set<String> repeated=Set.of("lineKey","receiptLineId","quantity","unitCost","discountPct","discountAmount","subtotal","discount","net","tax","gross");
        Set<String> allowed=save?Set.of("version","reference","invoiceDate","supplierVersion","configVersion","lineKey","receiptLineId","quantity","unitCost","discountPct","discountAmount","subtotal","discount","net","tax","gross"):uri.endsWith("/reverse")?Set.of("reversalDate","reason","reversalKey"):Set.of("version");
        for(var name:request.getParameterMap().keySet()) if(!name.equals("_csrf")&&!name.equals("_form")) { if(!allowed.contains(name)||(!repeated.contains(name)&&request.getParameterValues(name).length!=1)||request.getParameterValues(name).length>100) throw new DomainException(400,"Unexpected form field"); }
    }
    @Override public String redisplayForm(HttpServletRequest req,Model model) { long w=FormController.path(req,"workspaceId"),e=FormController.path(req,"entityId"); String uri=req.getRequestURI(); if(uri.endsWith("/new")) return form(w,e,null,count(req),model); long id=FormController.path(req,"id"); if(uri.endsWith("/edit")) return form(w,e,id,count(req),model); return detail(w,e,id,model); }
    private int count(HttpServletRequest r) { int c=1; for(String name:List.of("lineKey","receiptLineId","quantity","unitCost","discountPct","discountAmount","subtotal","discount","net","tax","gross")) { var v=r.getParameterValues(name); if(v!=null)c=Math.max(c,v.length); } return Math.min(c,100); }
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) { model.addAttribute("invoices",invoices.list(workspaceId,entityId,q,status)); model.addAttribute("q",q); model.addAttribute("status",status); return "vendor-invoice-list"; }
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="1") int count,Model model) { return form(workspaceId,entityId,null,count,model); }
    @GetMapping("/{id}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam(defaultValue="0") int count,Model model) { return form(workspaceId,entityId,id,count,model); }
    private String form(long w,long e,Long id,int count,Model model) {
        access.requireContext(w,e,false,"OPERATIONS","FINANCE"); if(count<0||count>100) throw new DomainException(400,"Line count must be between 1 and 100");
        Map<String,Object> header;
        if(id==null) {
            header=new java.util.HashMap<>();
            for(String field:List.of("version","reference","invoice_date","supplier_account_version","config_version")) header.put(field,null);
        } else header=invoices.detail(w,e,id);
        var lines=id==null?List.<Map<String,Object>>of():invoices.lines(w,e,id);
        if(id!=null && "POSTED".equals(header.get("status"))) throw new DomainException(409,"Posted invoice cannot be edited");
        model.addAttribute("header",header); model.addAttribute("lines",lines); model.addAttribute("lineCount",count==0?Math.max(1,lines.size()):count); model.addAttribute("action",base(w,e)+(id==null?"/new":"/"+id+"/edit"));
        model.addAttribute("sources",db.queryForList("select rl.id,rc.number,rl.line_key,rl.slice_end-rl.slice_start as quantity,sa.version as supplier_version,rev.config_version from procurement_receipt_lines rl join procurement_receipts rc on rc.workspace_id=rl.workspace_id and rc.entity_id=rl.entity_id and rc.id=rl.receipt_id join purchase_orders po on po.workspace_id=rc.workspace_id and po.entity_id=rc.entity_id and po.id=rc.order_id join supplier_entity_accounts sa on sa.workspace_id=po.workspace_id and sa.entity_id=po.entity_id and sa.id=po.supplier_account_id join purchase_order_lines ol on ol.workspace_id=rl.workspace_id and ol.entity_id=rl.entity_id and ol.id=rl.order_line_id join purchase_order_revisions rev on rev.workspace_id=ol.workspace_id and rev.entity_id=ol.entity_id and rev.id=ol.revision_id where rl.workspace_id=? and rl.entity_id=? and rl.active and rc.status='POSTED' order by rc.id desc,rl.id",w,e)); return "vendor-invoice-form";
    }
    private static BigDecimal decimal(String s) { return s==null||s.isBlank()?null:new BigDecimal(s); }
    @PostMapping({"/new","/{id}/edit"}) public String save(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable(required=false) Long id,@RequestParam(defaultValue="0") long version,@RequestParam String reference,@RequestParam LocalDate invoiceDate,@RequestParam long supplierVersion,@RequestParam long configVersion,@RequestParam List<String> lineKey,@RequestParam List<Long> receiptLineId,@RequestParam List<BigDecimal> quantity,@RequestParam List<BigDecimal> unitCost,@RequestParam List<BigDecimal> subtotal,@RequestParam List<BigDecimal> discount,@RequestParam List<BigDecimal> net,@RequestParam List<BigDecimal> tax,@RequestParam List<BigDecimal> gross,HttpServletRequest request) {
        String[] discountPct=request.getParameterValues("discountPct"), discountAmount=request.getParameterValues("discountAmount");
        int count=lineKey.size(); if(count<1||count>100||discountPct==null||discountAmount==null||discountPct.length!=count||discountAmount.length!=count||List.of(receiptLineId,quantity,unitCost,subtotal,discount,net,tax,gross).stream().anyMatch(x->x.size()!=count)) throw new DomainException(400,"Invoice line fields must align");
        var lines=new ArrayList<VendorInvoiceService.Line>(); for(int i=0;i<count;i++) lines.add(new VendorInvoiceService.Line(lineKey.get(i),receiptLineId.get(i),quantity.get(i),unitCost.get(i),decimal(discountPct[i]),decimal(discountAmount[i]),subtotal.get(i),discount.get(i),net.get(i),tax.get(i),gross.get(i)));
        long result=invoices.save(workspaceId,entityId,id,version,new VendorInvoiceService.Draft(reference,invoiceDate,supplierVersion,configVersion,lines)); return "redirect:"+base(workspaceId,entityId)+"/"+result;
    }
    @GetMapping("/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) { var scope=access.requireContext(workspaceId,entityId,false,"OPERATIONS","FINANCE"); model.addAttribute("finance",scope.has("FINANCE")); model.addAttribute("invoice",invoices.detail(workspaceId,entityId,id)); model.addAttribute("lines",invoices.lines(workspaceId,entityId,id)); model.addAttribute("reversals",db.queryForList("select * from procurement_reversals where workspace_id=? and entity_id=? and invoice_id=?",workspaceId,entityId,id)); model.addAttribute("history",db.queryForList("select action,outcome,detail,occurred_at from audit_events where workspace_id=? and entity_id=? and target_type='VENDOR_INVOICE' and target_id=? order by id",workspaceId,entityId,Long.toString(id))); return "vendor-invoice-detail"; }
    @PostMapping("/{id}/match") public String match(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version) { invoices.post(workspaceId,entityId,id,version); return "redirect:"+base(workspaceId,entityId)+"/"+id; }
    @PostMapping("/{id}/reverse") public String reverse(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam LocalDate reversalDate,@RequestParam String reason,@RequestParam String reversalKey) { invoices.reverse(workspaceId,entityId,id,reversalDate,reason,reversalKey); return "redirect:"+base(workspaceId,entityId)+"/"+id; }
}
