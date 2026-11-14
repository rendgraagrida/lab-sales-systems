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
@RequestMapping("/w/{workspaceId}/e/{entityId}/sales/invoices")
public class SalesInvoiceController implements FormController {
    private final SalesInvoiceService invoices; private final AccessControl access; private final JdbcTemplate db;
    public SalesInvoiceController(SalesInvoiceService invoices,AccessControl access,JdbcTemplate db) { this.invoices=invoices; this.access=access; this.db=db; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e+"/sales/invoices"; }
    @ModelAttribute public void validateParameters(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return;
        String path=request.getRequestURI(); boolean edit=path.endsWith("/new")||path.endsWith("/edit");
        Set<String> allowed=edit?Set.of("version","reference","invoiceDate","lineKey","sourceType","sourceId","sliceStart","sliceEnd"):path.endsWith("/reverse")?Set.of("reversalDate","reason","reversalKey"):Set.of("version");
        for(String key:request.getParameterMap().keySet()) if(!key.equals("_csrf")&&!key.equals("_form")) {
            int count=request.getParameterValues(key).length;
            if(!allowed.contains(key)||count>100||(!Set.of("lineKey","sourceType","sourceId","sliceStart","sliceEnd").contains(key)&&count!=1)) throw new DomainException(400,"Unexpected form field");
        }
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId"); String path=request.getRequestURI();
        int count=1; for(String name:List.of("lineKey","sourceType","sourceId","sliceStart","sliceEnd")) { var v=request.getParameterValues(name); if(v!=null) count=Math.max(count,v.length); }
        if(path.endsWith("/new")) return form(w,e,null,Math.min(count,100),model);
        long id=FormController.path(request,"id"); if(path.endsWith("/edit")) return form(w,e,id,Math.min(count,100),model); return detail(w,e,id,model);
    }
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) {
        model.addAttribute("invoices",invoices.list(workspaceId,entityId,q,status)); model.addAttribute("q",q); model.addAttribute("status",status); return "sales-invoice-list";
    }
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="1") int count,Model model) { return form(workspaceId,entityId,null,count,model); }
    @GetMapping("/{id}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam(defaultValue="0") int count,Model model) { return form(workspaceId,entityId,id,count,model); }
    private String form(long w,long e,Long id,int count,Model model) {
        access.requireContext(w,e,false,"FINANCE"); if(count<0||count>100) throw new DomainException(400,"Invalid line count");
        Map<String,Object> header=id==null?new HashMap<>():invoices.detail(w,e,id);
        if(id!=null&&!"DRAFT".equals(header.get("status"))) throw new DomainException(409,"Posted invoice cannot be edited");
        var lines=id==null?List.<Map<String,Object>>of():invoices.lines(w,e,id);
        model.addAttribute("header",header); model.addAttribute("lines",lines); model.addAttribute("lineCount",count==0?Math.max(1,lines.size()):count);
        if(id==null) for(String field:List.of("version","reference","invoice_date")) header.put(field,null);
        model.addAttribute("action",base(w,e)+(id==null?"/new":"/"+id+"/edit"));
        model.addAttribute("sources",db.queryForList("select l.id,l.slice_start,l.slice_end,s.number,s.shipment_date,o.customer_account_id,o.currency from shipment_lines l join shipments s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.id=l.shipment_id join sales_orders o on o.workspace_id=s.workspace_id and o.entity_id=s.entity_id and o.id=s.order_id where l.workspace_id=? and l.entity_id=? and l.active and s.status='POSTED' and o.sealed order by l.id desc",w,e));
        model.addAttribute("obligations",db.queryForList("select a.id,a.net,a.accepted_date,o.customer_account_id,a.currency from sales_service_obligations a join sales_order_lines l on l.workspace_id=a.workspace_id and l.entity_id=a.entity_id and l.id=a.order_line_id join sales_orders o on o.workspace_id=l.workspace_id and o.entity_id=l.entity_id and o.id=l.order_id and o.sealed where a.workspace_id=? and a.entity_id=? and a.kind='ONBOARDING' and a.status<>'CANCELLED' order by a.id",w,e));
        model.addAttribute("terms",db.queryForList("select t.id,t.net_fee,t.coverage_start,t.coverage_end,t.currency,c.customer_account_id from service_contract_terms t join service_contract_assets ca on ca.workspace_id=t.workspace_id and ca.entity_id=t.entity_id and ca.id=t.contract_asset_id join service_contracts c on c.workspace_id=ca.workspace_id and c.entity_id=ca.entity_id and c.id=ca.contract_id where t.workspace_id=? and t.entity_id=? and t.status='VALIDATED' order by t.id",w,e));
        model.addAttribute("billingHistory",db.queryForList("select l.source_type,coalesce(l.shipment_line_id,l.service_obligation_id,l.service_term_id) source_id,coalesce(l.slice_start,l.fee_start) interval_start,coalesce(l.slice_end,l.fee_end) interval_end,i.reference,i.invoice_date,r.reversal_date from sales_invoice_lines l join sales_invoices i on i.workspace_id=l.workspace_id and i.entity_id=l.entity_id and i.id=l.invoice_id left join sales_reversals r on r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id where l.workspace_id=? and l.entity_id=? and l.active and i.status='POSTED' order by l.source_type,source_id,i.invoice_date,l.id",w,e));
        return "sales-invoice-form";
    }
    @PostMapping({"/new","/{id}/edit"}) public String save(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable(required=false) Long id,@RequestParam(defaultValue="0") long version,@RequestParam String reference,@RequestParam LocalDate invoiceDate,@RequestParam List<String> lineKey,@RequestParam List<SalesInvoiceService.SourceType> sourceType,@RequestParam List<Long> sourceId,@RequestParam List<BigDecimal> sliceStart,@RequestParam List<BigDecimal> sliceEnd) {
        int size=lineKey.size(); if(size==0||size>100||sourceType.size()!=size||sourceId.size()!=size||sliceStart.size()!=size||sliceEnd.size()!=size) throw new DomainException(400,"Invoice line fields must align");
        var lines=new ArrayList<SalesInvoiceService.Line>(); for(int i=0;i<size;i++) lines.add(new SalesInvoiceService.Line(lineKey.get(i),sourceType.get(i),sourceId.get(i),sliceStart.get(i),sliceEnd.get(i)));
        long result=invoices.save(workspaceId,entityId,id,version,new SalesInvoiceService.Draft(reference,invoiceDate,lines)); return "redirect:"+base(workspaceId,entityId)+"/"+result;
    }
    @GetMapping("/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("invoice",invoices.detail(workspaceId,entityId,id)); model.addAttribute("lines",invoices.lines(workspaceId,entityId,id));
        model.addAttribute("reversals",db.queryForList("select * from sales_reversals where workspace_id=? and entity_id=? and invoice_id=?",workspaceId,entityId,id)); return "sales-invoice-detail";
    }
    @PostMapping("/{id}/post") public String post(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam long version) { invoices.post(workspaceId,entityId,id,version); return "redirect:"+base(workspaceId,entityId)+"/"+id; }
    @PostMapping("/{id}/reverse") public String reverse(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam LocalDate reversalDate,@RequestParam String reason,@RequestParam String reversalKey) { invoices.reverse(workspaceId,entityId,id,reversalDate,reason,reversalKey); return "redirect:"+base(workspaceId,entityId)+"/"+id; }
}
