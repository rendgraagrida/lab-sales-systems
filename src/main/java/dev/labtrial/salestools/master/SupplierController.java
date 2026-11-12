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
@RequestMapping("/w/{workspaceId}/e/{entityId}/masters/suppliers")
public class SupplierController implements FormController {
    private final SupplierService service; private final ConfigurationService configuration;
    public SupplierController(SupplierService service,ConfigurationService configuration){this.service=service;this.configuration=configuration;}
    private static String base(long ws,long entity){return "/w/"+ws+"/e/"+entity+"/masters/suppliers";}
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        long ws=FormController.path(request,"workspaceId"), entity=FormController.path(request,"entityId");
        String path=request.getServletPath(), root=base(ws,entity);
        model.addAttribute("contactMode",false); model.addAttribute("supplierMode",true);
        model.addAttribute("productMode",false); model.addAttribute("priceMode",false);
        if(path.equals(root+"/save")) {
            Long id=FormController.parameterId(request,"id");
            if(id==null) { service.list(ws,entity,""); return create(ws,entity,model); }
            return edit(ws,entity,id,model);
        }
        long id=FormController.path(request,"id");
        if(path.equals(root+"/"+id+"/deactivate") || path.equals(root+"/"+id+"/assign") || path.equals(root+"/"+id+"/account"))
            return detail(ws,entity,id,model);
        throw new DomainException(400,"Unknown form");
    }
    @ModelAttribute("supplierMode") public boolean supplierMode(){return true;}
    @ModelAttribute("contactMode") public boolean contactMode(){return false;}
    @ModelAttribute("productMode") public boolean productMode(){return false;}
    @ModelAttribute("priceMode") public boolean priceMode(){return false;}
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="")String q,Model model){model.addAttribute("suppliers",service.list(workspaceId,entityId,q));model.addAttribute("q",q);model.addAttribute("base",base(workspaceId,entityId));return "masters";}
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model){model.addAttribute("base",base(workspaceId,entityId));return "master-form";}
    @GetMapping("/{id:[0-9]+}")
    public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("supplier",service.get(workspaceId,entityId,id));
        boolean finance=service.isFinance(workspaceId,entityId);
        model.addAttribute("finance",finance);
        if(finance) {
            var account=service.account(workspaceId,entityId,id);
            model.addAttribute("account",account);
            model.addAttribute("terms",configuration.list(workspaceId,entityId,ConfigurationService.Kind.TERMS,"").stream()
                    .filter(term -> term.active() || (account != null && account.termsId() != null && account.termsId() == term.id())).toList());
        }
        model.addAttribute("base",base(workspaceId,entityId));
        return "master-detail";
    }
    @GetMapping("/{id:[0-9]+}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model){model.addAttribute("supplier",service.get(workspaceId,entityId,id));model.addAttribute("base",base(workspaceId,entityId));return "master-form";}
    @PostMapping("/save") public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","id","version","code","name","email"));long id=service.save(workspaceId,entityId,number(p.get("id")),number(p.get("version")),p.get("code"),p.get("name"),p.get("email"));return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/deactivate") public String deactivate(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","version"));Long v=number(p.get("version"));if(v==null)throw new DomainException(400,"Version required");service.deactivate(workspaceId,entityId,id,v);return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/assign") public String assign(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","version","erpCode"));Long v=number(p.get("version"));if(v==null)throw new DomainException(400,"Version required");service.assign(workspaceId,entityId,id,v,p.get("erpCode"));return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/account") public String account(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","supplierVersion","accountVersion","erpCode","terms","active"));Long v=number(p.get("supplierVersion")),av=number(p.get("accountVersion")),terms=null,tv=null;if(v==null)throw new DomainException(400,"Version required");String t=p.get("terms");if(t!=null&&!t.isBlank()){String[] parts=t.split(":",-1);if(parts.length!=2)throw new DomainException(400,"Invalid terms","terms");terms=number(parts[0], "terms");tv=number(parts[1], "terms");if(terms==null||tv==null)throw new DomainException(400,"Invalid terms","terms");}String active=p.get("active");if(!"true".equals(active)&&!"false".equals(active))throw new DomainException(400,"Invalid active state","active");service.saveAccount(workspaceId,entityId,id,v,av,p.get("erpCode"),terms,tv,Boolean.parseBoolean(active));return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    private static void allowed(Map<String,String> p,Set<String> fields){if(p.keySet().stream().anyMatch(key -> !key.equals("_form") && !fields.contains(key)))throw new DomainException(400,"Unexpected field");}
    private static Long number(String v,String field){try{return number(v);}catch(DomainException e){throw new DomainException(400,"Invalid number",field);}}
    private static Long number(String v){if(v==null||v.isBlank())return null;try{return Long.valueOf(v);}catch(NumberFormatException e){throw new DomainException(400,"Invalid number");}}
}
