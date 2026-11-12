package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/configuration")
public class ConfigurationController implements FormController {
    private final ConfigurationService service;
    public ConfigurationController(ConfigurationService service) { this.service = service; }
    private static String base(long ws, long entity) { return "/w/" + ws + "/e/" + entity + "/configuration"; }
    @Override public String redisplayForm(HttpServletRequest request, Model model) {
        long ws=FormController.path(request,"workspaceId"), entity=FormController.path(request,"entityId");
        String path=request.getServletPath(), root=base(ws,entity);
        if(!path.equals(root+"/save") && !path.equals(root+"/deactivate")) throw new DomainException(400,"Unknown form");
        String kind=request.getParameter("kind");
        if(kind==null || kind.isBlank()) throw new DomainException(400,"Required field missing","kind");
        ConfigurationService.Kind type=ConfigurationService.Kind.parse(kind);
        Long id=path.equals(root+"/save") ? FormController.parameterId(request,"id") : null;
        return index(ws,entity,type.getKey(),"",id,model);
    }
    @GetMapping
    public String index(@PathVariable long workspaceId, @PathVariable long entityId,
                        @RequestParam(defaultValue="uom") String kind, @RequestParam(defaultValue="") String q,
                        @RequestParam(required=false) Long edit, Model model) {
        ConfigurationService.Kind type = ConfigurationService.Kind.parse(kind);
        model.addAttribute("kind", type);
        model.addAttribute("kinds", ConfigurationService.Kind.values());
        model.addAttribute("entries", service.list(workspaceId, entityId, type, q));
        model.addAttribute("entry", edit == null ? null : service.get(workspaceId, entityId, type, edit));
        model.addAttribute("q", q);
        model.addAttribute("base", base(workspaceId, entityId));
        return "configuration";
    }
    @PostMapping("/save")
    public String save(@PathVariable long workspaceId, @PathVariable long entityId,
                       @RequestParam Map<String,String> params) {
        rejectUnknown(params, Set.of("_csrf","kind","id","version","code","name","days"));
        ConfigurationService.Kind kind = ConfigurationService.Kind.parse(required(params, "kind"));
        Long id = number(params.get("id"));
        Long version = number(params.get("version"));
        Long days;
        try { days = number(params.get("days")); }
        catch (DomainException ex) { throw new DomainException(400, "Invalid days", "days"); }
        if (days != null && (days < Integer.MIN_VALUE || days > Integer.MAX_VALUE)) throw new DomainException(400, "Invalid days", "days");
        service.save(workspaceId, entityId, kind, id, version, params.get("code"), params.get("name"), days == null ? null : days.intValue());
        return "redirect:" + base(workspaceId, entityId) + "?kind=" + kind.getKey();
    }
    @PostMapping("/deactivate")
    public String deactivate(@PathVariable long workspaceId, @PathVariable long entityId, @RequestParam Map<String,String> params) {
        rejectUnknown(params, Set.of("_csrf","kind","id","version"));
        ConfigurationService.Kind kind = ConfigurationService.Kind.parse(required(params, "kind"));
        Long id = number(required(params, "id")), version = number(required(params, "version"));
        if (id == null || version == null) throw new DomainException(400, "ID and version are required");
        service.deactivate(workspaceId, entityId, kind, id, version);
        return "redirect:" + base(workspaceId, entityId) + "?kind=" + kind.getKey();
    }
    private static void rejectUnknown(Map<String,String> params, Set<String> allowed) {
        if (params.keySet().stream().anyMatch(key -> !key.equals("_form") && !allowed.contains(key))) throw new DomainException(400, "Unexpected field");
    }
    private static String required(Map<String,String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) throw new DomainException(400, "Required field missing");
        return value;
    }
    private static Long number(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException ex) { throw new DomainException(400, "Invalid number"); }
    }
}
