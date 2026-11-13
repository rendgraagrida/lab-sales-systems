package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/sales/goods-configuration")
public class GoodsConfigurationController implements FormController {
    private final GoodsConfigurationService service; private final QuoteService quotes;
    public GoodsConfigurationController(GoodsConfigurationService service,QuoteService quotes) { this.service=service; this.quotes=quotes; }
    private static String base(long w,long e) { return "/w/"+w+"/e/"+e+"/sales/goods-configuration"; }
    @GetMapping public String index(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("products",service.products(workspaceId,entityId)); model.addAttribute("customers",quotes.customers(workspaceId,entityId)); model.addAttribute("compatibilities",service.compatibilities(workspaceId,entityId)); model.addAttribute("bundles",service.bundles(workspaceId,entityId)); model.addAttribute("addresses",service.addresses(workspaceId,entityId)); model.addAttribute("addressHistory",service.addressHistory(workspaceId,entityId)); return "quote-goods-configuration";
    }
    @Override public String redisplayForm(HttpServletRequest request,Model model) { return index(FormController.path(request,"workspaceId"),FormController.path(request,"entityId"),model); }
    @PostMapping("/{action:compatibility|bundle|address|revise-address}") public String add(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable String action,HttpServletRequest r) {
        var allowed="address".equals(action)?java.util.Set.of("_csrf","_form","customer","role","label","validFrom","validTo"):("revise-address".equals(action)?java.util.Set.of("_csrf","_form","address","label","validFrom","validTo"):("bundle".equals(action)?java.util.Set.of("_csrf","_form","first","second","quantity"):java.util.Set.of("_csrf","_form","first","second")));
        if(!allowed.containsAll(r.getParameterMap().keySet())) throw new DomainException(400,"Unexpected field");
        if("address".equals(action)) { long[] customer=ref(single(r,"customer"),"customer"); service.address(workspaceId,entityId,customer[0],customer[1],single(r,"role"),single(r,"label"),date(single(r,"validFrom"),false,"validFrom"),date(single(r,"validTo"),true,"validTo")); }
        else if("revise-address".equals(action)) { long[] address=ref(single(r,"address"),"address"); service.reviseAddress(workspaceId,entityId,address[0],address[1],single(r,"label"),date(single(r,"validFrom"),false,"validFrom"),date(single(r,"validTo"),true,"validTo")); }
        else { long[] first=ref(single(r,"first"),"first"), second=ref(single(r,"second"),"second"); if("compatibility".equals(action)) service.compatibility(workspaceId,entityId,first[0],first[1],second[0],second[1]); else { try { service.bundle(workspaceId,entityId,first[0],first[1],second[0],second[1],new BigDecimal(single(r,"quantity"))); } catch(NumberFormatException | NullPointerException ex) { throw new DomainException(400,"Invalid quantity","quantity"); } } }
        return "redirect:"+base(workspaceId,entityId);
    }
    private static java.time.LocalDate date(String value,boolean optional,String field) { if(optional && value.isBlank()) return null; try { return java.time.LocalDate.parse(value); } catch(java.time.format.DateTimeParseException ex) { throw new DomainException(400,"Invalid date",field); } }
    private static String single(HttpServletRequest r,String name) { String[] values=r.getParameterValues(name); if(values==null || values.length!=1) throw new DomainException(400,"Missing or duplicate field",name); return values[0]; }
    private static long[] ref(String value,String field) { try { String[] p=value.split(":",-1); if(p.length!=2) throw new NumberFormatException(); long id=Long.parseLong(p[0]),version=Long.parseLong(p[1]); if(id<=0 || version<=0) throw new NumberFormatException(); return new long[]{id,version}; } catch(NumberFormatException | NullPointerException ex) { throw new DomainException(400,"Invalid reference",field); } }
}
