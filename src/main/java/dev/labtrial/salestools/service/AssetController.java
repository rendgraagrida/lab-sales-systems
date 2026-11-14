package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/service/assets")
public class AssetController implements FormController {
    private final AssetService service;
    public AssetController(AssetService service) { this.service=service; }
    private static String base(long w,long e) { return "/w/"+w+"/e/"+e+"/service/assets"; }
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("assets",service.list(workspaceId,entityId)); return "service-assets";
    }
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("customers",service.customers(workspaceId,entityId)); model.addAttribute("devices",service.devices(workspaceId,entityId)); model.addAttribute("operators",service.operators(workspaceId,entityId)); return "service-asset-form";
    }
    @GetMapping("/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("asset",service.detail(workspaceId,entityId,id)); model.addAttribute("history",service.history(workspaceId,entityId,id)); model.addAttribute("operatorHistory",service.operatorHistory(workspaceId,entityId,id)); model.addAttribute("obligations",service.obligations(workspaceId,entityId,id)); return "service-asset-detail";
    }
    @GetMapping("/{id}/operators") public String operators(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        var asset=service.detail(workspaceId,entityId,id);
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("asset",asset);
        model.addAttribute("operators",service.operators(workspaceId,entityId).stream()
                .filter(operator -> operator.get("customer_account_id").equals(asset.get("customer_account_id"))).toList());
        model.addAttribute("operatorHistory",service.operatorHistory(workspaceId,entityId,id)); return "service-asset-operators";
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId");
        if(request.getRequestURI().endsWith("/register")) return create(w,e,model);
        if(request.getRequestURI().endsWith("/operators")) return operators(w,e,FormController.path(request,"id"),model);
        return detail(w,e,FormController.path(request,"id"),model);
    }
    @PostMapping("/register") public String register(@PathVariable long workspaceId,@PathVariable long entityId,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","customerRef","productRef","settingVersion","serial","key","date","reason","contact","linkVersion","intent"));
        long[] customer=ref(single(r,"customerRef"),"customerRef"),product=ref(single(r,"productRef"),"productRef"),contact=ref(single(r,"contact"),"contact");
        long id=service.registerLegacy(workspaceId,entityId,customer[0],customer[1],product[0],product[1],number(single(r,"settingVersion"),"settingVersion"),single(r,"serial"),single(r,"key"),date(single(r,"date"),"date"),single(r,"reason"),contact[0],contact[1],number(single(r,"linkVersion"),"linkVersion"),single(r,"intent"));
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    @PostMapping("/{id}/complete") public String complete(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","version","targetId","date","postingDate","reference","reason","intent"));
        String target=single(r,"targetId"); Long obligation=target.isBlank()?null:number(target,"targetId");
        service.complete(workspaceId,entityId,id,number(single(r,"version"),"version"),obligation,date(single(r,"date"),"date"),date(single(r,"postingDate"),"postingDate"),single(r,"reference"),single(r,"reason"),single(r,"intent"));
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    @PostMapping("/{id}/operators") public String link(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","version","contact","linkVersion"));
        long[] contact=ref(single(r,"contact"),"contact");
        service.linkOperator(workspaceId,entityId,id,number(single(r,"version"),"version"),contact[0],contact[1],number(single(r,"linkVersion"),"linkVersion"));
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    private static void allowed(HttpServletRequest r,Set<String> allowed) { if(!allowed.containsAll(r.getParameterMap().keySet())) throw new DomainException(400,"Unexpected field"); }
    private static String single(HttpServletRequest r,String field) { String[] v=r.getParameterValues(field); if(v==null || v.length!=1) throw new DomainException(400,"Missing or duplicate field",field); return v[0]; }
    private static long number(String s,String field) { try { long n=Long.parseLong(s); if(n<=0) throw new NumberFormatException(); return n; } catch(NumberFormatException ex) { throw new DomainException(400,"Invalid reference",field); } }
    private static long[] ref(String s,String field) { String[] p=s.split(":",-1); if(p.length!=2) throw new DomainException(400,"Invalid reference",field); return new long[]{number(p[0],field),number(p[1],field)}; }
    private static LocalDate date(String value,String field) { try { return LocalDate.parse(value); } catch(java.time.format.DateTimeParseException ex) { throw new DomainException(400,"Invalid date",field); } }
}
