package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/masters/products")
public class ProductController implements FormController {
    private final ProductService service; private final ConfigurationService configuration;
    public ProductController(ProductService service,ConfigurationService configuration){this.service=service;this.configuration=configuration;}
    private static String base(long ws,long entity){return "/w/"+ws+"/e/"+entity+"/masters/products";}
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        long ws=FormController.path(request,"workspaceId"), entity=FormController.path(request,"entityId");
        String path=request.getServletPath(), root=base(ws,entity);
        model.addAttribute("contactMode",false); model.addAttribute("supplierMode",false);
        model.addAttribute("productMode",true); model.addAttribute("priceMode",false);
        if(path.equals(root+"/save")) {
            Long id=FormController.parameterId(request,"id");
            if(id==null) { service.list(ws,entity,""); return create(ws,entity,model); }
            return edit(ws,entity,id,model);
        }
        long id=FormController.path(request,"id");
        if(path.equals(root+"/"+id+"/deactivate") || path.equals(root+"/"+id+"/setting"))
            return detail(ws,entity,id,model);
        throw new DomainException(400,"Unknown form");
    }
    @ModelAttribute("productMode") public boolean productMode(){return true;}
    @ModelAttribute("supplierMode") public boolean supplierMode(){return false;}
    @ModelAttribute("contactMode") public boolean contactMode(){return false;}
    @ModelAttribute("priceMode") public boolean priceMode(){return false;}
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="")String q,Model model){model.addAttribute("products",service.list(workspaceId,entityId,q));model.addAttribute("q",q);model.addAttribute("base",base(workspaceId,entityId));return "masters";}
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model){model.addAttribute("units",configuration.list(workspaceId,entityId,ConfigurationService.Kind.UOM,"").stream().filter(ConfigurationService.Entry::active).toList());model.addAttribute("base",base(workspaceId,entityId));return "master-form";}
    @GetMapping("/{id:[0-9]+}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model){model.addAttribute("product",service.get(workspaceId,entityId,id));model.addAttribute("setting",service.setting(workspaceId,entityId,id));model.addAttribute("base",base(workspaceId,entityId));return "master-detail";}
    @GetMapping("/{id:[0-9]+}/edit")
    public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        var product=service.get(workspaceId,entityId,id);
        model.addAttribute("product",product);
        model.addAttribute("units",configuration.list(workspaceId,entityId,ConfigurationService.Kind.UOM,"").stream()
                .filter(unit -> unit.active() || product.uomId() == unit.id()).toList());
        model.addAttribute("base",base(workspaceId,entityId));
        return "master-form";
    }
    @PostMapping("/save") public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","id","version","sku","name","category","productLine","unit","tracking"));String unit=p.get("unit");String[] u=unit==null?new String[0]:unit.split(":",-1);if(u.length!=2)throw new DomainException(400,"Unit is required","unit");Long uid=number(u[0], "unit"),uv=number(u[1], "unit");if(uid==null||uv==null)throw new DomainException(400,"Unit is required","unit");long id=service.save(workspaceId,entityId,number(p.get("id")),number(p.get("version")),new ProductService.Input(p.get("sku"),p.get("name"),p.get("category"),p.get("productLine"),uid,uv,p.get("tracking")));return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/deactivate") public String deactivate(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","version"));Long v=number(p.get("version"));if(v==null)throw new DomainException(400,"Version required");service.deactivate(workspaceId,entityId,id,v);return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/setting") public String setting(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","productVersion","settingVersion","active"));Long pv=number(p.get("productVersion")),sv=number(p.get("settingVersion"));if(pv==null)throw new DomainException(400,"Version required");String active=p.get("active");if(!"true".equals(active)&&!"false".equals(active))throw new DomainException(400,"Invalid active state","active");service.assign(workspaceId,entityId,id,pv,sv,Boolean.parseBoolean(active));return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    private static void allowed(Map<String,String> p,Set<String> fields){if(p.keySet().stream().anyMatch(key -> !key.equals("_form") && !fields.contains(key)))throw new DomainException(400,"Unexpected field");}
    private static Long number(String v,String field){try{return number(v);}catch(DomainException e){throw new DomainException(400,"Invalid number",field);}}
    private static Long number(String v){if(v==null||v.isBlank())return null;try{return Long.valueOf(v);}catch(NumberFormatException e){throw new DomainException(400,"Invalid number");}}
}
