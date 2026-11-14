package dev.labtrial.salestools.web;

import dev.labtrial.salestools.core.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    private final PageContext context;
    public ApiErrors(PageContext context) { this.context = context; }

    @ExceptionHandler(DomainException.class)
    ModelAndView domain(DomainException error, HttpServletRequest request, HandlerMethod handler) {
        int status = error.status();
        if (status != 400 && status != 401 && status != 403 && status != 404 && status != 409) {
            return unexpected(error);
        }
        if (status == 400 || status == 409) return formFailure(status, request, handler, error.getMessage(), error.fields());
        return page(status, error.getMessage(), null);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class, BindException.class})
    ModelAndView invalid(Exception error, HttpServletRequest request, HandlerMethod handler) {
        List<String> fields = List.of();
        if (error instanceof MissingServletRequestParameterException missing) fields = List.of(missing.getParameterName());
        else if (error instanceof MethodArgumentTypeMismatchException mismatch) fields = List.of(mismatch.getName());
        else if (error instanceof BindException binding) fields = binding.getFieldErrors().stream().map(field -> field.getField()).distinct().toList();
        return formFailure(400, request, handler, "Review the required fields, dates, amounts and references. No invalid action was saved.", fields);
    }

    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class})
    ModelAndView conflict(Exception error, HttpServletRequest request, HandlerMethod handler) {
        return formFailure(409, request, handler, "The action conflicts with the persisted state or a data constraint. Review the submitted values and current references before submitting again.", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ModelAndView forbidden(AccessDeniedException error) {
        return page(403, "Your current role and entity membership do not allow this operation.", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ModelAndView missing(NoResourceFoundException error) {
        return page(404, "The requested resource is not available.", null);
    }

    @ExceptionHandler(Exception.class)
    ModelAndView unexpected(Exception error) {
        String reference = UUID.randomUUID().toString();
        LOG.error("Unexpected request failure reference={} type={}", reference, error.getClass().getSimpleName());
        return page(500, "Unexpected server failure. No success is claimed; contact the local operator with the reference.", reference);
    }

    private ModelAndView formFailure(int responseStatus, HttpServletRequest request, HandlerMethod handler, String message, List<String> fields) {
        if (!"POST".equals(request.getMethod()) || handler == null
                || !(handler.getBean() instanceof FormController controller)) {
            return page(responseStatus, message, null);
        }
        var model = new ExtendedModelMap();
        try {
            context.context(model, SecurityContextHolder.getContext().getAuthentication(), request);
            model.addAttribute("contactMode", false);
            model.addAttribute("supplierMode", false);
            model.addAttribute("productMode", false);
            model.addAttribute("priceMode", false);
            model.addAttribute("feedback", FormFeedback.rejected(request, message, fields));
            // The failed transaction has unwound. Rebuild only the authorized GET/read model.
            String view = controller.redisplayForm(request, model);
            if (view == null || view.startsWith("redirect:") || view.startsWith("forward:")) {
                throw new IllegalStateException("Form recovery must return a read-only view");
            }
            return new ModelAndView(view, model.asMap(), HttpStatusCode.valueOf(responseStatus));
        } catch (DomainException denied) {
            int status = denied.status();
            if (status != 400 && status != 401 && status != 403 && status != 404 && status != 409) return unexpected(denied);
            return page(status, denied.getMessage(), null);
        } catch (RuntimeException failure) {
            return unexpected(failure);
        }
    }

    private ModelAndView page(int status, String message, String reference) {
        var page = new ModelAndView("error");
        page.setStatus(HttpStatusCode.valueOf(status));
        page.addObject("status", status);
        page.addObject("safeMessage", message);
        if (reference != null) page.addObject("reference", reference);
        return page;
    }
}
