package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.finance.SalesPostingConfigService;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.service.ServiceObligationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records a CRM handover request; booking is a separate explicit command. */
@Service
public class HandoverService {
    public record ExecuteResult(long orderId, boolean replayed) {}
    public record HandoverResult(long requestId, Long orderId, String status, List<String> blockedReasons, boolean replayed) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final SalesPostingConfigService postingConfig;
    private final ServiceObligationService obligations;

    public HandoverService(JdbcTemplate db, AccessControl access, AuditLog audit, SalesPostingConfigService postingConfig, ServiceObligationService obligations) {
        this.db = db;
        this.access = access;
        this.postingConfig = postingConfig;
        this.obligations = obligations;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public HandoverResult requestOpportunity(long workspaceId, long entityId, long opportunityId, String intent) {
        var scope = access.requireContext(workspaceId, entityId, true, "INSIDE_SALES", "SALES_MANAGER");
        if (intent == null || intent.isBlank() || intent.trim().length() > 200)
            throw new DomainException(400, "Handover intent is required", "intent");
        String key = intent.trim();
        var existing = db.query("select id,opportunity_id,status from sales_handover_requests where workspace_id=? and entity_id=? and intent=?",
                (r,n) -> new Object[]{r.getLong(1), r.getLong(2), r.getString(3)}, workspaceId, entityId, key);
        if (!existing.isEmpty()) {
            Object[] row = existing.get(0);
            if ((long) row[1] != opportunityId) throw new DomainException(409, "Handover intent already belongs to another opportunity");
            long requestId = (long) row[0];
            return new HandoverResult(requestId, existingOrder(workspaceId, entityId, opportunityId), (String) row[2], reasons(workspaceId, entityId, requestId), true);
        }
        var opportunities = db.query("select customer_account_id,stage from crm_opportunities where workspace_id=? and entity_id=? and id=? for update",
                (r,n) -> new Object[]{r.getLong(1),r.getString(2)}, workspaceId, entityId, opportunityId);
        if (opportunities.isEmpty()) throw new DomainException(404, "Opportunity not found");
        Object[] opportunity = opportunities.get(0);
        if (!"WON".equals(opportunity[1])) throw new DomainException(409, "Opportunity is not won");
        Long orderId = existingOrder(workspaceId, entityId, opportunityId);
        List<String> blockers = new ArrayList<>();
        if (orderId == null) {
            Integer credit = db.queryForObject("select count(*) from customer_entity_accounts where workspace_id=? and entity_id=? and id=? and active and credit_status='APPROVED'", Integer.class, workspaceId, entityId, opportunity[0]);
            if (credit == null || credit == 0) blockers.add("Finance credit approval is required");
            Integer accepted = db.queryForObject("select count(*) from quotes q join quote_revisions r on r.workspace_id=q.workspace_id and r.entity_id=q.entity_id and r.quote_id=q.id where q.workspace_id=? and q.entity_id=? and q.opportunity_id=? and q.customer_account_id=? and r.status='ACCEPTED'", Integer.class, workspaceId, entityId, opportunityId, opportunity[0]);
            if (accepted == null || accepted == 0) blockers.add("An accepted quote is required");
        }
        String status = orderId != null ? "EXISTING" : blockers.isEmpty() ? "READY" : "BLOCKED";
        long id = db.queryForObject("insert into sales_handover_requests(workspace_id,entity_id,opportunity_id,intent,source_hash,status,blocked_reasons,created_by) values (?,?,?,?,?,?,to_jsonb(string_to_array(?,chr(10))),?) returning id", Long.class,
                workspaceId, entityId, opportunityId, key, Long.toString(opportunityId), status, String.join("\n", blockers), scope.actor().id());
        audit.record(scope.actor(), workspaceId, entityId, "REQUEST", "sales_handover_requests", Long.toString(id), 1, "SUCCESS", status);
        return new HandoverResult(id, orderId, status, List.copyOf(blockers), false);
    }

    private List<String> reasons(long w, long e, long id) {
        return db.query("select value from sales_handover_requests r cross join lateral jsonb_array_elements_text(r.blocked_reasons) value where r.workspace_id=? and r.entity_id=? and r.id=? and value<>''", (r,n) -> r.getString(1), w,e,id);
    }
    /** Book only an accepted quote source; replay never reinterprets mutable masters. */
    @Transactional
    public ExecuteResult execute(long w, long e, long quoteId, long revisionId, long expectedQuoteVersion, String intent) {
        var scope = access.requireContext(w,e,true,"INSIDE_SALES","SALES_MANAGER");
        if (intent == null || intent.isBlank() || intent.trim().length()>200) throw new DomainException(400,"Handover intent is required","intent");
        String key=intent.trim();
        var prior=db.query("select quote_id,quote_revision_id,order_id from sales_handover_executions where workspace_id=? and entity_id=? and intent=?",
            (r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},w,e,key);
        if(!prior.isEmpty()) {
            long[] p=prior.get(0);
            if(p[0]!=quoteId || p[1]!=revisionId) throw new DomainException(409,"Handover intent has different input");
            return new ExecuteResult(p[2],true);
        }
        var roots=db.query("select version,customer_account_id from quotes where workspace_id=? and entity_id=? and id=? for update",
            (r,n)->new long[]{r.getLong(1),r.getLong(2)},w,e,quoteId);
        if(roots.isEmpty()) throw new DomainException(404,"Quote not found");
        var orders=db.query("select id from sales_orders where workspace_id=? and entity_id=? and quote_id=? and sealed", (r,n)->r.getLong(1),w,e,quoteId);
        if(!orders.isEmpty()) {
            db.update("insert into sales_handover_executions(workspace_id,entity_id,intent,quote_id,quote_revision_id,order_id,created_by) values (?,?,?,?,?,?,?)",w,e,key,quoteId,revisionId,orders.get(0),scope.actor().id());
            return new ExecuteResult(orders.get(0),true);
        }
        if(roots.get(0)[0]!=expectedQuoteVersion) throw new DomainException(409,"Quote has changed");
        var revisions=db.queryForList("select * from quote_revisions where workspace_id=? and entity_id=? and id=? and quote_id=? for update",w,e,revisionId,quoteId);
        if(revisions.isEmpty()) throw new DomainException(404,"Quote revision not found");
        var revision=revisions.get(0);
        if(!"ACCEPTED".equals(revision.get("status"))) throw new DomainException(409,"An accepted quote is required");
        Integer won=db.queryForObject("select count(*) from quotes q left join crm_opportunities o on o.workspace_id=q.workspace_id and o.entity_id=q.entity_id and o.id=q.opportunity_id where q.workspace_id=? and q.entity_id=? and q.id=? and (q.origin='DIRECT_ERP' or (q.origin='LOCAL_CRM' and o.stage='WON' and o.customer_account_id=q.customer_account_id))",Integer.class,w,e,quoteId);
        if(won==null || won!=1) throw new DomainException(409,"Local CRM opportunity must be won");
        var accounts=db.query("select version from customer_entity_accounts where workspace_id=? and entity_id=? and id=? and active and credit_status='APPROVED' for update",
            (r,n)->r.getLong(1),w,e,roots.get(0)[1]);
        if(accounts.isEmpty()) throw new DomainException(409,"Finance credit approval is required");
        var config=postingConfig.require(scope);
        if(config.version()!=((Number)revision.get("config_version")).longValue()) throw new DomainException(409,"Sales configuration changed");
        Integer lines=db.queryForObject("select count(*) from quote_lines where workspace_id=? and entity_id=? and revision_id=? and active",Integer.class,w,e,revisionId);
        if(lines==null || lines==0) throw new DomainException(409,"Quote has no lines");
        db.update("insert into document_sequences(workspace_id,entity_id,document_type,next_value) values (?,?,'SO',1) on conflict (workspace_id,entity_id,document_type) do nothing",w,e);
        long number=db.queryForObject("update document_sequences set next_value=next_value+1,version=version+1 where workspace_id=? and entity_id=? and document_type='SO' returning next_value-1",Long.class,w,e);
        long order=db.queryForObject("insert into sales_orders(workspace_id,entity_id,number,quote_id,quote_revision_id,customer_account_id,customer_account_version,currency,terms_days,business_date,accepted_reference,source_hash,config_version,created_by) select workspace_id,entity_id,?,quote_id,id,?,?,currency,terms_days,business_date,accepted_reference,payload_hash,config_version,? from quote_revisions where workspace_id=? and entity_id=? and id=? returning id",Long.class,
            "SO-"+number,roots.get(0)[1],revision.get("customer_account_version"),scope.actor().id(),w,e,revisionId);
        db.update("insert into sales_order_lines(workspace_id,entity_id,order_id,quote_line_id,line_key,product_id,product_version,product_setting_version,product_category,uom_id,uom_version,tracking,quantity_scale,quantity,unit_price,subtotal,discount,net,tax,gross,tax_version_id,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id,deferred_account_id,service_config_version,service_product_version_id) select workspace_id,entity_id,?,id,line_key,product_id,product_version,product_setting_version,product_category,uom_id,uom_version,tracking,quantity_scale,quantity,unit_price,subtotal,discount,net,tax,gross,tax_version_id,tax_rate_pct,output_account_id,ar_account_id,unbilled_account_id,revenue_account_id,cogs_account_id,inventory_account_id,deferred_account_id,service_config_version,service_product_version_id from quote_lines where workspace_id=? and entity_id=? and revision_id=? and active order by ordinal,id",order,w,e,revisionId);
        db.update("update sales_orders set sealed=true,version=version+1 where workspace_id=? and entity_id=? and id=? and not sealed",w,e,order);
        obligations.createFromAcceptedOrder(scope,order);
        db.update("insert into sales_handover_executions(workspace_id,entity_id,intent,quote_id,quote_revision_id,order_id,created_by) values (?,?,?,?,?,?,?)",w,e,key,quoteId,revisionId,order,scope.actor().id());
        audit.record(scope.actor(),w,e,"BOOK","sales_orders",Long.toString(order),2,"SUCCESS","accepted quote");
        return new ExecuteResult(order,false);
    }

    private Long existingOrder(long w, long e, long opportunity) {
        var rows = db.query("select o.id from sales_orders o join quotes q on q.workspace_id=o.workspace_id and q.entity_id=o.entity_id and q.id=o.quote_id where q.workspace_id=? and q.entity_id=? and q.opportunity_id=? and o.sealed order by o.id limit 1", (r,n)->r.getLong(1), w,e,opportunity);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
