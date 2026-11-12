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
@RequestMapping("/w/{workspaceId}/e/{entityId}/masters/contacts")
public class ContactController implements FormController {
    private final ContactService service;
    public ContactController(ContactService service){this.service=service;}
    private static String base(long ws,long entity){return "/w/"+ws+"/e/"+entity+"/masters/contacts";}
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        long ws=FormController.path(request,"workspaceId"), entity=FormController.path(request,"entityId");
        String path=request.getServletPath(), root=base(ws,entity);
        model.addAttribute("contactMode",true); model.addAttribute("supplierMode",false);
        model.addAttribute("productMode",false); model.addAttribute("priceMode",false);
        if(path.equals(root+"/save")) {
            Long id=FormController.parameterId(request,"id");
            if(id==null) { service.list(ws,entity,""); return create(ws,entity,model); }
            return edit(ws,entity,id,model);
        }
        long id=FormController.path(request,"id");
        if(path.equals(root+"/"+id+"/deactivate")) return detail(ws,entity,id,model);
        throw new DomainException(400,"Unknown form");
    }
    @ModelAttribute("supplierMode") public boolean supplierMode(){return false;}
    @ModelAttribute("productMode") public boolean productMode(){return false;}
    @ModelAttribute("priceMode") public boolean priceMode(){return false;}
    @GetMapping
    public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,Model model){
        model.addAttribute("contacts",service.list(workspaceId,entityId,q));model.addAttribute("q",q);model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("contactMode",true);return "masters";
    }
    @GetMapping("/new")
    public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model){model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("contactMode",true);return "master-form";}
    @GetMapping("/{id:[0-9]+}")
    public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model){model.addAttribute("contact",service.get(workspaceId,entityId,id));model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("contactMode",true);return "master-detail";}
    @GetMapping("/{id:[0-9]+}/edit")
    public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model){model.addAttribute("contact",service.get(workspaceId,entityId,id));model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("contactMode",true);return "master-form";}
    @PostMapping("/save")
    public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p){
        allowed(p,Set.of("_csrf","id","version","code","firstName","lastName","email","lifecycle"));
        long id=service.save(workspaceId,entityId,number(p.get("id")),number(p.get("version")),p.get("code"),p.get("firstName"),p.get("lastName"),p.get("email"),p.get("lifecycle"));return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","version"));Long version=number(p.get("version"));if(version==null)throw new DomainException(400,"Version required");service.deactivate(workspaceId,entityId,id,version);return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    private static void allowed(Map<String,String> p,Set<String> fields){if(p.keySet().stream().anyMatch(key -> !key.equals("_form") && !fields.contains(key)))throw new DomainException(400,"Unexpected field");}
    private static Long number(String v){if(v==null||v.isBlank())return null;try{return Long.valueOf(v);}catch(NumberFormatException e){throw new DomainException(400,"Invalid number");}}
}
