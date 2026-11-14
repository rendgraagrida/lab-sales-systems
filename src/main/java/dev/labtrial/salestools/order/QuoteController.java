package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import dev.labtrial.salestools.service.ServiceObligationService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/sales/quotes")
public class QuoteController implements FormController {
    private final QuoteService quotes;
    private final HandoverService handover;
    public QuoteController(QuoteService quotes,HandoverService handover) { this.quotes=quotes; this.handover=handover; }
    private static String base(long w,long e) { return "/w/"+w+"/e/"+e+"/sales/quotes"; }
    @Override public String redisplayForm(HttpServletRequest request,Model model) {
        long w=FormController.path(request,"workspaceId"),e=FormController.path(request,"entityId");
        if(request.getServletPath().endsWith("/save")) return form(w,e,FormController.parameterId(request,"id"),model);
        return detail(w,e,FormController.path(request,"id"),model);
    }
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("rows",quotes.list(workspaceId,entityId,q)); model.addAttribute("q",q); return "quote-list";
    }
    @GetMapping("/new") public String create(@PathVariable long workspaceId,@PathVariable long entityId,Model model) { return form(workspaceId,entityId,null,model); }
    @GetMapping("/{id:[0-9]+}/edit") public String edit(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) { return form(workspaceId,entityId,id,model); }
    private String form(long w,long e,Long id,Model model) {
        Map<String,Object> quote=id==null?null:quotes.quote(w,e,id); if(quote!=null && !"DRAFT".equals(quote.get("status"))) throw new DomainException(409,"Only drafts can be edited");
        var lines=id==null?List.<Map<String,Object>>of():quotes.lines(w,e,id);
        model.addAttribute("base",base(w,e)); model.addAttribute("quote",quote); model.addAttribute("lines",lines);
        model.addAttribute("addresses",quotes.addresses(w,e));
        Object serviceVersion=lines.stream().map(line->line.get("service_config_version")).filter(java.util.Objects::nonNull).findFirst().orElse(quotes.serviceConfigVersion(w,e));
        model.addAttribute("currentServiceConfigVersion",quotes.serviceConfigVersion(w,e));
        model.addAttribute("serviceConfigVersion",serviceVersion); model.addAttribute("serviceProfiles",quotes.serviceProfiles(w,e)); model.addAttribute("serviceMatrix",quotes.serviceMatrix(w,e)); model.addAttribute("assets",quotes.assets(w,e)); model.addAttribute("renewals",quotes.renewals(w,e)); model.addAttribute("targets",id==null?List.of():quotes.targets(w,e,id));
        model.addAttribute("customers",quotes.customers(w,e)); model.addAttribute("products",quotes.products(w,e)); model.addAttribute("prices",quotes.prices(w,e)); model.addAttribute("taxes",quotes.taxes(w,e)); model.addAttribute("opportunities",quotes.opportunities(w,e)); model.addAttribute("configVersion",quotes.configVersion(w,e)); return "quote-form";
    }
    @GetMapping("/{id:[0-9]+}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("quote",quotes.quote(workspaceId,entityId,id)); model.addAttribute("lines",quotes.lines(workspaceId,entityId,id)); model.addAttribute("targets",quotes.targets(workspaceId,entityId,id)); model.addAttribute("addresses",quotes.addressSnapshots(workspaceId,entityId,id)); return "quote-detail";
    }
    @PostMapping("/save") public String save(@PathVariable long workspaceId,@PathVariable long entityId,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","id","version","origin","customerRef","opportunityId","businessDate","validUntil","configVersion","serviceConfigVersion","billingAddress","shippingAddress","lineKey","productRef","bookRef","quantity","overridePrice","discountPct","discountAmount","taxRef","targetLineKey","targetUnit","targetProfile","targetMatrix","targetAsset","targetDeviceKey","targetDeviceUnit","targetBasis","targetStart","targetEnd","targetRenewal"));
        Long id=optionalLong(single(r,"id")),version=optionalLong(single(r,"version")); long[] customer=ref(single(r,"customerRef"),2,"customerRef");
        String[][] a=arrays(r,"lineKey","productRef","bookRef","quantity","overridePrice","discountPct","discountAmount","taxRef");
        String[][] targets=optionalArrays(r,"targetLineKey","targetUnit","targetProfile","targetMatrix","targetAsset","targetDeviceKey","targetDeviceUnit","targetBasis","targetStart","targetEnd","targetRenewal");
        Map<String,List<ServiceObligationService.TargetInput>> byLine=new java.util.HashMap<>();
        for(int i=0;i<targets[0].length;i++) {
            boolean empty=true; for(String[] row:targets) if(!row[i].isBlank()) empty=false; if(empty) continue;
            String lineKey=targets[0][i].trim(); if(lineKey.isEmpty()) throw new DomainException(400,"Target requires a service line key","targetLineKey["+i+"]");
            long unit=requiredLong(targets[1][i]); if(unit>100) throw new DomainException(400,"Target unit must be at most 100","targetUnit["+i+"]");
            long profile=requiredLong(targets[2][i]); long[] matrix=ref(targets[3][i],2,"targetMatrix["+i+"]");
            long[] asset=targets[4][i].isBlank()?null:ref(targets[4][i],2,"targetAsset["+i+"]");
            Long deviceUnit=optionalLong(targets[6][i]); if(deviceUnit!=null && deviceUnit>Integer.MAX_VALUE) throw new DomainException(400,"Invalid device unit","targetDeviceUnit["+i+"]");
            long[] renewal=targets[10][i].isBlank()?null:ref(targets[10][i],2,"targetRenewal["+i+"]");
            byLine.computeIfAbsent(lineKey,k->new ArrayList<>()).add(new ServiceObligationService.TargetInput((int)unit,profile,matrix[0],matrix[1],asset==null?null:asset[0],asset==null?null:asset[1],targets[5][i].isBlank()?null:targets[5][i].trim(),deviceUnit==null?null:deviceUnit.intValue(),targets[7][i],optionalDate(targets[8][i],"targetStart["+i+"]"),optionalDate(targets[9][i],"targetEnd["+i+"]"),renewal==null?null:renewal[0],renewal==null?null:renewal[1]));
        }
        List<QuoteService.LineInput> lines=new ArrayList<>();
        for(int i=0;i<a[0].length;i++) {
            boolean empty=true; for(String[] row:a) if(!row[i].isBlank()) empty=false; if(empty) continue;
            long[] product=ref(a[1][i],4,"productRef["+i+"]"),book=ref(a[2][i],4,"bookRef["+i+"]"),tax=ref(a[7][i],2,"taxRef["+i+"]");
            if(book[3]!=product[0]) throw new DomainException(400,"Choose a price for the selected product","bookRef["+i+"]");
            lines.add(new QuoteService.LineInput(a[0][i],product[0],product[1],product[2],product[3],book[0],book[1],book[2],decimal(a[3][i],false,"quantity["+i+"]"),decimal(a[4][i],true,"overridePrice["+i+"]"),decimal(a[5][i],true,"discountPct["+i+"]"),decimal(a[6][i],true,"discountAmount["+i+"]"),tax[0],tax[1],byLine.getOrDefault(a[0][i].trim(),List.of())));
            byLine.remove(a[0][i].trim());
        }
        if(!byLine.isEmpty()) throw new DomainException(400,"A target refers to an unknown line key","targetLineKey");
        boolean hasTargets=lines.stream().anyMatch(line->line.serviceTargets()!=null && !line.serviceTargets().isEmpty());
        long result=quotes.save(workspaceId,entityId,id,version,new QuoteService.Draft(single(r,"origin"),customer[0],customer[1],optionalLong(single(r,"opportunityId")),date(single(r,"businessDate"),"businessDate"),date(single(r,"validUntil"),"validUntil"),requiredLong(single(r,"configVersion")),address(single(r,"billingAddress"),"billingAddress"),address(single(r,"shippingAddress"),"shippingAddress"),hasTargets?optionalLong(single(r,"serviceConfigVersion")):null,lines));
        return "redirect:"+base(workspaceId,entityId)+"/"+result;
    }
    @PostMapping("/{id}/{action:submit|approve|reject|revise|accept|withdraw|handover}") public String action(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@PathVariable String action,HttpServletRequest r) {
        allowed(r,Set.of("_csrf","_form","version","reason","businessDate","reference","intent","revisionId")); long version=requiredLong(single(r,"version"));
        switch(action) {
            case "submit" -> quotes.submit(workspaceId,entityId,id,version);
            case "approve" -> quotes.decide(workspaceId,entityId,id,version,true,single(r,"reason"));
            case "reject" -> quotes.decide(workspaceId,entityId,id,version,false,single(r,"reason"));
            case "revise" -> quotes.revise(workspaceId,entityId,id,version);
            case "accept" -> quotes.accept(workspaceId,entityId,id,version,date(single(r,"businessDate"),"businessDate"),single(r,"reference"));
            case "withdraw" -> quotes.withdraw(workspaceId,entityId,id,version,single(r,"reason"));
            case "handover" -> { var result=handover.execute(workspaceId,entityId,id,requiredLong(single(r,"revisionId")),version,single(r,"intent")); return "redirect:/w/"+workspaceId+"/e/"+entityId+"/sales/orders/"+result.orderId(); }
            default -> throw new DomainException(400,"Invalid action");
        }
        return "redirect:"+base(workspaceId,entityId)+"/"+id;
    }
    private static void allowed(HttpServletRequest r,Set<String> names) { if(!names.containsAll(r.getParameterMap().keySet())) throw new DomainException(400,"Unexpected field"); }
    private static String single(HttpServletRequest r,String name) { String[] a=r.getParameterValues(name); if(a==null) return null; if(a.length!=1) throw new DomainException(400,"Duplicate field",name); return a[0]; }
    private static long requiredLong(String x) { try { long n=Long.parseLong(x); if(n>0) return n; } catch(NumberFormatException | NullPointerException ignored) {} throw new DomainException(400,"Invalid reference"); }
    private static Long optionalLong(String x) { return x==null || x.isBlank()?null:requiredLong(x); }
    private static long[] ref(String x,int count,String field) { String[] parts=x==null?new String[0]:x.split(":",-1); if(parts.length!=count) throw new DomainException(400,"Invalid reference",field); long[] result=new long[count]; for(int i=0;i<count;i++) { try { result[i]=requiredLong(parts[i]); } catch(DomainException ex) { throw new DomainException(400,"Invalid reference",field); } } return result; }
    private static QuoteService.AddressRef address(String x,String field) { if(x==null || x.isBlank()) return null; long[] p=ref(x,2,field); return new QuoteService.AddressRef(p[0],p[1]); }
    private static String[][] arrays(HttpServletRequest r,String... names) { String[][] a=new String[names.length][]; for(int i=0;i<names.length;i++) { a[i]=r.getParameterValues(names[i]); if(a[i]==null || a[i].length>100 || (i>0 && a[i].length!=a[0].length)) throw new DomainException(400,"Invalid line fields"); } return a; }
    private static String[][] optionalArrays(HttpServletRequest r,String... names) { if(r.getParameterValues(names[0])!=null) return arrays(r,names); for(String name:names) if(r.getParameterValues(name)!=null) throw new DomainException(400,"Invalid target fields"); String[][] result=new String[names.length][]; java.util.Arrays.setAll(result,i->new String[0]); return result; }
    private static BigDecimal decimal(String s,boolean optional,String field) { if(optional && (s==null || s.isBlank())) return null; try { return new BigDecimal(s); } catch(NumberFormatException | NullPointerException ex) { throw new DomainException(400,"Invalid amount",field); } }
    private static LocalDate date(String s,String field) { try { return LocalDate.parse(s); } catch(DateTimeParseException | NullPointerException ex) { throw new DomainException(400,"Invalid date",field); } }
    private static LocalDate optionalDate(String s,String field) { return s==null || s.isBlank()?null:date(s,field); }
}
