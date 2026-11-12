package dev.labtrial.salestools.web;

import dev.labtrial.salestools.identity.AccessControl;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public final class HomeController {
    private final AccessControl access;
    public HomeController(AccessControl access) { this.access = access; }
    @GetMapping("/") String root() { return "redirect:/workspaces"; }
    @GetMapping("/w/{workspaceId}/e/{entityId}")
    String home(@PathVariable long workspaceId, @PathVariable long entityId, Model model) {
        var scope = access.requireContext(workspaceId, entityId, false);
        model.addAttribute("roles", scope.roles());
        model.addAttribute("scope", scope);
        return "home";
    }
}
