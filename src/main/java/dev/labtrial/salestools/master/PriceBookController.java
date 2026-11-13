package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/masters/price-books")
public class PriceBookController implements FormController {
    private final PriceBookService service; private final ProductService products;
    public PriceBookController(PriceBookService service,ProductService products){this.service=service;this.products=products;}
    private static String base(long ws,long entity){return "/w/"+ws+"/e/"+entity+"/masters/price-books";}
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        long ws=FormController.path(request,"workspaceId"), entity=FormController.path(request,"entityId");
        String path=request.getServletPath(), root=base(ws,entity);
        model.addAttribute("contactMode",false); model.addAttribute("supplierMode",false);
        model.addAttribute("productMode",false); model.addAttribute("priceMode",true);
        if(path.equals(root+"/save")) {
            Long id=FormController.parameterId(request,"id");
            return id==null ? create(ws,entity,model) : edit(ws,entity,id,model);
        }
        long id=FormController.path(request,"id");
        if(path.equals(root+"/"+id+"/deactivate") || path.equals(root+"/"+id+"/line") || path.equals(root+"/"+id+"/approve"))
            return detail(ws,entity,id,model);
        throw new DomainException(400,"Unknown form");
    }
    @ModelAttribute("priceMode") public boolean priceMode(){return true;}
    @ModelAttribute("productMode") public boolean productMode(){return false;}
    @ModelAttribute("supplierMode") public boolean supplierMode(){return false;}
    @ModelAttribute("contactMode") public boolean contactMode(){return false;}
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="")String q,Model model){model.addAttribute("books",service.list(workspaceId,entityId,q));model.addAttribute("q",q);model.addAttribute("base",base(workspaceId,entityId));return "masters";}
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model){service.list(workspaceId,entityId,"");model.addAttribute("base",base(workspaceId,entityId));return "master-form";}
    @GetMapping("/{id:[0-9]+}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model){model.addAttribute("book",service.get(workspaceId,entityId,id));model.addAttribute("lines",service.lines(workspaceId,entityId,id));model.addAttribute("products",products.priceable(workspaceId,entityId));model.addAttribute("base",base(workspaceId,entityId));return "master-detail";}
    @GetMapping("/{id:[0-9]+}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model){model.addAttribute("book",service.get(workspaceId,entityId,id));model.addAttribute("base",base(workspaceId,entityId));return "master-form";}
    @PostMapping("/save") public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","id","version","code","name","currency","validFrom","validTo"));long id=service.save(workspaceId,entityId,number(p.get("id")),number(p.get("version")),p.get("code"),p.get("name"),p.get("currency"),date(p.get("validFrom"), "Valid from","validFrom"),date(p.get("validTo"), "Valid to","validTo"));return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/line") public String line(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","bookVersion","product","lineVersion","amount"));Long bv=number(p.get("bookVersion")),lv=number(p.get("lineVersion"));String raw=p.get("product");String[] parts=raw==null?new String[0]:raw.split(":",-1);if (bv == null) throw new DomainException(400,"Versions required"); else if (parts.length != 2) throw new DomainException(400,"Product required","product");Long product=number(parts[0], "product"),pv=number(parts[1], "product");if(product==null||pv==null)throw new DomainException(400,"Product required","product");String a=p.get("amount");BigDecimal amount;try{if(a==null||a.length()>40)throw new NumberFormatException();amount=new BigDecimal(a);}catch(NumberFormatException e){throw new DomainException(400,"Invalid amount","amount");}service.line(workspaceId,entityId,id,bv,product,pv,lv,amount);return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/deactivate") public String deactivate(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p){allowed(p,Set.of("_csrf","version"));Long v=number(p.get("version"));if(v==null)throw new DomainException(400,"Version required");service.deactivate(workspaceId,entityId,id,v);return "redirect:"+base(workspaceId,entityId)+"/"+id;}
    @PostMapping("/{id}/approve") public String approve(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,Set.of("_csrf","version"));
        Long version=number(p.get("version"));
        if(version==null) throw new DomainException(400,"Version required");
        service.approve(workspaceId,entityId,id,version);
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    private static void allowed(Map<String,String> p,Set<String> fields){if(p.keySet().stream().anyMatch(key -> !key.equals("_form") && !fields.contains(key)))throw new DomainException(400,"Unexpected field");}
    private static Long number(String v,String field){try{return number(v);}catch(DomainException e){throw new DomainException(400,"Invalid number",field);}}
    private static Long number(String v){if(v==null||v.isBlank())return null;try{return Long.valueOf(v);}catch(NumberFormatException e){throw new DomainException(400,"Invalid number");}}
    private static LocalDate date(String v,String label,String field){if(v==null||v.isBlank())return null;try{return LocalDate.parse(v);}catch(DateTimeParseException e){throw new DomainException(400,"Invalid "+label,field);}}
}
