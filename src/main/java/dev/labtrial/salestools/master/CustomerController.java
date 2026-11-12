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
@RequestMapping("/w/{workspaceId}/e/{entityId}/masters/customers")
public class CustomerController implements FormController {
    private final CustomerService service;
    private final CustomerAccountService accounts;
    private final ConfigurationService configuration;
    private final ContactService contacts;
    public CustomerController(CustomerService service, CustomerAccountService accounts, ConfigurationService configuration, ContactService contacts) {
        this.service=service; this.accounts=accounts; this.configuration=configuration; this.contacts=contacts;
    }
    private static String base(long ws,long entity) { return "/w/"+ws+"/e/"+entity+"/masters/customers"; }
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        long ws=FormController.path(request,"workspaceId"), entity=FormController.path(request,"entityId");
        String path=request.getServletPath(), root=base(ws,entity);
        model.addAttribute("contactMode",false); model.addAttribute("supplierMode",false);
        model.addAttribute("productMode",false); model.addAttribute("priceMode",false);
        if(path.equals(root+"/save")) {
            Long id=FormController.parameterId(request,"id");
            return id==null ? create(ws,entity,model) : edit(ws,entity,id,model);
        }
        long id=FormController.path(request,"id");
        if(path.equals(root+"/"+id+"/deactivate") || path.equals(root+"/"+id+"/assign")
                || path.equals(root+"/"+id+"/account") || path.equals(root+"/"+id+"/contact-link"))
            return detail(ws,entity,id,model);
        throw new DomainException(400,"Unknown form");
    }
    @ModelAttribute("contactMode")
    public boolean contactMode() { return false; }
    @ModelAttribute("supplierMode")
    public boolean supplierMode() { return false; }
    @ModelAttribute("productMode") public boolean productMode() { return false; }
    @ModelAttribute("priceMode") public boolean priceMode() { return false; }
    @GetMapping
    public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,Model model) {
        model.addAttribute("customers",service.list(workspaceId,entityId,q)); model.addAttribute("q",q);
        model.addAttribute("base",base(workspaceId,entityId)); return "masters";
    }
    @GetMapping("/new")
    public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        model.addAttribute("parents",service.list(workspaceId,entityId,"").stream().filter(CustomerRepository.Customer::active).toList()); model.addAttribute("customer",null);
        model.addAttribute("base",base(workspaceId,entityId)); return "master-form";
    }
    @GetMapping("/{id:[0-9]+}")
    public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("customer",service.get(workspaceId,entityId,id));
        var people=contacts.list(workspaceId,entityId,"");
        model.addAttribute("contacts",people);
        model.addAttribute("contactChoices",people.stream().filter(ContactService.Contact::active).toList());
        model.addAttribute("links",contacts.links(workspaceId,entityId,id));
        boolean finance=accounts.canRead(workspaceId,entityId);
        model.addAttribute("finance",finance);
        if(finance) {
            var account=accounts.get(workspaceId,entityId,id);
            model.addAttribute("account",account);
            model.addAttribute("terms",configuration.list(workspaceId,entityId,ConfigurationService.Kind.TERMS,"").stream()
                    .filter(term -> term.active() || (account != null && account.termsId() != null && account.termsId() == term.id())).toList());
        }
        model.addAttribute("base",base(workspaceId,entityId)); return "master-detail";
    }
    @GetMapping("/{id:[0-9]+}/edit")
    public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        var customer=service.get(workspaceId,entityId,id);
        if(customer.parentId()!=null) service.get(workspaceId,entityId,customer.parentId());
        model.addAttribute("customer",customer);
        model.addAttribute("parents",service.list(workspaceId,entityId,"").stream()
                .filter(parent -> (parent.active() || (customer.parentId() != null && customer.parentId() == parent.id())) && parent.id() != customer.id()).toList());
        model.addAttribute("base",base(workspaceId,entityId)); return "master-form";
    }
    @PostMapping("/save")
    public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,Set.of("_csrf","id","version","code","name","segment","territory","domain","parent"));
        Long id=number(p.get("id")),version=number(p.get("version")),parentId=null,parentVersion=null;
        String parent=p.get("parent");
        if(parent!=null && !parent.isBlank()) {
            String[] parts=parent.split(":",-1);
            if(parts.length!=2) throw new DomainException(400,"Invalid parent","parent");
            parentId=number(parts[0],"parent"); parentVersion=number(parts[1],"parent");
            if(parentId==null || parentVersion==null) throw new DomainException(400,"Invalid parent","parent");
        }
        long saved=service.save(workspaceId,entityId,id,version,new CustomerService.Input(p.get("code"),p.get("name"),p.get("segment"),p.get("territory"),p.get("domain"),parentId,parentVersion));
        return "redirect:"+base(workspaceId,entityId)+"/"+saved;
    }
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,Set.of("_csrf","version")); Long version=number(p.get("version"));
        if(version==null) throw new DomainException(400,"Version required");
        service.deactivate(workspaceId,entityId,id,version); return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    @PostMapping("/{id}/assign")
    public String assign(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,Set.of("_csrf","version","erpCode"));
        Long version=number(p.get("version"));
        if(version==null) throw new DomainException(400,"Version required");
        accounts.assign(workspaceId,entityId,id,version,p.get("erpCode"));
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    @PostMapping("/{id}/account")
    public String account(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,Set.of("_csrf","customerVersion","accountVersion","erpCode","credit","terms","active"));
        Long customerVersion=number(p.get("customerVersion")), accountVersion=number(p.get("accountVersion")), termsId=null, termsVersion=null;
        if(customerVersion==null) throw new DomainException(400,"Version required");
        String terms=p.get("terms");
        if(terms!=null && !terms.isBlank()) {
            String[] parts=terms.split(":",-1);
            if(parts.length!=2) throw new DomainException(400,"Invalid terms","terms");
            termsId=number(parts[0],"terms"); termsVersion=number(parts[1],"terms");
            if(termsId==null || termsVersion==null) throw new DomainException(400,"Invalid terms","terms");
        }
        String active=p.get("active");
        if(!"true".equals(active) && !"false".equals(active)) throw new DomainException(400,"Invalid active state","active");
        accounts.save(workspaceId,entityId,id,customerVersion,accountVersion,p.get("erpCode"),p.get("credit"),termsId,termsVersion,Boolean.parseBoolean(active));
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    @PostMapping("/{id}/contact-link")
    public String link(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,Set.of("_csrf","customerVersion","contact","linkVersion","role","active"));
        Long customerVersion=number(p.get("customerVersion")),linkVersion=number(p.get("linkVersion"));
        String value=p.get("contact");
        String[] parts=value==null?new String[0]:value.split(":",-1);
        if(customerVersion==null) throw new DomainException(400,"Versions required");
        if(parts.length!=2) throw new DomainException(400,"Invalid contact","contact");
        Long contact=number(parts[0],"contact"),version=number(parts[1],"contact");
        if(contact==null || version==null) throw new DomainException(400,"Invalid contact","contact");
        String active=p.get("active");
        if(!"true".equals(active) && !"false".equals(active)) throw new DomainException(400,"Invalid active state","active");
        contacts.link(workspaceId,entityId,id,customerVersion,contact,version,linkVersion,p.get("role"),Boolean.parseBoolean(active));
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    private static void allowed(Map<String,String> params,Set<String> allowed) {
        if(params.keySet().stream().anyMatch(key -> !key.equals("_form") && !allowed.contains(key))) throw new DomainException(400,"Unexpected field");
    }
    private static Long number(String value,String field) {
        try { return number(value); } catch(DomainException ex) { throw new DomainException(400,"Invalid number",field); }
    }
    private static Long number(String value) {
        if(value==null || value.isBlank()) return null;
        try { return Long.valueOf(value); } catch(NumberFormatException ex) { throw new DomainException(400,"Invalid number"); }
    }
}
