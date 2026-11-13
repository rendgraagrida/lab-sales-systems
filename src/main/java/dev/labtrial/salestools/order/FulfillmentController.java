package dev.labtrial.salestools.order;

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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.HandlerMapping;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}")
public class FulfillmentController implements FormController {
    private final FulfillmentService service;
    private final AccessControl access;
    public FulfillmentController(FulfillmentService service,AccessControl access) { this.service=service; this.access=access; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e; }
    private void context(Model m,long w,long e) { var scope=access.requireContext(w,e,false,"OPERATIONS","FINANCE","INSIDE_SALES","SALES_MANAGER"); m.addAttribute("workspaceId",w); m.addAttribute("entityId",e); m.addAttribute("contextBase",base(w,e)); m.addAttribute("operations",scope.has("OPERATIONS")); }
    @ModelAttribute
    public void rejectUnexpectedParameters(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return;
        Set<String> allowed=Set.of("_csrf","_form","orderVersion","lineVersion","lineId","lineKey","warehouse","serialId","lotId","quantity","intent","reason","reservationId","number","date","version","count");
        boolean shipmentSave=request.getRequestURI().contains("/shipments/") && request.getRequestURI().endsWith("/save");
        Set<String> repeated=Set.of("lineKey","lineId","warehouse","quantity");
        for(var entry:request.getParameterMap().entrySet()) {
            boolean multiple=shipmentSave && repeated.contains(entry.getKey());
            if(!allowed.contains(entry.getKey()) || (!multiple && entry.getValue().length!=1) || (multiple && entry.getValue().length>100))
                throw new DomainException(400,"Unexpected or duplicate form field");
        }
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId");
        Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(variables instanceof Map<?,?> m && m.get("shipmentId")!=null) {
            long id=Long.parseLong(m.get("shipmentId").toString());
            if(request.getRequestURI().endsWith("/save")) {
                var shipment=service.shipment(w,e,id);
                return shipmentForm(w,e,((Number)shipment.get("order_id")).longValue(),id,submittedRows(request),model);
            }
            return detail(w,e,id,model);
        }
        if(variables instanceof Map<?,?> m && m.get("orderId")!=null) {
            long order=Long.parseLong(m.get("orderId").toString());
            if(request.getRequestURI().contains("/shipments/new")) return shipmentForm(w,e,order,null,submittedRows(request),model);
            return fulfillment(w,e,order,model);
        }
        throw new DomainException(400,"Invalid form context");
    }
    @GetMapping("/sales/orders/{orderId}/fulfillment")
    public String fulfillment(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long orderId,Model model) {
        long w=workspaceId,e=entityId; context(model,w,e); model.addAttribute("order",service.orderDetail(w,e,orderId)); model.addAttribute("lines",service.orderLines(w,e,orderId)); model.addAttribute("reservations",service.reservations(w,e,orderId));
        if(Boolean.TRUE.equals(model.getAttribute("operations"))) { model.addAttribute("warehouses",service.warehouses(w,e)); model.addAttribute("tracking",service.tracking(w,e,orderId)); }
        return "fulfillment-order";
    }
    @PostMapping("/sales/orders/{orderId}/fulfillment/reserve")
    public String reserve(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long orderId,@RequestParam long orderVersion,@RequestParam long lineId,@RequestParam long lineVersion,@RequestParam String warehouse,@RequestParam(required=false) Long serialId,@RequestParam(required=false) Long lotId,@RequestParam BigDecimal quantity,@RequestParam String intent) {
        long[] wh=reference(warehouse); service.reserve(workspaceId,entityId,orderId,orderVersion,lineId,lineVersion,wh[0],wh[1],serialId,lotId,quantity,intent); return redirectOrder(workspaceId,entityId,orderId);
    }
    @PostMapping("/sales/orders/{orderId}/fulfillment/release")
    public String release(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long orderId,@RequestParam long orderVersion,@RequestParam long lineId,@RequestParam long lineVersion,@RequestParam long reservationId,@RequestParam BigDecimal quantity,@RequestParam String intent) {
        service.release(workspaceId,entityId,orderId,orderVersion,lineId,lineVersion,reservationId,quantity,intent); return redirectOrder(workspaceId,entityId,orderId);
    }
    @PostMapping("/sales/orders/{orderId}/fulfillment/cancel")
    public String cancel(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long orderId,@RequestParam long orderVersion,@RequestParam long lineId,@RequestParam long lineVersion,@RequestParam BigDecimal quantity,@RequestParam String reason,@RequestParam String intent) {
        service.cancel(workspaceId,entityId,orderId,orderVersion,lineId,lineVersion,quantity,reason,intent); return redirectOrder(workspaceId,entityId,orderId);
    }
    private String redirectOrder(long w,long e,long id) { return "redirect:"+base(w,e)+"/sales/orders/"+id+"/fulfillment"; }
    @GetMapping("/sales/shipments")
    public String list(@PathVariable long workspaceId,@PathVariable long entityId,Model model) { context(model,workspaceId,entityId); model.addAttribute("shipments",service.shipments(workspaceId,entityId)); return "shipment-list"; }
    @GetMapping("/sales/orders/{orderId}/shipments/new")
    public String create(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long orderId,@RequestParam(defaultValue="1") int count,Model model) { return shipmentForm(workspaceId,entityId,orderId,null,count,model); }
    @GetMapping("/sales/shipments/{shipmentId}")
    public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long shipmentId,Model model) {
        context(model,workspaceId,entityId); var shipment=service.shipment(workspaceId,entityId,shipmentId); model.addAttribute("shipment",shipment); model.addAttribute("shipmentLines",service.shipmentLines(workspaceId,entityId,shipmentId)); return "shipment-detail";
    }
    @GetMapping("/sales/shipments/{shipmentId}/edit")
    public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long shipmentId,@RequestParam(defaultValue="1") int count,Model model) { var s=service.shipment(workspaceId,entityId,shipmentId); return shipmentForm(workspaceId,entityId,((Number)s.get("order_id")).longValue(),shipmentId,count,model); }
    private int submittedRows(HttpServletRequest request) {
        int count=1;
        for(String name:List.of("lineKey","lineId","warehouse","quantity")) { var values=request.getParameterValues(name); if(values!=null) count=Math.max(count,values.length); }
        return Math.min(count,100);
    }
    private String shipmentForm(long w,long e,long orderId,Long shipmentId,int count,Model model) {
        if(count<1 || count>100) throw new DomainException(400,"Choose between one and 100 rows");
        access.requireContext(w,e,false,"OPERATIONS"); context(model,w,e); model.addAttribute("order",service.orderDetail(w,e,orderId)); model.addAttribute("lines",service.orderLines(w,e,orderId)); model.addAttribute("warehouses",service.warehouses(w,e));
        var shipment=shipmentId==null?null:service.shipment(w,e,shipmentId);
        if(shipment!=null && !"DRAFT".equals(shipment.get("status"))) throw new DomainException(409,"Only draft shipments can be edited");
        var lines=shipmentId==null?List.<Map<String,Object>>of():service.shipmentLines(w,e,shipmentId);
        model.addAttribute("shipment",shipment); model.addAttribute("draftLines",lines); model.addAttribute("count",Math.max(count,lines.size()));
        model.addAttribute("actionUrl",base(w,e)+(shipmentId==null?"/sales/orders/"+orderId+"/shipments/new/save":"/sales/shipments/"+shipmentId+"/save"));
        model.addAttribute("formUrl",base(w,e)+(shipmentId==null?"/sales/orders/"+orderId+"/shipments/new":"/sales/shipments/"+shipmentId+"/edit"));
        return "shipment-form";
    }
    @PostMapping("/sales/orders/{orderId}/shipments/new/save")
    public String saveNew(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long orderId,@RequestParam long orderVersion,@RequestParam List<String> lineKey,@RequestParam List<String> lineId,@RequestParam List<String> warehouse,@RequestParam List<String> quantity,@RequestParam String number,@RequestParam LocalDate date) {
        return save(workspaceId,entityId,null,null,orderId,orderVersion,lineKey,lineId,warehouse,quantity,number,date);
    }
    @PostMapping("/sales/shipments/{shipmentId}/save")
    public String saveEdit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long shipmentId,@RequestParam long version,@RequestParam long orderVersion,@RequestParam List<String> lineKey,@RequestParam List<String> lineId,@RequestParam List<String> warehouse,@RequestParam List<String> quantity,@RequestParam String number,@RequestParam LocalDate date) {
        var s=service.shipment(workspaceId,entityId,shipmentId); return save(workspaceId,entityId,shipmentId,version,((Number)s.get("order_id")).longValue(),orderVersion,lineKey,lineId,warehouse,quantity,number,date);
    }
    private String save(long w,long e,Long id,Long version,long order,long orderVersion,List<String> keys,List<String> lines,List<String> warehouses,List<String> quantities,String number,LocalDate date) {
        if(keys.size()!=lines.size() || keys.size()!=warehouses.size() || keys.size()!=quantities.size() || keys.size()>100) throw new DomainException(400,"Shipment rows do not match");
        var entries=new ArrayList<FulfillmentService.ShipmentLineDraft>();
        for(int i=0;i<keys.size();i++) {
            String key=keys.get(i).trim(),line=lines.get(i).trim(),warehouse=warehouses.get(i).trim(),quantity=quantities.get(i).trim();
            if(key.isEmpty() && line.isEmpty() && warehouse.isEmpty() && quantity.isEmpty()) continue;
            long[] l=reference(line),wh=reference(warehouse);
            BigDecimal q; try { q=new BigDecimal(quantity); } catch(NumberFormatException ex) { throw new DomainException(400,"Invalid shipment quantity","quantity["+i+"]"); }
            entries.add(new FulfillmentService.ShipmentLineDraft(key,l[0],l[1],wh[0],wh[1],q));
        }
        long result=service.saveShipment(w,e,id,version,new FulfillmentService.ShipmentDraft(number,order,orderVersion,date,entries)); return "redirect:"+base(w,e)+"/sales/shipments/"+result;
    }
    @PostMapping("/sales/shipments/{shipmentId}/post")
    public String post(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long shipmentId,@RequestParam long version) { service.postShipment(workspaceId,entityId,shipmentId,version); return "redirect:"+base(workspaceId,entityId)+"/sales/shipments/"+shipmentId; }
    private static long[] reference(String text) { try { String[] p=text.split(":",-1); if(p.length!=2) throw new IllegalArgumentException(); long a=Long.parseLong(p[0]),b=Long.parseLong(p[1]); if(a<=0 || b<=0) throw new IllegalArgumentException(); return new long[]{a,b}; } catch(RuntimeException ex) { throw new DomainException(400,"Invalid versioned reference"); } }
}
