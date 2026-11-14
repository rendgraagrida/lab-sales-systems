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
@RequestMapping("/w/{workspaceId}/e/{entityId}/service/profiles")
public class ServiceConfigurationController implements FormController {
    private final ServiceConfigurationService service;
    public ServiceConfigurationController(ServiceConfigurationService service) { this.service = service; }
    private static String base(long w, long e) { return "/w/" + w + "/e/" + e + "/service/profiles"; }
    @GetMapping public String index(@PathVariable long workspaceId, @PathVariable long entityId, Model model) {
        model.addAttribute("base", base(workspaceId, entityId));
        model.addAttribute("coverageTypes", service.coverageTypes(workspaceId, entityId));
        model.addAttribute("products", service.products(workspaceId, entityId));
        model.addAttribute("profiles", service.profiles(workspaceId, entityId));
        return "service-profiles";
    }
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        return index(FormController.path(request, "workspaceId"), FormController.path(request, "entityId"), model);
    }
    @PostMapping("/coverage") public String coverage(@PathVariable long workspaceId, @PathVariable long entityId, HttpServletRequest request) {
        allowed(request, Set.of("_csrf", "_form", "code", "name"));
        service.createCoverageType(workspaceId, entityId, single(request, "code"), single(request, "name"));
        return "redirect:" + base(workspaceId, entityId);
    }
    @PostMapping("/profile") public String profile(@PathVariable long workspaceId, @PathVariable long entityId, HttpServletRequest request) {
        allowed(request, Set.of("_csrf", "_form", "productRef", "settingVersion", "version", "serviceProfile", "quantity", "enabled"));
        String[] ref = single(request, "productRef").split(":", -1);
        if (ref.length != 2) throw new DomainException(400, "Invalid product reference", "productRef");
        long id = number(ref[0], false, "productRef"), productVersion = number(ref[1], false, "productRef");
        long settingVersion = number(single(request, "settingVersion"), false, "settingVersion");
        long revision = number(single(request, "version"), true, "version");
        String type = single(request, "serviceProfile"), duration = single(request, "quantity"), enabled = single(request, "enabled");
        Long coverage = type.isBlank() ? null : number(type, false, "serviceProfile");
        Integer months = null;
        if (!duration.isBlank()) {
            long value = number(duration, false, "quantity");
            if (value > 1200) throw new DomainException(400, "Duration is too large", "quantity");
            months = (int) value;
        }
        if (!Set.of("true", "false").contains(enabled)) throw new DomainException(400, "Invalid eligibility", "enabled");
        service.createProfile(workspaceId, entityId, id, productVersion, settingVersion, revision, coverage, months, Boolean.parseBoolean(enabled));
        return "redirect:" + base(workspaceId, entityId);
    }
    private static void allowed(HttpServletRequest request, Set<String> fields) {
        if (!fields.containsAll(request.getParameterMap().keySet())) throw new DomainException(400, "Unexpected field");
    }
    private static String single(HttpServletRequest request, String field) {
        String[] values = request.getParameterValues(field);
        if (values == null || values.length != 1) throw new DomainException(400, "Missing or duplicate field", field);
        return values[0];
    }
    private static long number(String value, boolean zero, String field) {
        try { long n = Long.parseLong(value); if (n < (zero ? 0 : 1)) throw new NumberFormatException(); return n; }
        catch (NumberFormatException ex) { throw new DomainException(400, "Invalid number", field); }
    }
}
