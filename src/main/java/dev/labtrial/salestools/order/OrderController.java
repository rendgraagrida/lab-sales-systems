package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}/sales/orders")
public class OrderController {
    private final JdbcTemplate db;
    private final AccessControl access;
    public OrderController(JdbcTemplate db, AccessControl access) { this.db=db; this.access=access; }
    private static String base(long w,long e) { return "/w/"+w+"/e/"+e+"/sales/orders"; }
    @GetMapping public String list(@PathVariable long workspaceId,@PathVariable long entityId,@RequestParam(defaultValue="") String q,Model model) {
        access.requireContext(workspaceId,entityId,false,"INSIDE_SALES","SALES_MANAGER","FINANCE","OPERATIONS");
        String search=q.trim(); if(search.length()>200) throw new DomainException(400,"Search is too long");
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("q",search);
        model.addAttribute("orders",db.queryForList("select id,number,currency,business_date,accepted_reference from sales_orders where workspace_id=? and entity_id=? and sealed and position(lower(?) in lower(number))>0 order by id desc",workspaceId,entityId,search)); return "sales-order-list";
    }
    @GetMapping("/{id:[0-9]+}") public String detail(@PathVariable long workspaceId,@PathVariable long entityId,@PathVariable long id,@RequestParam(required=false) LocalDate asOf,Model model) {
        var scope=access.requireContext(workspaceId,entityId,false,"INSIDE_SALES","SALES_MANAGER","FINANCE","OPERATIONS");
        var rows=db.queryForList("select o.*,q.number quote_number from sales_orders o join quotes q on q.workspace_id=o.workspace_id and q.entity_id=o.entity_id and q.id=o.quote_id where o.workspace_id=? and o.entity_id=? and o.id=? and o.sealed",workspaceId,entityId,id);
        if(rows.isEmpty()) throw new DomainException(404,"Sales order not found");
        LocalDate day=asOf==null?db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,workspaceId):asOf;
        var lines=db.queryForList("""
            select l.*,q.product_sku,q.product_name,
              coalesce((select sum(r.quantity-r.released_qty-r.consumed_qty) from inventory_reservations r
                where r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.order_line_id=l.id),0) reserved_qty,
              coalesce((select sum(il.slice_end-il.slice_start) from sales_invoice_lines il
                join shipment_lines sl on sl.workspace_id=il.workspace_id and sl.entity_id=il.entity_id and sl.id=il.shipment_line_id
                join sales_invoices i on i.workspace_id=il.workspace_id and i.entity_id=il.entity_id and i.id=il.invoice_id
                where sl.workspace_id=l.workspace_id and sl.entity_id=l.entity_id and sl.order_line_id=l.id and il.active
                  and i.status='POSTED' and i.invoice_date<=? and not exists
                  (select 1 from sales_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?)),0) billed_qty
            from sales_order_lines l join quote_lines q on q.workspace_id=l.workspace_id and q.entity_id=l.entity_id and q.id=l.quote_line_id
            where l.workspace_id=? and l.entity_id=? and l.order_id=? order by l.id
            """,day,day,workspaceId,entityId,id);
        boolean commercial=scope.has("INSIDE_SALES") || scope.has("SALES_MANAGER") || scope.has("FINANCE");
        var invoices=commercial ? db.queryForList("""
            select i.id,i.reference,i.invoice_date,i.currency,
              coalesce((select sum(l.gross) from sales_invoice_lines l where l.workspace_id=i.workspace_id and l.entity_id=i.entity_id and l.invoice_id=i.id and l.active),0) gross,
              coalesce((select sum(a.amount) from customer_receipt_allocations a
                join customer_receipts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.receipt_id
                where a.workspace_id=i.workspace_id and a.entity_id=i.entity_id and a.invoice_id=i.id and a.active and c.status='POSTED' and c.payment_date<=?
                  and not exists (select 1 from sales_reversals r where r.workspace_id=c.workspace_id and r.entity_id=c.entity_id and r.receipt_id=c.id and r.reversal_date<=?)),0) cash_applied
            from sales_invoices i where i.workspace_id=? and i.entity_id=? and i.status='POSTED' and i.invoice_date<=?
              and not exists (select 1 from sales_reversals r where r.workspace_id=i.workspace_id and r.entity_id=i.entity_id and r.invoice_id=i.id and r.reversal_date<=?)
              and exists (select 1 from sales_invoice_lines l
                join shipment_lines sl on sl.workspace_id=l.workspace_id and sl.entity_id=l.entity_id and sl.id=l.shipment_line_id
                join sales_order_lines ol on ol.workspace_id=sl.workspace_id and ol.entity_id=sl.entity_id and ol.id=sl.order_line_id
                where l.workspace_id=i.workspace_id and l.entity_id=i.entity_id and l.invoice_id=i.id and l.active and ol.order_id=?)
            order by i.invoice_date,i.id
            """,day,day,workspaceId,entityId,day,day,id) : java.util.List.<java.util.Map<String,Object>>of();
        model.addAttribute("invoices",invoices);
        model.addAttribute("commercial",commercial);
        model.addAttribute("base",base(workspaceId,entityId)); model.addAttribute("root","/w/"+workspaceId+"/e/"+entityId); model.addAttribute("order",rows.get(0)); model.addAttribute("lines",lines); model.addAttribute("asOf",day); return "sales-order-detail";
    }
}
