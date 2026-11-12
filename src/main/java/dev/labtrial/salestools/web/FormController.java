package dev.labtrial.salestools.web;

import dev.labtrial.salestools.core.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.ui.Model;
import org.springframework.web.servlet.HandlerMapping;

/** Rebuilds an authorized read model after an unsuccessful POST, never a write. */
public interface FormController {
    String redisplayForm(HttpServletRequest request, Model model);

    static long path(HttpServletRequest request, String name) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> values) {
            try {
                long value = Long.parseLong(String.valueOf(values.get(name)));
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // A malformed path is input failure, not a fallback workspace/entity.
            }
        }
        throw new DomainException(400, "Invalid form context");
    }

    static Long parameterId(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) return null;
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // The caller must not silently turn an invalid edit ID into a create.
        }
        throw new DomainException(400, "Invalid record reference", name);
    }
}
