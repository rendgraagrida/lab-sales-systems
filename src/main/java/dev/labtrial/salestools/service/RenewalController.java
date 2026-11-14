package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/service/renewals")
public class RenewalController implements FormController {
    private final RenewalService service;
    public RenewalController(RenewalService service) { this.service = service; }
    private static String base(long w, long e) { return "/w/" + w + "/e/" + e + "/service/renewals"; }
    @GetMapping public String index(@PathVariable long workspaceId, @PathVariable long entityId, Model model) {
        model.addAttribute("base", base(workspaceId, entityId));
        model.addAttribute("clock", service.clock(workspaceId, entityId));
        model.addAttribute("owners", service.owners(workspaceId, entityId));
        model.addAttribute("due", service.due(workspaceId, entityId));
        model.addAttribute("renewals", service.list(workspaceId, entityId));
        return "service-renewals";
    }
    @GetMapping("/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("renewal",service.detail(workspaceId,entityId,id));
        model.addAttribute("contractBase","/w/"+workspaceId+"/e/"+entityId+"/service/contracts");
        return "service-renewal-detail";
    }
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        return index(FormController.path(request, "workspaceId"), FormController.path(request, "entityId"), model);
    }
    @PostMapping("/scan") public String scan(@PathVariable long workspaceId, @PathVariable long entityId, HttpServletRequest request) {
        if (!Set.of("_csrf", "_form", "version", "ownerId").containsAll(request.getParameterMap().keySet())) throw new DomainException(400, "Unexpected field");
        long version = number(single(request, "version"), "version");
        String[] owner = single(request, "ownerId").split(":", -1);
        if (owner.length != 2) throw new DomainException(400, "Invalid owner reference", "ownerId");
        service.scan(workspaceId, entityId, version, number(owner[0], "ownerId"), number(owner[1], "ownerId"));
        return "redirect:" + base(workspaceId, entityId);
    }
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) throw new DomainException(400, "Missing or duplicate field", name);
        return values[0];
    }
    private static long number(String value, String field) {
        try { long n = Long.parseLong(value); if (n <= 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException ex) { throw new DomainException(400, "Invalid reference", field); }
    }
}
