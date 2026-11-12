package dev.labtrial.salestools.procurement;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import dev.labtrial.salestools.inventory.InventoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/procurement")
public class ProcurementController implements FormController {
    private final ProcurementService service;
    public ProcurementController(ProcurementService service) { this.service=service; }
    private static String base(long w,long e) { return "/w/"+w+"/e/"+e+"/procurement"; }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId");
        String root=base(w,e),path=request.getServletPath();
        if(path.equals(root+"/requests/save")) {
            Long id=FormController.parameterId(request,"id");
            return form(w,e,id,model);
        }
        if(path.equals(root+"/orders/save")) return orderForm(w,e,FormController.parameterId(request,"id"),model);
        if(path.equals(root+"/receipts/save")) return receiptForm(w,e,FormController.parameterId(request,"id"),model);
        if(path.contains("/orders/")) return orderDetail(w,e,FormController.path(request,"id"),model);
        if(path.contains("/receipts/")) return receiptDetail(w,e,FormController.path(request,"id"),model);
        return detail(w,e,FormController.path(request,"id"),model);
    }
    @GetMapping public String index(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) {
        validateStatus(status,false); model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("requests",service.requests(workspaceId,entityId,q).stream().filter(x->status.isEmpty() || status.equals(x.status())).toList()); model.addAttribute("q",q); model.addAttribute("status",status); return "procurement-list";
    }
    @GetMapping("/requests/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model) { return form(workspaceId,entityId,null,model); }
    @GetMapping("/requests/{id:[0-9]+}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) { return form(workspaceId,entityId,id,model); }
    private String form(long w,long e,Long id,Model model) {
        var request=id==null?null:service.getRequest(w,e,id);
        if(request!=null && !"DRAFT".equals(request.status())) throw new DomainException(409,"Only drafts can be edited");
        model.addAttribute("base",base(w,e)); model.addAttribute("purchaseRequest",request);
        model.addAttribute("lines",id==null?List.of():service.requestLines(w,e,id)); model.addAttribute("products",service.products(w,e));
        return "procurement-request-form";
    }
    @GetMapping("/requests/{id:[0-9]+}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("purchaseRequest",service.getRequest(workspaceId,entityId,id));
        model.addAttribute("lines",service.requestLines(workspaceId,entityId,id)); return "procurement-request-detail";
    }
    @PostMapping("/requests/save") public String save(@PathVariable long workspaceId,@PathVariable long entityId,HttpServletRequest request) {
        allowed(request,Set.of("_csrf","_form","id","version","businessDate","purpose","lineKey","productRef","quantity"));
        Long id=optionalLong(single(request,"id")), version=optionalLong(single(request,"version"));
        LocalDate date; try { date=LocalDate.parse(single(request,"businessDate")); } catch(DateTimeParseException|NullPointerException ex) { throw new DomainException(400,"Invalid business date","businessDate"); }
        String[] keys=request.getParameterValues("lineKey"),refs=request.getParameterValues("productRef"),quantities=request.getParameterValues("quantity");
        if(keys==null || refs==null || quantities==null || keys.length>100 || keys.length!=refs.length || keys.length!=quantities.length) throw new DomainException(400,"Invalid request lines");
        var lines=new ArrayList<ProcurementService.RequestLineInput>();
        for(int i=0;i<keys.length;i++) {
            if(keys[i].isBlank() && refs[i].isBlank() && quantities[i].isBlank()) continue;
            String[] ref=refs[i].split(":",-1);
            if(ref.length!=4) throw new DomainException(400,"Choose a product reference","productRef["+i+"]");
            try { lines.add(new ProcurementService.RequestLineInput(keys[i],Long.parseLong(ref[0]),Long.parseLong(ref[1]),Long.parseLong(ref[2]),Long.parseLong(ref[3]),new BigDecimal(quantities[i]))); }
            catch(NumberFormatException ex) { throw new DomainException(400,"Invalid product or quantity","quantity["+i+"]"); }
        }
        long result=service.saveRequest(workspaceId,entityId,id,version,new ProcurementService.RequestInput(date,single(request,"purpose"),lines));
        return "redirect:"+base(workspaceId,entityId)+"/requests/"+result;
    }
    @PostMapping("/requests/{id}/submit") public String submit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest request) {
        allowed(request,Set.of("_csrf","_form","version")); service.submitRequest(workspaceId,entityId,id,requiredLong(single(request,"version"))); return redirect(workspaceId,entityId,id);
    }
    @PostMapping("/requests/{id}/approve") public String approve(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest request) {
        allowed(request,Set.of("_csrf","_form","version","reason")); service.decideRequest(workspaceId,entityId,id,requiredLong(single(request,"version")),true,single(request,"reason")); return redirect(workspaceId,entityId,id);
    }
    @PostMapping("/requests/{id}/reject") public String reject(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest request) {
        allowed(request,Set.of("_csrf","_form","version","reason")); service.decideRequest(workspaceId,entityId,id,requiredLong(single(request,"version")),false,single(request,"reason")); return redirect(workspaceId,entityId,id);
    }
    @PostMapping("/requests/{id}/revise") public String revise(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest request) {
        allowed(request,Set.of("_csrf","_form","version")); service.reviseRequest(workspaceId,entityId,id,requiredLong(single(request,"version"))); return redirect(workspaceId,entityId,id);
    }
    @GetMapping("/orders") public String orderList(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) {
        validateStatus(status,false); model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("rows",service.orders(workspaceId,entityId,q).stream().filter(x->status.isEmpty() || status.equals(x.get("status"))).toList()); model.addAttribute("q",q); model.addAttribute("status",status); model.addAttribute("mode","orders"); return "procurement-documents";
    }
    @GetMapping("/orders/new") public String orderCreate(@PathVariable long workspaceId,@PathVariable long entityId,Model model) { return orderForm(workspaceId,entityId,null,model); }
    @GetMapping("/orders/{id:[0-9]+}/edit") public String orderEdit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) { return orderForm(workspaceId,entityId,id,model); }
    private String orderForm(long w,long e,Long id,Model model) {
        Map<String,Object> order=id==null?null:service.order(w,e,id);
        if(order!=null && !"DRAFT".equals(order.get("status"))) throw new DomainException(409,"Only drafts can be edited");
        model.addAttribute("base",base(w,e)); model.addAttribute("order",order); model.addAttribute("lines",id==null?List.of():service.orderLines(w,e,id));
        model.addAttribute("suppliers",service.supplierChoices(w,e)); model.addAttribute("sources",service.approvedRequestLines(w,e)); model.addAttribute("taxes",service.taxChoices(w,e)); model.addAttribute("configVersion",service.configVersion(w,e)); return "procurement-order-form";
    }
    @GetMapping("/orders/{id:[0-9]+}") public String orderDetail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("order",service.order(workspaceId,entityId,id)); model.addAttribute("lines",service.orderLines(workspaceId,entityId,id)); return "procurement-order-detail";
    }
    @PostMapping("/orders/save") public String saveOrder(@PathVariable long workspaceId,@PathVariable long entityId,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","id","version","supplierRef","configVersion","businessDate","lineKey","requestLineRef","previousLineId","quantity","unitCost","discountPct","discountAmount","taxRef","serviceProfile"));
        Long id=optionalLong(single(r,"id")),version=optionalLong(single(r,"version")); long[] supplier=ref(single(r,"supplierRef"),2,"supplierRef");
        long config=requiredLong(single(r,"configVersion")); String[][] a=arrays(r,"lineKey","requestLineRef","previousLineId","quantity","unitCost","discountPct","discountAmount","taxRef","serviceProfile");
        var lines=new ArrayList<ProcurementService.OrderLineInput>();
        for(int i=0;i<a[0].length;i++) {
            if(emptyRow(a,i)) continue;
            long source=ref(a[1][i],1,"requestLineRef["+i+"]")[0]; Long previous=optionalLong(a[2][i]); long[] tax=ref(a[7][i],2,"taxRef["+i+"]");
            lines.add(new ProcurementService.OrderLineInput(a[0][i],source,previous,decimal(a[3][i],false,"quantity["+i+"]"),decimal(a[4][i],false,"unitCost["+i+"]"),decimal(a[5][i],true,"discountPct["+i+"]"),decimal(a[6][i],true,"discountAmount["+i+"]"),tax[0],tax[1],a[8][i].isBlank()?null:a[8][i]));
        }
        long result=service.saveOrder(workspaceId,entityId,id,version,new ProcurementService.OrderInput(supplier[0],supplier[1],config,date(single(r,"businessDate"),false,"businessDate"),lines));
        return "redirect:"+base(workspaceId,entityId)+"/orders/"+result;
    }
    @PostMapping("/orders/{id}/{action:submit|approve|reject|revise}") public String orderAction(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@PathVariable String action,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","version","reason")); long version=requiredLong(single(r,"version"));
        switch(action) {
            case "submit" -> service.submitOrder(workspaceId,entityId,id,version);
            case "approve" -> service.decideOrder(workspaceId,entityId,id,version,true,single(r,"reason"));
            case "reject" -> service.decideOrder(workspaceId,entityId,id,version,false,single(r,"reason"));
            case "revise" -> service.reviseOrder(workspaceId,entityId,id,version);
            default -> throw new DomainException(400,"Invalid action");
        }
        return "redirect:"+base(workspaceId,entityId)+"/orders/"+id;
    }
    @GetMapping("/receipts") public String receiptList(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,Model model) {
        validateStatus(status,true); model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("rows",service.receipts(workspaceId,entityId,q).stream().filter(x->status.isEmpty() || status.equals(x.get("status"))).toList()); model.addAttribute("q",q); model.addAttribute("status",status); model.addAttribute("mode","receipts"); return "procurement-documents";
    }
    @GetMapping("/receipts/new") public String receiptCreate(@PathVariable long workspaceId,@PathVariable long entityId,Model model) { return receiptForm(workspaceId,entityId,null,model); }
    @GetMapping("/receipts/{id:[0-9]+}/edit") public String receiptEdit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) { return receiptForm(workspaceId,entityId,id,model); }
    private String receiptForm(long w,long e,Long id,Model model) {
        Map<String,Object> receipt=id==null?null:service.receipt(w,e,id);
        if(receipt!=null && !"DRAFT".equals(receipt.get("status"))) throw new DomainException(409,"Only drafts can be edited");
        model.addAttribute("base",base(w,e)); model.addAttribute("receipt",receipt); model.addAttribute("lines",id==null?List.of():service.receiptLines(w,e,id)); model.addAttribute("tracks",id==null?List.of():service.receiptTracking(w,e,id));
        model.addAttribute("orders",service.orders(w,e,"").stream().filter(x->"APPROVED".equals(x.get("status"))).toList()); model.addAttribute("sources",service.receiptSources(w,e)); model.addAttribute("warehouses",service.warehouseChoices(w,e)); return "receipt-form";
    }
    @GetMapping("/receipts/{id:[0-9]+}") public String receiptDetail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("receipt",service.receipt(workspaceId,entityId,id)); model.addAttribute("lines",service.receiptLines(workspaceId,entityId,id)); model.addAttribute("tracks",service.receiptTracking(workspaceId,entityId,id)); return "receipt-detail";
    }
    @PostMapping("/receipts/save") public String saveReceipt(@PathVariable long workspaceId,@PathVariable long entityId,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","id","version","orderRef","kind","date","lineKey","orderLineRef","quantity","warehouseRef","trackingLineKey","serial","lot","expiresOn","trackingQuantity"));
        Long id=optionalLong(single(r,"id")),version=optionalLong(single(r,"version")); long[] order=ref(single(r,"orderRef"),2,"orderRef");
        LocalDate date=date(single(r,"date"),false,"date");
        String[][] a=arrays(r,"lineKey","orderLineRef","quantity","warehouseRef");
        String[][] t=arrays(r,"trackingLineKey","serial","lot","expiresOn","trackingQuantity");
        Map<String,List<InventoryService.TrackingInput>> tracking=new LinkedHashMap<>();
        for(int i=0;i<t[0].length;i++) {
            if(emptyRow(t,i)) continue;
            if(t[0][i].isBlank()) throw new DomainException(400,"Tracking line key required","trackingLineKey["+i+"]");
            tracking.computeIfAbsent(t[0][i],x->new ArrayList<>()).add(new InventoryService.TrackingInput(blankNull(t[1][i]),blankNull(t[2][i]),date(t[3][i],true,"expiresOn["+i+"]"),decimal(t[4][i],false,"trackingQuantity["+i+"]")));
        }
        var lines=new ArrayList<ProcurementService.ReceiptLineInput>();
        for(int i=0;i<a[0].length;i++) {
            if(emptyRow(a,i)) continue;
            long source=ref(a[1][i],1,"orderLineRef["+i+"]")[0]; Long warehouse=null,warehouseVersion=null;
            if(!a[3][i].isBlank()) { long[] ref=ref(a[3][i],2,"warehouseRef["+i+"]"); warehouse=ref[0]; warehouseVersion=ref[1]; }
            lines.add(new ProcurementService.ReceiptLineInput(a[0][i],source,decimal(a[2][i],false,"quantity["+i+"]"),warehouse,warehouseVersion,tracking.remove(a[0][i])));
        }
        if(!tracking.isEmpty()) throw new DomainException(400,"Tracking refers to an unknown line","trackingLineKey");
        long result=service.saveReceipt(workspaceId,entityId,id,version,new ProcurementService.ReceiptInput(order[0],order[1],single(r,"kind"),date,lines));
        return "redirect:"+base(workspaceId,entityId)+"/receipts/"+result;
    }
    @PostMapping("/receipts/{id}/post") public String postReceipt(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","version","intent")); service.postReceipt(workspaceId,entityId,id,requiredLong(single(r,"version")),single(r,"intent")); return "redirect:"+base(workspaceId,entityId)+"/receipts/"+id;
    }
    private static String[][] arrays(HttpServletRequest r,String... names) {
        String[][] result=new String[names.length][];
        for(int i=0;i<names.length;i++) { result[i]=r.getParameterValues(names[i]); if(result[i]==null || result[i].length>100 || (i>0 && result[i].length!=result[0].length)) throw new DomainException(400,"Invalid repeated fields"); }
        return result;
    }
    private static boolean emptyRow(String[][] values,int i) { for(String[] value:values) if(!value[i].isBlank()) return false; return true; }
    private static String blankNull(String s) { return s==null || s.isBlank()?null:s.trim(); }
    private static BigDecimal decimal(String value,boolean optional,String field) { if(optional && (value==null || value.isBlank())) return null; try { return new BigDecimal(value); } catch(NumberFormatException | NullPointerException ex) { throw new DomainException(400,"Invalid amount",field); } }
    private static LocalDate date(String value,boolean optional,String field) { if(optional && (value==null || value.isBlank())) return null; try { return LocalDate.parse(value); } catch(DateTimeParseException | NullPointerException ex) { throw new DomainException(400,"Invalid date",field); } }
    private static long[] ref(String value,int size,String field) { String[] p=value==null?new String[0]:value.split(":",-1); if(p.length!=size) throw new DomainException(400,"Invalid reference",field); long[] result=new long[size]; for(int i=0;i<size;i++) { try { result[i]=requiredLong(p[i]); } catch(DomainException ex) { throw new DomainException(400,"Invalid reference",field); } } return result; }
    private static void validateStatus(String status,boolean receipt) { if(!status.isEmpty() && !(receipt?Set.of("DRAFT","POSTED"):Set.of("DRAFT","SUBMITTED","APPROVED","REJECTED")).contains(status)) throw new DomainException(400,"Invalid status","status"); }
    private static String redirect(long w,long e,long id) { return "redirect:"+base(w,e)+"/requests/"+id; }
    private static void allowed(HttpServletRequest request,Set<String> names) { if(!names.containsAll(request.getParameterMap().keySet())) throw new DomainException(400,"Unexpected field"); }
    private static String single(HttpServletRequest request,String name) { String[] values=request.getParameterValues(name); if(values==null) return null; if(values.length!=1) throw new DomainException(400,"Invalid field",name); return values[0]; }
    private static Long optionalLong(String v) { if(v==null || v.isBlank()) return null; return requiredLong(v); }
    private static long requiredLong(String v) { try { long n=Long.parseLong(v); if(n>0) return n; } catch(NumberFormatException | NullPointerException ignored) {} throw new DomainException(400,"Invalid version or reference"); }
}
