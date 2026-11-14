package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/service/contracts")
public class ContractController {
    private final JdbcTemplate db;
    private final AccessControl access;
    public ContractController(JdbcTemplate db,AccessControl access) { this.db=db; this.access=access; }
    private static String base(long w,long e) { return "/w/"+w+"/e/"+e+"/service/contracts"; }
    private void read(long w,long e) { access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE"); }
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,Model model) {
        read(workspaceId,entityId); model.addAttribute("base",base(workspaceId,entityId));
        model.addAttribute("contracts",db.queryForList("select c.id,c.record_key,a.serial,t.code coverage_code from service_contracts c join service_contract_assets ca on ca.workspace_id=c.workspace_id and ca.entity_id=c.entity_id and ca.contract_id=c.id join installed_instruments a on a.workspace_id=ca.workspace_id and a.entity_id=ca.entity_id and a.id=ca.asset_id join service_coverage_types t on t.workspace_id=c.workspace_id and t.entity_id=c.entity_id and t.id=c.coverage_type_id where c.workspace_id=? and c.entity_id=? order by c.id desc",workspaceId,entityId));
        return "service-contracts";
    }
    @GetMapping("/{id}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,Model model) {
        read(workspaceId,entityId);
        var rows=db.queryForList("select c.*,a.serial,a.record_key asset_key,t.code coverage_code from service_contracts c join service_contract_assets ca on ca.workspace_id=c.workspace_id and ca.entity_id=c.entity_id and ca.contract_id=c.id join installed_instruments a on a.workspace_id=ca.workspace_id and a.entity_id=ca.entity_id and a.id=ca.asset_id join service_coverage_types t on t.workspace_id=c.workspace_id and t.entity_id=c.entity_id and t.id=c.coverage_type_id where c.workspace_id=? and c.entity_id=? and c.id=?",workspaceId,entityId,id);
        if(rows.size()!=1) throw new DomainException(404,"Contract not found");
        model.addAttribute("contract",rows.get(0)); model.addAttribute("base",base(workspaceId,entityId));
        model.addAttribute("terms",db.queryForList("select t.*,p.sku,case when t.status='CANCELLED' then 'CANCELLED' when w.business_date<t.coverage_start then 'SCHEDULED' when w.business_date>=t.coverage_end then 'EXPIRED' else 'ACTIVE' end effective_status from service_contract_terms t join service_contract_assets ca on ca.workspace_id=t.workspace_id and ca.entity_id=t.entity_id and ca.id=t.contract_asset_id join products p on p.workspace_id=t.workspace_id and p.id=t.plan_product_id join scenario_workspaces w on w.id=t.workspace_id where t.workspace_id=? and t.entity_id=? and ca.contract_id=? order by t.coverage_start,t.id",workspaceId,entityId,id));
        return "service-contract-detail";
    }
}
