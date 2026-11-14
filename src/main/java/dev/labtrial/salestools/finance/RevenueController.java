package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/finance/service-revenue")
public class RevenueController implements FormController {
    private final ServiceRevenueService revenue;
    private final AccessControl access;
    private final JdbcTemplate db;
    public RevenueController(ServiceRevenueService revenue,AccessControl access,JdbcTemplate db) {
        this.revenue=revenue; this.access=access; this.db=db;
    }
    @ModelAttribute
    public void validateParameters(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return;
        Set<String> fields=Set.of("_csrf","_form","postingDate","intentKey");
        for (var entry:request.getParameterMap().entrySet())
            if (!fields.contains(entry.getKey()) || entry.getValue().length!=1)
                throw new DomainException(400,"Unexpected or duplicate form field");
    }
    @GetMapping
    public String index(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        access.requireContext(workspaceId,entityId,false,"FINANCE");
        model.addAttribute("action",path(workspaceId,entityId));
        model.addAttribute("businessDate",db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,workspaceId));
        model.addAttribute("periods",db.queryForList("""
            select t.record_key,l.id,l.period_no,l.period_start,l.period_end,l.amount,r.posting_date,
              case when o.id is not null then 'OPENING COVERED' when r.id is not null then 'RECOGNIZED' else 'PENDING' end state
            from revenue_schedule_lines l join revenue_schedules s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.id=l.schedule_id
            join service_contract_terms t on t.workspace_id=s.workspace_id and t.entity_id=s.entity_id and t.id=s.term_id
            left join service_opening_periods o on o.workspace_id=l.workspace_id and o.entity_id=l.entity_id and o.schedule_line_id=l.id
            left join revenue_recognitions r on r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.schedule_line_id=l.id
            where l.workspace_id=? and l.entity_id=? and s.sealed order by t.id,l.period_no
            """,workspaceId,entityId));
        return "service-revenue";
    }
    @Override
    public String redisplayForm(HttpServletRequest request,Model model) {
        return index(FormController.path(request,"workspaceId"),FormController.path(request,"entityId"),model);
    }
    @PostMapping
    public String recognize(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam LocalDate postingDate,@RequestParam String intentKey) {
        revenue.recognize(workspaceId,entityId,postingDate,intentKey);
        return "redirect:"+path(workspaceId,entityId);
    }
    private static String path(long w,long e) { return "/w/"+w+"/e/"+e+"/finance/service-revenue"; }
}
