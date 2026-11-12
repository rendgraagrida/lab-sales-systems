package dev.labtrial.salestools.inventory;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/inventory")
public class InventoryController implements FormController {
    private final InventoryService inventory;
    private final AccessControl access;
    private final JdbcTemplate db;
    public InventoryController(InventoryService inventory, AccessControl access, JdbcTemplate db) { this.inventory=inventory; this.access=access; this.db=db; }
    private String base(long w,long e) { return "/w/"+w+"/e/"+e+"/inventory"; }
    private void context(Model model,long w,long e,boolean finance) {
        model.addAttribute("workspaceId",w); model.addAttribute("entityId",e);
        model.addAttribute("contextBase","/w/"+w+"/e/"+e); model.addAttribute("finance",finance);
    }
    @ModelAttribute
    public void rejectUnexpectedParameters(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return;
        Set<String> allowed=request.getRequestURI().endsWith("/save") ? Set.of("actionKey","kind","product","fromWarehouse","toWarehouse","quantity","value","date","reason","serial","lot","expiresOn","trackingQuantity","version","count") : Set.of("version");
        Set<String> repeated=Set.of("serial","lot","expiresOn","trackingQuantity");
        for(String name:request.getParameterMap().keySet()) {
            if(!name.equals("_csrf") && !name.equals("_form") && !allowed.contains(name)) throw new DomainException(400,"Unexpected form field");
            if(!repeated.contains(name) && request.getParameterValues(name).length!=1) throw new DomainException(400,"Duplicate form field");
            if(repeated.contains(name) && request.getParameterValues(name).length>100) throw new DomainException(400,"At most 100 tracking rows are allowed");
        }
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"), e=FormController.path(request,"entityId");
        Long id=FormController.parameterId(request,"id");
        Object pathId=request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(pathId instanceof java.util.Map<?,?> map && map.get("actionId")!=null) {
            try { id=Long.valueOf(map.get("actionId").toString()); } catch(NumberFormatException ex) { throw new DomainException(400,"Invalid action"); }
        }
        int count=1;
        for(String field:List.of("serial","lot","expiresOn","trackingQuantity")) { var values=request.getParameterValues(field); if(values!=null) count=Math.max(count,values.length); }
        return form(w,e,id,Math.min(count,100),model);
    }
    @GetMapping
    public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,Model model) {
        var scope=access.requireContext(workspaceId,entityId,false,"OPERATIONS","FINANCE");
        context(model,workspaceId,entityId,scope.has("FINANCE")); model.addAttribute("q",q);
        model.addAttribute("balances",inventory.balances(workspaceId,entityId,q));
        model.addAttribute("actions",inventory.actions(workspaceId,entityId,q));
        return "inventory-list";
    }
    @GetMapping("/products/{productId}/movements")
    public String movements(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long productId,Model model) {
        var scope=access.requireContext(workspaceId,entityId,false,"OPERATIONS","FINANCE");
        context(model,workspaceId,entityId,scope.has("FINANCE"));
        model.addAttribute("movements",inventory.movements(workspaceId,entityId,productId)); return "inventory-movements";
    }
    @GetMapping("/actions/new")
    public String newAction(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="1") int count,Model model) {
        return form(workspaceId,entityId,null,count,model);
    }
    @GetMapping("/actions/{actionId}")
    public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long actionId,@RequestParam(defaultValue="1") int count,Model model) {
        return form(workspaceId,entityId,actionId,count,model);
    }
    private String form(long w,long e,Long id,int count,Model model) {
        var scope=access.requireContext(w,e,false,"OPERATIONS","FINANCE"); context(model,w,e,scope.has("FINANCE"));
        InventoryService.Action a=id==null?null:inventory.action(w,e,id);
        List<InventoryService.TrackingInput> tracks=id==null?List.of():inventory.actionTracking(w,e,id);
        model.addAttribute("action",a); model.addAttribute("tracks",tracks); model.addAttribute("count",100);
        model.addAttribute("operations",scope.has("OPERATIONS"));
        model.addAttribute("products",db.queryForList("""
            select p.id,p.sku,p.name,p.version,p.uom_id,u.version as uom_version,s.version as setting_version
            from products p join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id
            join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=?
            where p.workspace_id=? and p.active and u.active and s.active and p.category not in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') order by p.sku
            """,e,w));
        model.addAttribute("warehouses",db.queryForList("select id,code,name,version from warehouses where workspace_id=? and entity_id=? and active order by code",w,e));
        model.addAttribute("actionUrl",base(w,e)+"/actions/"+(id==null?"new":id)+"/save");
        return "inventory-action";
    }
    @PostMapping({"/actions/new/save","/actions/{actionId}/save"})
    public String save(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable(required=false) Long actionId,
            @RequestParam(required=false) Long version,@RequestParam String actionKey,@RequestParam String kind,@RequestParam String product,
            @RequestParam(defaultValue="") String fromWarehouse,@RequestParam(defaultValue="") String toWarehouse,
            @RequestParam BigDecimal quantity,@RequestParam(required=false) BigDecimal value,@RequestParam LocalDate date,@RequestParam String reason,
            @RequestParam(required=false) List<String> serial,@RequestParam(required=false) List<String> lot,
            @RequestParam(required=false) List<String> expiresOn,@RequestParam(required=false) List<String> trackingQuantity) {
        long[] p=reference(product,5,"product"); Long[] from=warehouse(fromWarehouse,"fromWarehouse"), to=warehouse(toWarehouse,"toWarehouse");
        List<InventoryService.TrackingInput> rows=parseTracking(serial,lot,expiresOn,trackingQuantity);
        long id=inventory.saveAction(workspaceId,entityId,actionId,version,new InventoryService.ActionInput(actionKey,kind,p[0],p[1],p[2],p[3],p[4],from[0],from[1],to[0],to[1],quantity,value,date,reason,rows));
        return "redirect:"+base(workspaceId,entityId)+"/actions/"+id;
    }
    @PostMapping("/actions/{actionId}/submit")
    public String submit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long actionId,@RequestParam long version) {
        inventory.submitAction(workspaceId,entityId,actionId,version); return "redirect:"+base(workspaceId,entityId)+"/actions/"+actionId;
    }
    @PostMapping("/actions/{actionId}/post")
    public String post(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long actionId,@RequestParam long version) {
        inventory.postAction(workspaceId,entityId,actionId,version); return "redirect:"+base(workspaceId,entityId)+"/actions/"+actionId;
    }
    private static long[] reference(String value,int size,String field) {
        try { String[] parts=value.split(":",-1); if(parts.length!=size) throw new IllegalArgumentException(); long[] result=new long[size]; for(int i=0;i<size;i++) { result[i]=Long.parseLong(parts[i]); if(result[i]<=0) throw new IllegalArgumentException(); } return result; }
        catch(RuntimeException ex) { throw new DomainException(400,"Invalid reference",field); }
    }
    private static Long[] warehouse(String value,String field) {
        if(value==null || value.isBlank()) return new Long[]{null,null}; long[] v=reference(value,2,field); return new Long[]{v[0],v[1]};
    }
    private static String cell(List<String> values,int i) { return values==null || i>=values.size()?"":values.get(i).trim(); }
    private static List<InventoryService.TrackingInput> parseTracking(List<String> serial,List<String> lot,List<String> expiry,List<String> quantity) {
        int n=Math.max(Math.max(serial==null?0:serial.size(),lot==null?0:lot.size()),Math.max(expiry==null?0:expiry.size(),quantity==null?0:quantity.size()));
        if(n>100) throw new DomainException(400,"At most 100 tracking rows are allowed");
        var result=new ArrayList<InventoryService.TrackingInput>();
        for(int i=0;i<n;i++) {
            String s=cell(serial,i),l=cell(lot,i),d=cell(expiry,i),q=cell(quantity,i);
            if(s.isEmpty() && l.isEmpty() && d.isEmpty() && q.isEmpty()) continue;
            try { result.add(new InventoryService.TrackingInput(s.isEmpty()?null:s,l.isEmpty()?null:l,d.isEmpty()?null:LocalDate.parse(d),new BigDecimal(q))); }
            catch(NumberFormatException ex) { throw new DomainException(400,"Invalid tracking quantity","trackingQuantity["+i+"]"); }
            catch(DateTimeParseException ex) { throw new DomainException(400,"Invalid expiry date","expiresOn["+i+"]"); }
        }
        return result;
    }
}
