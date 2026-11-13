package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.Ledger;
import dev.labtrial.salestools.finance.PostingService;
import dev.labtrial.salestools.finance.SalesPostingConfigService;
import dev.labtrial.salestools.finance.SourceAmounts;
import dev.labtrial.salestools.identity.AccessControl;
import dev.labtrial.salestools.inventory.InventoryService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentService {
    public record ShipmentLineDraft(String lineKey,long orderLineId,long lineVersion,long warehouseId,long warehouseVersion,BigDecimal quantity) {}
    public record ShipmentDraft(String number,long orderId,long orderVersion,LocalDate date,List<ShipmentLineDraft> lines) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final InventoryService inventory;
    private final PostingService posting;
    private final SalesPostingConfigService config;
    private final AuditLog audit;
    public FulfillmentService(JdbcTemplate db, AccessControl access, InventoryService inventory, PostingService posting,
                              SalesPostingConfigService config, AuditLog audit) {
        this.db=db; this.access=access; this.inventory=inventory; this.posting=posting; this.config=config; this.audit=audit;
    }
    private static long id(Map<String,Object> r,String k) { return ((Number)r.get(k)).longValue(); }
    private static BigDecimal amount(Map<String,Object> r,String k) { return (BigDecimal)r.get(k); }
    private static LocalDate date(Map<String,Object> r,String k) { Object value=r.get(k); return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate)value; }
    private static String hash(Object... parts) {
        try { var d=MessageDigest.getInstance("SHA-256"); for(Object p:parts) { byte[] b=String.valueOf(p).getBytes(StandardCharsets.UTF_8); d.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array()); d.update(b); } return HexFormat.of().formatHex(d.digest()); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void key(String value) { if(value==null || value.isBlank() || value.length()>120 || !value.equals(value.trim()) || value.startsWith("ship:") || value.startsWith("cancel:")) throw new DomainException(400,"Invalid or reserved intent key"); }
    private static void quantity(BigDecimal q,int scale) { if(q==null || q.signum()<=0 || q.scale()>6 || q.scale() < -18 || (long)q.precision()-q.scale()>12 || q.stripTrailingZeros().scale()>scale) throw new DomainException(400,"Invalid quantity"); }
    private Map<String,Object> line(long w,long e,long order,long line,boolean lock) {
        var rows=db.queryForList("select * from sales_order_lines where workspace_id=? and entity_id=? and order_id=? and id=?"+(lock?" for update":""),w,e,order,line);
        if(rows.size()!=1) throw new DomainException(404,"Order line not found"); return rows.get(0);
    }
    private void checkProduct(long w,long e,Map<String,Object> line) {
        if(!Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from products p join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id
              join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=?
              where p.workspace_id=? and p.id=? and p.version=? and p.active and p.status='ACTIVE' and p.tracking=?
                and u.id=? and u.version=? and u.active and u.quantity_scale=?
                and s.version=? and s.active)
            """,Boolean.class,e,w,id(line,"product_id"),id(line,"product_version"),line.get("tracking"),
                id(line,"uom_id"),id(line,"uom_version"),line.get("quantity_scale"),id(line,"product_setting_version"))))
            throw new DomainException(409,"Product or unit reference changed");
    }
    private Map<String,Object> order(long w,long e,long order,boolean lock) {
        var rows=db.queryForList("select * from sales_orders where workspace_id=? and entity_id=? and id=? and sealed"+(lock?" for update":""),w,e,order);
        if(rows.size()!=1) throw new DomainException(404,"Order not found"); return rows.get(0);
    }
    private BigDecimal reserved(long w,long e,long line) {
        return db.queryForObject("select coalesce(sum(quantity-released_qty-consumed_qty),0) from inventory_reservations where workspace_id=? and entity_id=? and order_line_id=?",BigDecimal.class,w,e,line);
    }
    private void bump(long w,long e,long order,long line) {
        db.update("update sales_order_lines set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,line);
        db.update("update sales_orders set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,order);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> orderDetail(long w,long e,long order) {
        access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE");
        var rows=db.queryForList("select id,number,version,business_date,currency from sales_orders where workspace_id=? and entity_id=? and id=? and sealed",w,e,order);
        if(rows.size()!=1) throw new DomainException(404,"Order not found"); return rows.get(0);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> orderLines(long w,long e,long order) {
        access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE"); order(w,e,order,false);
        return db.queryForList("select l.id,l.line_key,l.product_id,l.tracking,l.quantity,l.fulfilled_qty,l.cancelled_qty,l.version,p.sku,coalesce((select sum(r.quantity-r.released_qty-r.consumed_qty) from inventory_reservations r where r.workspace_id=l.workspace_id and r.entity_id=l.entity_id and r.order_line_id=l.id),0) as reserved_qty from sales_order_lines l join products p on p.workspace_id=l.workspace_id and p.id=l.product_id where l.workspace_id=? and l.entity_id=? and l.order_id=? order by l.id",w,e,order);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> reservations(long w,long e,long order) {
        access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE"); order(w,e,order,false);
        return db.queryForList("select r.*,s.serial,l.lot from inventory_reservations r join sales_order_lines o on o.workspace_id=r.workspace_id and o.entity_id=r.entity_id and o.id=r.order_line_id left join instrument_serials s on s.workspace_id=r.workspace_id and s.entity_id=r.entity_id and s.id=r.serial_id left join inventory_lots l on l.workspace_id=r.workspace_id and l.entity_id=r.entity_id and l.id=r.lot_id where r.workspace_id=? and r.entity_id=? and o.order_id=? order by r.id",w,e,order);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> shipments(long w,long e) { access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE"); return db.queryForList("select id,order_id,number,shipment_date,status,version from shipments where workspace_id=? and entity_id=? order by id desc",w,e); }
    @Transactional(readOnly=true)
    public Map<String,Object> shipment(long w,long e,long id) {
        var scope=access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE");
        String journal=scope.has("FINANCE")?",journal_id":"";
        var rows=db.queryForList("select id,order_id,number,shipment_date,status,version"+journal+" from shipments where workspace_id=? and entity_id=? and id=?",w,e,id);
        if(rows.size()!=1) throw new DomainException(404,"Shipment not found"); return rows.get(0);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> shipmentLines(long w,long e,long id) {
        var scope=access.requireContext(w,e,false,"OPERATIONS","INSIDE_SALES","SALES_MANAGER","FINANCE");
        shipmentRow(w,e,id,false);
        String cost=scope.has("FINANCE")?",cost":"";
        return db.queryForList("select id,order_line_id,order_line_version,line_key,warehouse_id,warehouse_version,product_id,quantity,slice_start,slice_end,subtotal,discount,net,tax,gross"+cost+" from shipment_lines where workspace_id=? and entity_id=? and shipment_id=? and active order by id",w,e,id);
    }
    private Map<String,Object> shipmentRow(long w,long e,long id,boolean lock) { var rows=db.queryForList("select * from shipments where workspace_id=? and entity_id=? and id=?"+(lock?" for update":""),w,e,id); if(rows.size()!=1) throw new DomainException(404,"Shipment not found"); return rows.get(0); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> warehouses(long w,long e) { access.requireContext(w,e,false,"OPERATIONS"); return db.queryForList("select id,code,name,version from warehouses where workspace_id=? and entity_id=? and active order by code",w,e); }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> tracking(long w,long e,long order) { access.requireContext(w,e,false,"OPERATIONS"); order(w,e,order,false); return db.queryForList("select 'SERIAL' as kind,s.id,s.serial as label,s.product_id,s.warehouse_id from instrument_serials s where s.workspace_id=? and s.entity_id=? and s.status='IN_STOCK' union all select 'LOT',l.id,l.lot,l.product_id,b.warehouse_id from inventory_lots l join inventory_lot_balances b on b.workspace_id=l.workspace_id and b.entity_id=l.entity_id and b.lot_id=l.id where l.workspace_id=? and l.entity_id=? and b.quantity>b.reserved order by kind,label",w,e,w,e); }
    @Transactional
    public long reserve(long w,long e,long order,long orderVersion,long lineId,long lineVersion,long warehouse,long warehouseVersion,Long serial,Long lot,BigDecimal q,String intent) {
        var scope=access.requireContext(w,e,true,"OPERATIONS"); key(intent); String h=hash(order,orderVersion,lineId,lineVersion,warehouse,warehouseVersion,serial,lot,q==null?null:q.stripTrailingZeros());
        var prior=db.queryForList("select id,intent_hash from inventory_reservations where workspace_id=? and entity_id=? and intent_key=?",w,e,intent);
        if(!prior.isEmpty()) { if(!h.equals(prior.get(0).get("intent_hash"))) throw new DomainException(409,"Reservation intent conflicts"); return id(prior.get(0),"id"); }
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from inventory_reservation_events where workspace_id=? and entity_id=? and intent_key=?)",Boolean.class,w,e,intent))) throw new DomainException(409,"Reservation intent conflicts with an existing event");
        var o=order(w,e,order,true); var l=line(w,e,order,lineId,true); if(id(o,"version")!=orderVersion || id(l,"version")!=lineVersion) throw new DomainException(409,"Order changed");
        checkProduct(w,e,l);
        quantity(q,((Number)l.get("quantity_scale")).intValue());
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from warehouses where workspace_id=? and entity_id=? and id=? and version=? and active)",Boolean.class,w,e,warehouse,warehouseVersion))) throw new DomainException(409,"Warehouse changed");
        if(amount(l,"quantity").subtract(amount(l,"fulfilled_qty")).subtract(amount(l,"cancelled_qty")).subtract(reserved(w,e,lineId)).compareTo(q)<0) throw new DomainException(409,"Reservation exceeds unfulfilled quantity");
        long result=inventory.reserveOrderStock(scope,lineId,id(l,"product_id"),warehouse,(String)l.get("tracking"),serial,lot,q,intent,h); bump(w,e,order,lineId);
        audit.record(scope.actor(),w,e,"RESERVE","SALES_ORDER",Long.toString(order),orderVersion+1,"SUCCESS",intent); return result;
    }
    @Transactional
    public void release(long w,long e,long order,long orderVersion,long lineId,long lineVersion,long reservation,BigDecimal q,String intent) {
        var scope=access.requireContext(w,e,true,"OPERATIONS"); key(intent); String h=hash(order,orderVersion,lineId,lineVersion,reservation,q==null?null:q.stripTrailingZeros());
        var prior=db.queryForList("select intent_hash from inventory_reservation_events where workspace_id=? and entity_id=? and intent_key=?",w,e,intent);
        if(!prior.isEmpty()) { if(!h.equals(prior.get(0).get("intent_hash"))) throw new DomainException(409,"Release intent conflicts"); return; }
        var o=order(w,e,order,true); var l=line(w,e,order,lineId,true); if(id(o,"version")!=orderVersion || id(l,"version")!=lineVersion) throw new DomainException(409,"Order changed"); quantity(q,((Number)l.get("quantity_scale")).intValue());
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from inventory_reservations where workspace_id=? and entity_id=? and id=? and order_line_id=?)",Boolean.class,w,e,reservation,lineId))) throw new DomainException(404,"Reservation not found");
        inventory.applyOrderReservation(scope,reservation,q,false,null,0,intent,h); bump(w,e,order,lineId);
        audit.record(scope.actor(),w,e,"RELEASE","SALES_ORDER",Long.toString(order),orderVersion+1,"SUCCESS",intent);
    }
    @Transactional
    public void cancel(long w,long e,long order,long orderVersion,long lineId,long lineVersion,BigDecimal q,String reason,String intent) {
        var scope=access.requireContext(w,e,true,"OPERATIONS"); key(intent); String h=hash(order,orderVersion,lineId,lineVersion,q==null?null:q.stripTrailingZeros(),reason);
        var prior=db.queryForList("select intent_hash from sales_order_cancellations where workspace_id=? and entity_id=? and intent_key=?",w,e,intent);
        if(!prior.isEmpty()) { if(!h.equals(prior.get(0).get("intent_hash"))) throw new DomainException(409,"Cancellation intent conflicts"); return; }
        var o=order(w,e,order,true); var l=line(w,e,order,lineId,true); if(id(o,"version")!=orderVersion || id(l,"version")!=lineVersion) throw new DomainException(409,"Order changed"); quantity(q,((Number)l.get("quantity_scale")).intValue());
        if(reason==null || reason.isBlank() || reason.length()>500) throw new DomainException(400,"Cancellation reason required");
        BigDecimal end=amount(l,"quantity").subtract(amount(l,"cancelled_qty")),start=end.subtract(q), fulfilled=amount(l,"fulfilled_qty");
        if(start.compareTo(fulfilled)<0) throw new DomainException(409,"Cancellation exceeds unfulfilled quantity");
        BigDecimal release=reserved(w,e,lineId).subtract(start.subtract(fulfilled));
        if(release.signum()>0) {
            var rs=db.queryForList("select * from inventory_reservations where workspace_id=? and entity_id=? and order_line_id=? and quantity>released_qty+consumed_qty order by id desc for update",w,e,lineId);
            for(var r:rs) { if(release.signum()<=0) break; BigDecimal available=amount(r,"quantity").subtract(amount(r,"released_qty")).subtract(amount(r,"consumed_qty")); BigDecimal part=available.min(release); if(r.get("serial_id")!=null && part.compareTo(BigDecimal.ONE)!=0) throw new DomainException(409,"Cannot partially release a serial"); inventory.applyOrderReservation(scope,id(r,"id"),part,false,null,0,"cancel:"+intent+":"+id(r,"id"),h); release=release.subtract(part); }
            if(release.signum()>0) throw new DomainException(409,"Reservations do not reconcile");
        }
        db.update("insert into sales_order_cancellations(workspace_id,entity_id,order_line_id,slice_start,slice_end,reason,intent_key,intent_hash,created_by) values (?,?,?,?,?,?,?,?,?)",w,e,lineId,start,end,reason,intent,h,scope.actor().id());
        db.update("update sales_order_lines set cancelled_qty=cancelled_qty+?,version=version+1 where workspace_id=? and entity_id=? and id=?",q,w,e,lineId); db.update("update sales_orders set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,order);
        audit.record(scope.actor(),w,e,"CANCEL","SALES_ORDER",Long.toString(order),orderVersion+1,"SUCCESS",reason);
    }
    @Transactional
    public long saveShipment(long w,long e,Long shipmentId,Long version,ShipmentDraft d) {
        var scope=access.requireContext(w,e,true,"OPERATIONS"); if(d==null) throw new DomainException(400,"Shipment required"); key(d.number());
        if(d.lines()==null || d.lines().isEmpty() || d.lines().size()>100 || d.lines().stream().anyMatch(java.util.Objects::isNull)) throw new DomainException(400,"Shipment requires one to 100 lines");
        var identity=new ArrayList<String>();
        for(var entry:d.lines()) identity.add(hash(entry.lineKey(),entry.orderLineId(),entry.lineVersion(),entry.warehouseId(),entry.warehouseVersion(),entry.quantity()==null?null:entry.quantity().stripTrailingZeros()));
        String h=hash(d.number(),d.orderId(),d.orderVersion(),d.date(),identity);
        if(shipmentId==null) { var prior=db.queryForList("select id,create_hash from shipments where workspace_id=? and entity_id=? and number=?",w,e,d.number()); if(!prior.isEmpty()) { if(!h.equals(prior.get(0).get("create_hash"))) throw new DomainException(409,"Shipment number conflicts"); return id(prior.get(0),"id"); } }
        var o=order(w,e,d.orderId(),true); if(id(o,"version")!=d.orderVersion()) throw new DomainException(409,"Order changed");
        if(d.date()==null || d.date().isBefore(date(o,"business_date"))) throw new DomainException(400,"Shipment date predates order");
        var keys=new java.util.HashSet<String>();
        var totals=new java.util.HashMap<Long,BigDecimal>();
        var sources=new java.util.HashMap<Long,Map<String,Object>>();
        for(var entry:d.lines()) {
            key(entry.lineKey());
            if(!keys.add(entry.lineKey())) throw new DomainException(400,"Shipment line keys must be distinct");
            var l=sources.computeIfAbsent(entry.orderLineId(),ignored->line(w,e,d.orderId(),entry.orderLineId(),true));
            if(id(l,"version")!=entry.lineVersion()) throw new DomainException(409,"Order line changed");
            quantity(entry.quantity(),((Number)l.get("quantity_scale")).intValue());
            if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from warehouses where workspace_id=? and entity_id=? and id=? and version=? and active)",Boolean.class,w,e,entry.warehouseId(),entry.warehouseVersion()))) throw new DomainException(409,"Warehouse changed");
            BigDecimal total=totals.merge(entry.orderLineId(),entry.quantity(),BigDecimal::add);
            if(amount(l,"quantity").subtract(amount(l,"fulfilled_qty")).subtract(amount(l,"cancelled_qty")).compareTo(total)<0) throw new DomainException(409,"Shipment exceeds unfulfilled quantity");
        }
        long sid;
        if(shipmentId==null) { if(version!=null) throw new DomainException(400,"Unexpected version"); sid=db.queryForObject("insert into shipments(workspace_id,entity_id,order_id,number,shipment_date,source_hash,create_hash,created_by) values (?,?,?,?,?,?,?,?) returning id",Long.class,w,e,d.orderId(),d.number(),d.date(),h,h,scope.actor().id()); }
        else { var old=shipmentRow(w,e,shipmentId,true); if(!"DRAFT".equals(old.get("status")) || version==null || id(old,"version")!=version || id(old,"order_id")!=d.orderId() || !d.number().equals(old.get("number"))) throw new DomainException(409,"Shipment changed"); sid=shipmentId; db.update("update shipment_lines set active=false where workspace_id=? and entity_id=? and shipment_id=? and active",w,e,sid); db.update("update shipments set shipment_date=?,source_hash=?,version=version+1 where workspace_id=? and entity_id=? and id=?",d.date(),h,w,e,sid); }
        for(var entry:d.lines()) {
            var l=sources.get(entry.orderLineId());
            db.update("insert into shipment_lines(workspace_id,entity_id,shipment_id,order_line_id,order_line_version,line_key,warehouse_id,warehouse_version,product_id,quantity) values (?,?,?,?,?,?,?,?,?,?)",w,e,sid,entry.orderLineId(),entry.lineVersion(),entry.lineKey(),entry.warehouseId(),entry.warehouseVersion(),id(l,"product_id"),entry.quantity());
        }
        audit.record(scope.actor(),w,e,shipmentId==null?"CREATE":"EDIT","SHIPMENT",Long.toString(sid),shipmentId==null?1:version+1,"SUCCESS",d.number()); return sid;
    }
    @Transactional
    public void postShipment(long w,long e,long shipmentId,long version) {
        var scope=access.requireContext(w,e,true,"OPERATIONS"); var s=shipmentRow(w,e,shipmentId,true);
        if("POSTED".equals(s.get("status"))) { if(id(s,"version")!=version+1) throw new DomainException(409,"Shipment replay does not match"); return; }
        if(!"DRAFT".equals(s.get("status")) || id(s,"version")!=version) throw new DomainException(409,"Shipment changed");
        long orderId=id(s,"order_id"); var o=order(w,e,orderId,true); var drafts=db.queryForList("select * from shipment_lines where workspace_id=? and entity_id=? and shipment_id=? and active order by product_id,order_line_id,warehouse_id,id for update",w,e,shipmentId); if(drafts.isEmpty()) throw new DomainException(409,"Shipment has no lines");
        var versions=new java.util.HashMap<Long,Long>();
        for(var d:drafts) {
            long lineId=id(d,"order_line_id"); var source=line(w,e,orderId,lineId,true);
            if(id(source,"version")!=id(d,"order_line_version") || id(source,"product_id")!=id(d,"product_id")) throw new DomainException(409,"Order line changed; refresh shipment draft");
            Long previous=versions.putIfAbsent(lineId,id(d,"order_line_version"));
            if(previous!=null && previous.longValue()!=id(d,"order_line_version")) throw new DomainException(409,"Inconsistent order line version");
        }
        config.require(scope); var journal=new ArrayList<Ledger.Line>(); BigDecimal zero=BigDecimal.ZERO.setScale(2); LocalDate date=date(s,"shipment_date");
        for(var d:drafts) {
            long lineId=id(d,"order_line_id"), warehouse=id(d,"warehouse_id"); var l=line(w,e,orderId,lineId,true); BigDecimal q=amount(d,"quantity"), start=amount(l,"fulfilled_qty"),end=start.add(q);
            if(id(l,"product_id")!=id(d,"product_id")) throw new DomainException(409,"Order line changed; refresh shipment draft");
            checkProduct(w,e,l);
            if(end.compareTo(amount(l,"quantity").subtract(amount(l,"cancelled_qty")))>0) throw new DomainException(409,"Shipment exceeds remaining order quantity");
            if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from warehouses where workspace_id=? and entity_id=? and id=? and version=? and active)",Boolean.class,w,e,warehouse,id(d,"warehouse_version")))) throw new DomainException(409,"Warehouse changed");
            var snap=new SourceAmounts.Snapshot(amount(l,"quantity"),amount(l,"unit_price"),amount(l,"subtotal"),amount(l,"discount"),amount(l,"net"),amount(l,"tax"),amount(l,"gross")); var slice=SourceAmounts.slice(snap,start,end);
            var reservations=db.queryForList("select * from inventory_reservations where workspace_id=? and entity_id=? and order_line_id=? and warehouse_id=? and quantity>released_qty+consumed_qty order by id for update",w,e,lineId,warehouse);
            BigDecimal remaining=q;
            var parts=new ArrayList<InventoryService.ReservationPart>();
            for(var r:reservations) {
                if(remaining.signum()==0) break;
                BigDecimal available=amount(r,"quantity").subtract(amount(r,"released_qty")).subtract(amount(r,"consumed_qty")),part=available.min(remaining);
                if(r.get("serial_id")!=null && part.compareTo(BigDecimal.ONE)!=0) throw new DomainException(409,"Cannot partially ship a serial");
                String event="ship:"+shipmentId+":"+id(d,"id")+":"+id(r,"id");
                parts.add(new InventoryService.ReservationPart(id(r,"id"),part,event,hash(event,part.stripTrailingZeros())));
                remaining=remaining.subtract(part);
            }
            if(remaining.signum()!=0) throw new DomainException(409,"Insufficient order-owned reservation");
            BigDecimal cost=inventory.issueOrderStock(scope,id(l,"product_id"),warehouse,lineId,shipmentId,date,parts);
            for(var part:parts) db.update("insert into shipment_line_tracking(workspace_id,entity_id,shipment_line_id,reservation_id,quantity) values (?,?,?,?,?)",w,e,id(d,"id"),part.reservationId(),part.quantity());
            db.update("update shipment_lines set slice_start=?,slice_end=?,subtotal=?,discount=?,net=?,tax=?,gross=?,output_account_id=?,ar_account_id=?,unbilled_account_id=?,cost=?,inventory_account_id=?,cogs_account_id=?,revenue_account_id=? where workspace_id=? and entity_id=? and id=?",start,end,slice.subtotal(),slice.discount(),slice.net(),slice.tax(),slice.gross(),id(l,"output_account_id"),id(l,"ar_account_id"),id(l,"unbilled_account_id"),cost,id(l,"inventory_account_id"),id(l,"cogs_account_id"),id(l,"revenue_account_id"),w,e,id(d,"id"));
            db.update("update sales_order_lines set fulfilled_qty=fulfilled_qty+?,version=version+1 where workspace_id=? and entity_id=? and id=?",q,w,e,lineId);
            if(slice.net().signum()>0) { journal.add(new Ledger.Line(id(l,"unbilled_account_id"),slice.net(),zero)); journal.add(new Ledger.Line(id(l,"revenue_account_id"),zero,slice.net())); }
            if(cost.signum()>0) { journal.add(new Ledger.Line(id(l,"cogs_account_id"),cost,zero)); journal.add(new Ledger.Line(id(l,"inventory_account_id"),zero,cost)); }
        }
        var totalsByAccount=new java.util.TreeMap<Long,BigDecimal>();
        for(var item:journal) totalsByAccount.merge(item.accountId(),item.debit().subtract(item.credit()),BigDecimal::add);
        journal.clear();
        for(var entry:totalsByAccount.entrySet()) {
            BigDecimal value=entry.getValue();
            if(value.signum()>0) journal.add(new Ledger.Line(entry.getKey(),value,zero));
            else if(value.signum()<0) journal.add(new Ledger.Line(entry.getKey(),zero,value.negate()));
        }
        var result=posting.post(scope,"SHIPMENT",Long.toString(shipmentId),"shipment:"+shipmentId,date,(String)o.get("currency"),"Goods shipment "+s.get("number"),journal);
        db.update("update shipments set status='POSTED',posted_by=?,posted_at=now(),journal_id=?,version=version+1 where workspace_id=? and entity_id=? and id=?",scope.actor().id(),result.journalId(),w,e,shipmentId); db.update("update sales_orders set version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,orderId);
        audit.record(scope.actor(),w,e,"POST","SHIPMENT",Long.toString(shipmentId),version+1,"SUCCESS",(String)s.get("number"));
    }
}
