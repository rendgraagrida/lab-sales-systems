package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/finance/sales-config")
public class SalesPostingController implements FormController {
    private final SalesPostingConfigService service;
    public SalesPostingController(SalesPostingConfigService service) { this.service=service; }

    @ModelAttribute
    public void validateParameters(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return;
        Set<String> fields=request.getRequestURI().endsWith("/service")
                ? Set.of("_csrf","_form","version","deferredAccount","implementationRevenueAccount","serviceRevenueAccount")
                : Set.of("_csrf","_form","version","arAccount","unbilledAccount","revenueAccount","cogsAccount");
        for (var entry:request.getParameterMap().entrySet())
            if (!fields.contains(entry.getKey()) || entry.getValue().length!=1)
                throw new DomainException(400,"Unexpected or duplicate form field");
    }

    @GetMapping
    public String form(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        var current=service.current(workspaceId,entityId);
        model.addAttribute("configuration",current.isEmpty()?null:current.get(0));
        var serviceCurrent=service.currentService(workspaceId,entityId);
        model.addAttribute("serviceConfiguration",serviceCurrent.isEmpty()?null:serviceCurrent.get(0));
        model.addAttribute("serviceAction",path(workspaceId,entityId)+"/service");
        model.addAttribute("accounts",service.choices(workspaceId,entityId));
        model.addAttribute("action",path(workspaceId,entityId));
        return "sales-posting-config";
    }

    @Override
    public String redisplayForm(HttpServletRequest request,Model model) {
        return form(FormController.path(request,"workspaceId"),FormController.path(request,"entityId"),model);
    }

    @PostMapping
    public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam long version,
            @RequestParam String arAccount,@RequestParam String unbilledAccount,
            @RequestParam String revenueAccount,@RequestParam String cogsAccount) {
        service.save(workspaceId,entityId,new SalesPostingConfigService.Draft(version,
            reference(arAccount,"arAccount"),reference(unbilledAccount,"unbilledAccount"),
            reference(revenueAccount,"revenueAccount"),reference(cogsAccount,"cogsAccount")));
        return "redirect:"+path(workspaceId,entityId);
    }

    @PostMapping("/service")
    public String saveService(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam long version,
            @RequestParam String deferredAccount,@RequestParam String implementationRevenueAccount,
            @RequestParam String serviceRevenueAccount) {
        service.saveService(workspaceId,entityId,new SalesPostingConfigService.ServiceDraft(version,
            reference(deferredAccount,"deferredAccount"),reference(implementationRevenueAccount,"implementationRevenueAccount"),
            reference(serviceRevenueAccount,"serviceRevenueAccount")));
        return "redirect:"+path(workspaceId,entityId);
    }

    private static SalesPostingConfigService.Reference reference(String value,String field) {
        try {
            String[] parts=value.split(":",-1);
            if(parts.length!=2) throw new NumberFormatException();
            long id=Long.parseLong(parts[0]),version=Long.parseLong(parts[1]);
            if(id<=0||version<=0) throw new NumberFormatException();
            return new SalesPostingConfigService.Reference(id,version);
        } catch(NumberFormatException ex) { throw new DomainException(400,"Select an account with its current version",field); }
    }

    private static String path(long w,long e) { return "/w/"+w+"/e/"+e+"/finance/sales-config"; }
}
