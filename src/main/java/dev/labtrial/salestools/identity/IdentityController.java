package dev.labtrial.salestools.identity;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class IdentityController implements FormController {
    private final AccessControl access; private final IdentityService service;
    public IdentityController(AccessControl access,IdentityService service) { this.access=access; this.service=service; }
    @GetMapping("/login") public String login() { return "login"; }
    @GetMapping("/workspaces") public String workspaces(Model model) { model.addAttribute("workspaces",access.workspaces()); return "workspaces"; }
    @PostMapping("/workspaces") public String createWorkspace(@RequestParam Map<String,String> p) { fields(p,"source","code","name","businessDate"); long id=service.createWorkspace(number(p,"source"),p.get("code"),p.get("name"),date(p.get("businessDate"))); return "redirect:/w/"+id; }
    @GetMapping("/w/{workspaceId}") public String workspace(@PathVariable("workspaceId") long ws,Model model) { var scope=access.requireWorkspace(ws,false); model.addAttribute("workspaceId",ws); model.addAttribute("entities",access.entities(ws)); model.addAttribute("isAdmin",scope.has("SYSTEM_ADMIN")); return "workspace"; }
    @GetMapping("/w/{workspaceId}/admin") public String admin(@PathVariable("workspaceId") long ws,Model model) { model.addAttribute("workspaceId",ws); model.addAttribute("workspace",service.workspaceDetails(ws)); model.addAttribute("users",service.users(ws)); model.addAttribute("roles",service.roles(ws)); model.addAttribute("grants",service.entityGrants(ws)); model.addAttribute("entities",service.allEntities(ws)); return "admin"; }
    @PostMapping("/w/{workspaceId}/admin/{action}") public String action(@PathVariable("workspaceId") long ws,@PathVariable("action") String action,@RequestParam Map<String,String> p) {
        switch(action) {
            case "workspace-edit" -> { fields(p,"version","code","name","businessDate"); service.updateWorkspace(ws,number(p,"version"),p.get("code"),p.get("name"),date(p.get("businessDate"))); }
            case "create-user" -> { fields(p,"login","password"); service.createUser(ws,p.get("login"),p.get("password")); }
            case "invite" -> { fields(p,"login"); service.invite(ws,p.get("login")); }
            case "user-active" -> { fields(p,"user","version","active"); service.userState(ws,number(p,"user"),number(p,"version"),bool(p)); }
            case "password" -> { fields(p,"user","version","password"); service.resetPassword(ws,number(p,"user"),number(p,"version"),p.get("password")); }
            case "membership" -> { fields(p,"user","version","active"); service.membership(ws,number(p,"user"),number(p,"version"),bool(p)); }
            case "role" -> { fields(p,"user","role","version","active"); service.role(ws,number(p,"user"),p.get("role"),number(p,"version"),bool(p)); }
            case "entity-grant" -> { fields(p,"user","entity","version","active"); service.entityGrant(ws,number(p,"entity"),number(p,"user"),number(p,"version"),bool(p)); }
            case "entity-create" -> { fields(p,"code","name","currency","active"); service.entity(ws,null,0,p.get("code"),p.get("name"),p.get("currency"),bool(p)); }
            case "entity-edit" -> { fields(p,"entity","version","code","name","currency","active"); service.entity(ws,number(p,"entity"),number(p,"version"),p.get("code"),p.get("name"),p.get("currency"),bool(p)); }
            default -> throw new DomainException(404,"Action not found");
        }
        return "redirect:/w/"+ws+"/admin";
    }
    @Override
    public String redisplayForm(HttpServletRequest request, Model model) {
        if ("/workspaces".equals(request.getRequestURI())) return workspaces(model);
        long ws = FormController.path(request, "workspaceId");
        if (request.getRequestURI().startsWith("/w/" + ws + "/admin/")) return admin(ws, model);
        throw new DomainException(400, "Unknown form target");
    }
    private static void fields(Map<String,String> p,String... names) { var allowed=new java.util.HashSet<>(Set.of(names)); allowed.add("_csrf"); allowed.add("_form"); if(!allowed.containsAll(p.keySet())) throw new DomainException(400,"Unexpected field"); }
    private static long number(Map<String,String> p,String name) { try { long n=Long.parseLong(p.get(name)); if(n<0) throw new NumberFormatException(); return n; } catch(RuntimeException ex) { throw new DomainException(400,"Invalid "+name,name); } }
    private static boolean bool(Map<String,String> p) { String v=p.get("active"); if("true".equals(v)) return true; if("false".equals(v)) return false; throw new DomainException(400,"Invalid active state","active"); }
    private static LocalDate date(String value) { try { return LocalDate.parse(value); } catch(DateTimeParseException | NullPointerException ex) { throw new DomainException(400,"Invalid business date","businessDate"); } }
}
