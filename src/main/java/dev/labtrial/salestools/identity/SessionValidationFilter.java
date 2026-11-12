package dev.labtrial.salestools.identity;

import dev.labtrial.salestools.core.DomainException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidationFilter extends OncePerRequestFilter {
    public static final String LOGIN_TIME = SessionValidationFilter.class.getName() + ".loginTime";
    private final AccessControl access;
    public SessionValidationFilter(AccessControl access) { this.access = access; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof IdentityPrincipal principal) {
            HttpSession session = request.getSession(false);
            Long started = session == null ? null : (Long) session.getAttribute(LOGIN_TIME);
            boolean invalid = started == null || System.currentTimeMillis() - started >= 8L * 60 * 60 * 1000 || System.currentTimeMillis() < started;
            if (!invalid) {
                try { access.checkedActor(principal, false); } catch (DomainException ex) { invalid = true; }
            }
            if (invalid) {
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
                if ("GET".equals(request.getMethod())) response.sendRedirect(request.getContextPath() + "/login?expired");
                else response.sendError(401, "Authentication required");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
