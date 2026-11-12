package dev.labtrial.salestools.web;

import dev.labtrial.salestools.identity.AccessControl;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.HandlerMapping;

@ControllerAdvice
public final class PageContext {
    @ModelAttribute
    void context(Model model, Authentication authentication, HttpServletRequest request) {
        model.addAttribute("currentLogin", authentication == null ? "" : authentication.getName());
        model.addAttribute("feedback", FormFeedback.empty());
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> path) {
            String workspace = String.valueOf(path.get("workspaceId"));
            String entity = String.valueOf(path.get("entityId"));
            if (workspace.matches("[1-9][0-9]*")) {
                model.addAttribute("workspaceId", workspace);
                model.addAttribute("workspaceBase", "/w/" + workspace);
                if (entity.matches("[1-9][0-9]*")) {
                    model.addAttribute("entityId", entity);
                    model.addAttribute("contextBase", "/w/" + workspace + "/e/" + entity);
                }
            }
        }
    }
}
