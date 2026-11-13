package dev.labtrial.salestools.crm;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.web.FormController;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/crm")
public class CrmController implements FormController {
    private final InquiryService inquiries; private final RoutingService routing; private final ScoringService scoring;
    private final ActivityService activities; private final OpportunityService opportunities; private final ReactivationService reactivation;
    public CrmController(InquiryService inquiries,RoutingService routing,ScoringService scoring,ActivityService activities,OpportunityService opportunities,ReactivationService reactivation) {
        this.inquiries=inquiries;this.routing=routing;this.scoring=scoring;this.activities=activities;this.opportunities=opportunities;this.reactivation=reactivation;
    }
    private static String base(long ws,long entity) {return "/w/"+ws+"/e/"+entity+"/crm";}
    private static Long number(String s) {if(s==null || s.isBlank()) return null; try {long n=Long.parseLong(s); if(n<=0) throw new NumberFormatException(); return n;} catch(NumberFormatException ex) {throw new DomainException(400,"Invalid reference or version");}}
    private static long required(String s,String field) {Long n=number(s);if(n==null)throw new DomainException(400,"Reference is required",field);return n;}
    private static int integer(String s,String field) {try {return Integer.parseInt(s);}catch(Exception ex){throw new DomainException(400,"Invalid number",field);}}
    private static OffsetDateTime time(String s) {if(s==null || s.isBlank())return null;try{return java.time.LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);}catch(Exception ex){throw new DomainException(400,"Invalid UTC date and time");}}
    private static LocalDate date(String s) {try{return LocalDate.parse(s);}catch(Exception ex){throw new DomainException(400,"Invalid as-of date","asOf");}}
    private static boolean flag(Map<String,String> p,String name) {return "true".equals(p.get(name));}
    private static void allowed(Map<String,String> p,String... names) {var accepted=new java.util.HashSet<>(Set.of(names));accepted.add("_csrf");accepted.add("_form"); if(p.keySet().stream().anyMatch(k->!accepted.contains(k)))throw new DomainException(400,"Unexpected field");}
    @ModelAttribute
    public void rejectDuplicateFields(HttpServletRequest request) {
        if("POST".equals(request.getMethod()) && request.getParameterMap().values().stream().anyMatch(values->values.length!=1))
            throw new DomainException(400,"Duplicate form fields are not allowed");
    }
    @Override public String redisplayForm(HttpServletRequest req,Model model) {
        long ws=FormController.path(req,"workspaceId"), entity=FormController.path(req,"entityId");
        String path=req.getServletPath();
        if(path.equals(base(ws,entity)+"/inquiries/save")) {Long id=FormController.parameterId(req,"id"); if(id!=null) return inquiry(ws,entity,id,model);}
        else if(path.contains("/inquiries/")) return inquiry(ws,entity,FormController.path(req,"id"),model);
        return index(ws,entity,"all","",model);
    }
    @GetMapping({"","/inquiries","/activities","/opportunities","/reactivation"})
    public String index(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="all") String filter,@RequestParam(defaultValue="") String q,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("filter",filter); model.addAttribute("q",q);
        model.addAttribute("inquiries",inquiries.list(workspaceId,entityId,filter,q)); model.addAttribute("activities",activities.list(workspaceId,entityId));
        model.addAttribute("opportunities",opportunities.list(workspaceId,entityId)); model.addAttribute("routingRules",routing.rules(workspaceId,entityId));
        model.addAttribute("scoreRules",scoring.rules(workspaceId,entityId)); model.addAttribute("reactivationRules",reactivation.rules(workspaceId,entityId)); model.addAttribute("cases",reactivation.cases(workspaceId,entityId));
        model.addAttribute("reactivationHistory",reactivation.history(workspaceId,entityId));
        model.addAttribute("opportunityHistory",opportunities.history(workspaceId,entityId)); model.addAttribute("lifecycleHistory",opportunities.lifecycles(workspaceId,entityId));
        model.addAttribute("handoverRequests",opportunities.requests(workspaceId,entityId));
        model.addAttribute("commandIntent",java.util.UUID.randomUUID().toString());
        return "crm-index";
    }
    @GetMapping("/inquiries/{id}")
    public String inquiry(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("inquiry",inquiries.get(workspaceId,entityId,id));
        model.addAttribute("events",inquiries.history(workspaceId,entityId,id)); model.addAttribute("scoreRules",scoring.rules(workspaceId,entityId));
        model.addAttribute("firstResponse",inquiries.firstResponse(workspaceId,entityId,id));
        model.addAttribute("routingHistory",routing.history(workspaceId,entityId,id));
        model.addAttribute("scores",scoring.history(workspaceId,entityId,id)); return "crm-inquiry";
    }
    @GetMapping("/opportunities/{id}") public String opportunityDetail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("opportunity",opportunities.get(workspaceId,entityId,id));
        model.addAttribute("events",opportunities.history(workspaceId,entityId).stream().filter(e->e.opportunityId()==id).toList());
        model.addAttribute("requests",opportunities.requests(workspaceId,entityId).stream().filter(r->r.opportunityId()==id).toList());return "crm-detail";
    }
    @GetMapping("/activities/{id}") public String activityDetail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("activity",activities.list(workspaceId,entityId).stream().filter(a->a.id()==id).findFirst().orElseThrow(()->new DomainException(404,"Activity not found")));model.addAttribute("activityEvents",activities.history(workspaceId,entityId,id));return "crm-detail";
    }
    @GetMapping("/reactivation/{id}") public String caseDetail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        model.addAttribute("base",base(workspaceId,entityId));model.addAttribute("reactivationCase",reactivation.cases(workspaceId,entityId).stream().filter(c->c.id()==id).findFirst().orElseThrow(()->new DomainException(404,"Case not found")));
        model.addAttribute("evaluations",reactivation.history(workspaceId,entityId).stream().filter(e->e.caseId()!=null && e.caseId()==id).toList());model.addAttribute("caseEvents",reactivation.caseHistory(workspaceId,entityId,id));return "crm-detail";
    }
    @PostMapping("/inquiries/save")
    public String save(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"id","version","sourceKey","receivedAt","subject","need","customerId","customerVersion","contactId","contactVersion","linkVersion","territory","productLine","priority","followUpDue");
        long id=inquiries.save(workspaceId,entityId,number(p.get("id")),number(p.get("version")),new InquiryService.Draft(p.get("sourceKey"),time(p.get("receivedAt")),p.get("subject"),p.get("need"),number(p.get("customerId")),number(p.get("customerVersion")),number(p.get("contactId")),number(p.get("contactVersion")),number(p.get("linkVersion")),p.get("territory"),p.get("productLine"),p.get("priority"),time(p.get("followUpDue"))));
        return "redirect:"+base(workspaceId,entityId)+"/inquiries/"+id;
    }
    @PostMapping("/inquiries/{id}/action")
    public String inquiryAction(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,"action","version","assigneeId","reason","status","followUpDue","ruleId","asOf","territoryConfirmed","needConfirmed","timeframeConfirmed","budgetConfirmed"); long version=required(p.get("version"),"version");
        switch(p.getOrDefault("action","")) {
          case "claim" -> inquiries.assign(workspaceId,entityId,id,version,null,null,true);
          case "assign" -> inquiries.assign(workspaceId,entityId,id,version,number(p.get("assigneeId")),p.get("reason"),false);
          case "state" -> inquiries.transition(workspaceId,entityId,id,version,p.get("status"),time(p.get("followUpDue")),p.get("reason"));
          case "route" -> routing.route(workspaceId,entityId,id,version);
          case "score" -> scoring.evaluate(workspaceId,entityId,id,version,required(p.get("ruleId"),"ruleId"),date(p.get("asOf")),new ScoringService.Input(flag(p,"territoryConfirmed"),flag(p,"needConfirmed"),flag(p,"timeframeConfirmed"),flag(p,"budgetConfirmed")));
          default -> throw new DomainException(400,"Invalid inquiry action");
        }
        return "redirect:"+base(workspaceId,entityId)+"/inquiries/"+id;
    }
    @PostMapping("/rules/routing") public String routingRule(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"key","territory","productLine","priority","targetId","enabled","intent"); routing.createRule(workspaceId,entityId,p.get("key"),p.get("territory"),p.get("productLine"),integer(p.get("priority"),"priority"),required(p.get("targetId"),"targetId"),flag(p,"enabled"),p.get("intent"));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/rules/scoring") public String scoringRule(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"key","territoryPoints","needPoints","timeframePoints","budgetPoints","intent");scoring.createRule(workspaceId,entityId,p.get("key"),integer(p.get("territoryPoints"),"territoryPoints"),integer(p.get("needPoints"),"needPoints"),integer(p.get("timeframePoints"),"timeframePoints"),integer(p.get("budgetPoints"),"budgetPoints"),p.get("intent"));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/rules/reactivation") public String reactivationRule(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"key","days","intent");reactivation.createRule(workspaceId,entityId,p.get("key"),integer(p.get("days"),"days"),p.get("intent"));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/reactivation/scan") public String scan(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"ruleId","asOf");reactivation.scan(workspaceId,entityId,required(p.get("ruleId"),"ruleId"),date(p.get("asOf")));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/reactivation/{id}/action") public String caseAction(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,"action","version","subject","dueAt","reason"); long v=required(p.get("version"),"version");
        switch(p.getOrDefault("action","")) {case "followUp" -> reactivation.followUp(workspaceId,entityId,id,v,p.get("subject"),time(p.get("dueAt")));case "resolve" -> reactivation.transition(workspaceId,entityId,id,v,"RESOLVED",p.get("reason"));case "reopen" -> reactivation.transition(workspaceId,entityId,id,v,"OPEN",p.get("reason"));default -> throw new DomainException(400,"Invalid case action");}return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/lifecycle") public String lifecycle(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"customerId","customerVersion","lifecycle","reason");opportunities.lifecycle(workspaceId,entityId,required(p.get("customerId"),"customerId"),required(p.get("customerVersion"),"customerVersion"),p.get("lifecycle"),p.get("reason"));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/opportunities") public String qualify(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"customerAccountId","accountVersion","inquiryId","inquiryVersion","title","pipeline","ownerId","reason","intent");opportunities.qualify(workspaceId,entityId,new OpportunityService.Qualification(required(p.get("customerAccountId"),"customerAccountId"),required(p.get("accountVersion"),"accountVersion"),number(p.get("inquiryId")),number(p.get("inquiryVersion")),p.get("title"),p.get("pipeline"),required(p.get("ownerId"),"ownerId"),p.get("reason"),p.get("intent")));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/opportunities/{id}/stage") public String stage(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,"version","stage","reason","intent");opportunities.changeStage(workspaceId,entityId,id,required(p.get("version"),"version"),p.get("stage"),p.get("reason"),p.get("intent"));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/activities") public String activity(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam Map<String,String> p) {
        allowed(p,"inquiryId","inquiryVersion","opportunityId","opportunityVersion","customerId","customerVersion","kind","subject","assigneeId","dueAt","intent");activities.create(workspaceId,entityId,new ActivityService.Draft(number(p.get("inquiryId")),number(p.get("inquiryVersion")),number(p.get("opportunityId")),number(p.get("opportunityVersion")),number(p.get("customerId")),number(p.get("customerVersion")),p.get("kind"),p.get("subject"),required(p.get("assigneeId"),"assigneeId"),time(p.get("dueAt")),p.get("intent")));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/activities/{id}/edit") public String editActivity(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,"version","subject","assigneeId","dueAt");activities.edit(workspaceId,entityId,id,required(p.get("version"),"version"),p.get("subject"),required(p.get("assigneeId"),"assigneeId"),time(p.get("dueAt")));return "redirect:"+base(workspaceId,entityId);
    }
    @PostMapping("/activities/{id}/complete") public String complete(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam Map<String,String> p) {
        allowed(p,"version","outcome","response");activities.complete(workspaceId,entityId,id,required(p.get("version"),"version"),p.get("outcome"),flag(p,"response"));return "redirect:"+base(workspaceId,entityId);
    }
}
