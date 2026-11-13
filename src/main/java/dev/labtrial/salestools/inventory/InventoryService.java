package dev.labtrial.salestools.inventory;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.finance.Ledger;
import dev.labtrial.salestools.finance.PostingService;
import dev.labtrial.salestools.finance.SourceAmounts;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    public record TrackingInput(String serial, String lot, LocalDate expiresOn, BigDecimal quantity) {}
    public record ReceiptStockLine(long receiptLineId, long productId, long warehouseId, String tracking,
                                   BigDecimal quantity, BigDecimal net, List<TrackingInput> trackingRows) {}
    public record Balance(long productId, String sku, String productName, long warehouseId, String warehouseCode,
                          BigDecimal onHand, BigDecimal reserved, BigDecimal poolValue) {}
    public record Movement(long id, long productId, String sku, long warehouseId, String warehouseCode,
                           String sourceType, String sourceId, LocalDate postingDate, BigDecimal quantity,
                           BigDecimal value, String serial, String lot, LocalDate expiresOn, long poolSequence) {}
    private record Source(long id, long product, long warehouse, String tracking, BigDecimal start, BigDecimal end,
                          BigDecimal net, SourceAmounts.Snapshot snapshot) {}
    private record Track(long id, String serial, String lot, LocalDate expiry, BigDecimal quantity) {}
    public record ActionInput(String key, String kind, long productId, long productVersion, long uomId,
                              long uomVersion, long settingVersion, Long fromWarehouseId, Long fromWarehouseVersion,
                              Long toWarehouseId, Long toWarehouseVersion, BigDecimal quantity, BigDecimal value,
                              LocalDate date, String reason, List<TrackingInput> trackingRows) {}
    public record Action(long id, String key, String kind, String status, long productId, long productVersion,
                         long uomId, long uomVersion, long settingVersion, Long fromWarehouseId, Long fromWarehouseVersion,
                         Long toWarehouseId, Long toWarehouseVersion, String tracking, int quantityScale,
                         BigDecimal quantity, BigDecimal value, LocalDate date, String reason, long createdBy,
                         long version, String postingHash, Long journalId) {}
    public record ActionResult(long id, Long journalId, boolean replayed) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final PostingService posting;


    @Transactional(readOnly = true)
    public List<Balance> balances(long workspaceId, long entityId, String filter) {
        var scope = access.requireContext(workspaceId, entityId, false, "OPERATIONS", "FINANCE");
        String search = filter == null ? "" : filter.trim();
        if (search.length() > 200) throw new DomainException(400, "Search is too long");
        return db.query("""
            select b.product_id,p.sku,p.name,b.warehouse_id,w.code,b.on_hand,b.reserved,
                   case when ? then v.value else null end
              from inventory_balances b
              join products p on p.workspace_id=b.workspace_id and p.id=b.product_id
              join warehouses w on w.workspace_id=b.workspace_id and w.entity_id=b.entity_id and w.id=b.warehouse_id
              left join inventory_valuation_pools v on v.workspace_id=b.workspace_id and v.entity_id=b.entity_id and v.product_id=b.product_id
             where b.workspace_id=? and b.entity_id=? and
                   (position(lower(?) in lower(p.sku))>0 or position(lower(?) in lower(p.name))>0 or position(lower(?) in lower(w.code))>0)
             order by p.sku,w.code,b.id
            """, (r,n) -> new Balance(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),
                      r.getBigDecimal(6),r.getBigDecimal(7),r.getBigDecimal(8)),
                scope.has("FINANCE"),workspaceId,entityId,search,search,search);
    }

    @Transactional(readOnly = true)
    public List<Movement> movements(long workspaceId, long entityId, long productId) {
        var scope = access.requireContext(workspaceId, entityId, false, "OPERATIONS", "FINANCE");
        return db.query("""
            select m.id,m.product_id,p.sku,m.warehouse_id,w.code,m.source_type,m.source_id,m.posting_date,
                   m.quantity_delta,case when ? then m.value_delta else null end,s.serial,l.lot,l.expires_on,m.pool_sequence
              from stock_movements m
              join products p on p.workspace_id=m.workspace_id and p.id=m.product_id
              join warehouses w on w.workspace_id=m.workspace_id and w.entity_id=m.entity_id and w.id=m.warehouse_id
              left join instrument_serials s on s.workspace_id=m.workspace_id and s.entity_id=m.entity_id and s.id=m.serial_id
              left join inventory_lots l on l.workspace_id=m.workspace_id and l.entity_id=m.entity_id and l.id=m.lot_id
             where m.workspace_id=? and m.entity_id=? and m.product_id=?
             order by m.pool_sequence,m.id
            """, (r,n) -> new Movement(r.getLong(1),r.getLong(2),r.getString(3),r.getLong(4),r.getString(5),
                    r.getString(6),r.getString(7),r.getObject(8,LocalDate.class),r.getBigDecimal(9),r.getBigDecimal(10),
                    r.getString(11),r.getString(12),r.getObject(13,LocalDate.class),r.getLong(14)),
                scope.has("FINANCE"),workspaceId,entityId,productId);
    }
    public InventoryService(JdbcTemplate db, AccessControl access, AuditLog audit, PostingService posting) {
        this.db = db; this.access = access; this.audit = audit; this.posting = posting;
    }

    private static final String ACTION_COLUMNS = "id,action_key,kind,status,product_id,product_version,uom_id,uom_version,setting_version,from_warehouse_id,from_warehouse_version,to_warehouse_id,to_warehouse_version,tracking,quantity_scale,quantity,value,posting_date,reason,created_by,version,posting_hash,journal_id";
    private static Action actionRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Action(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getLong(6),
                r.getLong(7),r.getLong(8),r.getLong(9),r.getObject(10,Long.class),r.getObject(11,Long.class),
                r.getObject(12,Long.class),r.getObject(13,Long.class),r.getString(14),r.getInt(15),r.getBigDecimal(16),
                r.getBigDecimal(17),r.getObject(18,LocalDate.class),r.getString(19),r.getLong(20),r.getLong(21),
                r.getString(22),r.getObject(23,Long.class));
    }
    @Transactional(readOnly=true)
    public List<Action> actions(long w,long e) {
        return actions(w,e,"");
    }
    @Transactional(readOnly=true)
    public List<Action> actions(long w,long e,String filter) {
        var scope=access.requireContext(w,e,false,"OPERATIONS","FINANCE");
        String q=filter==null?"":filter.trim();
        if(q.length()>200) throw new DomainException(400,"Search is too long");
        var rows=db.query("select "+ACTION_COLUMNS+" from inventory_actions a where workspace_id=? and entity_id=? and (position(lower(?) in lower(action_key))>0 or position(lower(?) in lower(kind))>0 or position(lower(?) in lower(status))>0 or exists(select 1 from products p where p.workspace_id=a.workspace_id and p.id=a.product_id and position(lower(?) in lower(p.sku))>0)) order by id desc",InventoryService::actionRow,w,e,q,q,q,q);
        return scope.has("FINANCE")?rows:rows.stream().map(InventoryService::withoutFinancials).toList();
    }
    @Transactional(readOnly=true)
    public Action action(long w,long e,long id) {
        var scope=access.requireContext(w,e,false,"OPERATIONS","FINANCE");
        Action a=findAction(w,e,id,false);
        return scope.has("FINANCE")?a:withoutFinancials(a);
    }
    private static Action withoutFinancials(Action a) {
        return new Action(a.id(),a.key(),a.kind(),a.status(),a.productId(),a.productVersion(),a.uomId(),a.uomVersion(),a.settingVersion(),
                a.fromWarehouseId(),a.fromWarehouseVersion(),a.toWarehouseId(),a.toWarehouseVersion(),a.tracking(),a.quantityScale(),
                a.quantity(),null,a.date(),a.reason(),a.createdBy(),a.version(),null,null);
    }
    private Action findAction(long w,long e,long id,boolean lock) {
        var rows=db.query("select "+ACTION_COLUMNS+" from inventory_actions where workspace_id=? and entity_id=? and id=?"+(lock?" for update":""),InventoryService::actionRow,w,e,id);
        if(rows.size()!=1) throw new DomainException(404,"Inventory action not found");
        return rows.get(0);
    }
    @Transactional(readOnly=true)
    public List<TrackingInput> actionTracking(long w,long e,long id) {
        access.requireContext(w,e,false,"OPERATIONS","FINANCE");
        findAction(w,e,id,false);
        return tracking(w,e,id).stream().map(t->new TrackingInput(t.serial(),t.lot(),t.expiry(),t.quantity())).toList();
    }
    private List<Track> tracking(long w,long e,long id) {
        return db.query("select id,serial,lot,expires_on,quantity from inventory_action_tracking where workspace_id=? and entity_id=? and action_id=? and active order by id",
                (r,n)->new Track(r.getLong(1),r.getString(2),r.getString(3),r.getObject(4,LocalDate.class),r.getBigDecimal(5)),w,e,id);
    }
    private record ProductRef(String tracking,int scale) {}
    private ProductRef checkReferences(long w,long e,ActionInput in) {
        var products=db.query("""
            select p.tracking,u.quantity_scale from products p
            join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id
            join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=?
            where p.workspace_id=? and p.id=? and p.version=? and p.uom_id=? and u.version=? and s.version=?
              and p.active and u.active and s.active and p.category not in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE')
            for update of p,u,s
            """,(r,n)->new ProductRef(r.getString(1),r.getInt(2)),e,w,in.productId(),in.productVersion(),in.uomId(),in.uomVersion(),in.settingVersion());
        if(products.size()!=1) throw new DomainException(409,"Product, unit or entity assignment changed");
        checkWarehouse(w,e,in.fromWarehouseId(),in.fromWarehouseVersion());
        checkWarehouse(w,e,in.toWarehouseId(),in.toWarehouseVersion());
        return products.get(0);
    }
    private void checkWarehouse(long w,long e,Long id,Long version) {
        if ((id==null)!=(version==null)) throw new DomainException(400,"Warehouse version is required");
        if(id!=null && !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from warehouses where workspace_id=? and entity_id=? and id=? and version=? and active)",Boolean.class,w,e,id,version)))
            throw new DomainException(409,"Warehouse changed or is unavailable");
    }
    private void validateInput(ActionInput in, ProductRef product) {
        if(in.key()==null || in.key().isBlank() || in.key().length()>120 || !in.key().equals(in.key().trim()) ||
           in.kind()==null || !List.of("TRANSFER","NEGATIVE_ADJUSTMENT","POSITIVE_ADJUSTMENT").contains(in.kind()) || in.date()==null ||
           in.reason()==null || in.reason().isBlank() || in.reason().length()>500) throw new DomainException(400,"Invalid inventory action");
        if(in.quantity()==null || in.quantity().signum()<=0 || in.quantity().scale()>6 || in.quantity().scale() < -18 ||
           (long)in.quantity().precision()-in.quantity().scale()>12 || in.quantity().stripTrailingZeros().scale()>product.scale())
            throw new DomainException(400,"Invalid quantity for unit","quantity");
        if(("TRANSFER".equals(in.kind()) && (in.fromWarehouseId()==null || in.toWarehouseId()==null || in.fromWarehouseId().equals(in.toWarehouseId())))
           || ("NEGATIVE_ADJUSTMENT".equals(in.kind()) && (in.fromWarehouseId()==null || in.toWarehouseId()!=null))
           || ("POSITIVE_ADJUSTMENT".equals(in.kind()) && (in.fromWarehouseId()!=null || in.toWarehouseId()==null)))
            throw new DomainException(400,"Invalid warehouse selection");
        if("POSITIVE_ADJUSTMENT".equals(in.kind())) {
            if(in.value()==null || in.value().signum()<0 || in.value().scale()>2 || in.value().scale() < -18 || (long)in.value().precision()-in.value().scale()>16)
                throw new DomainException(400,"Nonnegative adjustment value is required","value");
        } else if(in.value()!=null) throw new DomainException(400,"Value is calculated from the pool");
        if(in.trackingRows()==null || in.trackingRows().size()>100) throw new DomainException(400,"Invalid tracking rows");
        var rows=new ArrayList<Track>(); long id=0;
        for(var row:in.trackingRows()) {
            if(row==null) throw new DomainException(400,"Invalid tracking row");
            if(row.quantity()!=null && row.quantity().stripTrailingZeros().scale()>product.scale()) throw new DomainException(400,"Tracking quantity exceeds unit precision");
            rows.add(new Track(++id,row.serial(),row.lot(),row.expiresOn(),row.quantity()));
        }
        validateTracking(new Source(0,in.productId(),0,product.tracking(),BigDecimal.ZERO,in.quantity(),BigDecimal.ZERO,null),rows,in.date());
    }
    @Transactional
    public long saveAction(long w,long e,Long id,Long version,ActionInput in) {
        var scope=access.requireContext(w,e,true,"OPERATIONS","FINANCE");
        if(in==null) throw new DomainException(400,"Action is required");
        if(!"POSITIVE_ADJUSTMENT".equals(in.kind()) && !scope.has("OPERATIONS")) throw new DomainException(403,"Operations role required");
        if(id==null && version!=null) throw new DomainException(400,"Unexpected action version");
        if(id==null && in.key()!=null) {
            var prior=db.query("select "+ACTION_COLUMNS+" from inventory_actions where workspace_id=? and entity_id=? and action_key=? for update",InventoryService::actionRow,w,e,in.key());
            if(!prior.isEmpty()) {
                if(sameIntent(prior.get(0),tracking(w,e,prior.get(0).id()),in)) return prior.get(0).id();
                throw new DomainException(409,"Action key conflicts with a different payload");
            }
        }
        ProductRef product=checkReferences(w,e,in);
        validateInput(in,product);
        Action old=id==null?null:findAction(w,e,id,true);
        if(old!=null && (!"DRAFT".equals(old.status()) || version==null || old.version()!=version)) throw new DomainException(409,"Action has changed");
        if(old!=null && (!old.key().equals(in.key()) || !old.kind().equals(in.kind()))) throw new DomainException(409,"Action identity cannot change");
        if(old==null && version!=null) throw new DomainException(400,"Unexpected action version");
        long actionId;
        if(old==null) {
            actionId=db.queryForObject("""
                insert into inventory_actions(workspace_id,entity_id,action_key,kind,status,product_id,product_version,uom_id,uom_version,setting_version,
                from_warehouse_id,from_warehouse_version,to_warehouse_id,to_warehouse_version,tracking,quantity_scale,quantity,value,posting_date,reason,created_by)
                values (?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
                """,Long.class,w,e,in.key(),in.kind(),in.productId(),in.productVersion(),in.uomId(),in.uomVersion(),in.settingVersion(),
                    in.fromWarehouseId(),in.fromWarehouseVersion(),in.toWarehouseId(),in.toWarehouseVersion(),product.tracking(),product.scale(),in.quantity(),in.value(),in.date(),in.reason(),scope.actor().id());
        } else {
            actionId=old.id();
            db.update("update inventory_action_tracking set active=false where workspace_id=? and entity_id=? and action_id=? and active",w,e,actionId);
            db.update("""
                update inventory_actions set product_id=?,product_version=?,uom_id=?,uom_version=?,setting_version=?,from_warehouse_id=?,from_warehouse_version=?,
                to_warehouse_id=?,to_warehouse_version=?,tracking=?,quantity_scale=?,quantity=?,value=?,posting_date=?,reason=?,version=version+1
                where workspace_id=? and entity_id=? and id=?
                """,in.productId(),in.productVersion(),in.uomId(),in.uomVersion(),in.settingVersion(),in.fromWarehouseId(),in.fromWarehouseVersion(),
                    in.toWarehouseId(),in.toWarehouseVersion(),product.tracking(),product.scale(),in.quantity(),in.value(),in.date(),in.reason(),w,e,actionId);
        }
        for(TrackingInput row:in.trackingRows()) db.update("insert into inventory_action_tracking(workspace_id,entity_id,action_id,product_id,serial,lot,expires_on,quantity) values (?,?,?,?,?,?,?,?)",
                w,e,actionId,in.productId(),row.serial(),row.lot(),row.expiresOn(),row.quantity());
        db.update("insert into inventory_action_contributors(workspace_id,entity_id,action_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,actionId,scope.actor().id());
        audit.record(scope.actor(),w,e,old==null?"CREATE":"EDIT","INVENTORY_ACTION",Long.toString(actionId),old==null?1:old.version()+1,"SUCCESS",in.kind());
        return actionId;
    }
    private boolean sameIntent(Action a,List<Track> tracks,ActionInput in) {
        if(!Objects.equals(a.key(),in.key()) || !Objects.equals(a.kind(),in.kind()) || a.productId()!=in.productId()
            || a.productVersion()!=in.productVersion() || a.uomId()!=in.uomId() || a.uomVersion()!=in.uomVersion()
            || a.settingVersion()!=in.settingVersion() || !Objects.equals(a.fromWarehouseId(),in.fromWarehouseId())
            || !Objects.equals(a.fromWarehouseVersion(),in.fromWarehouseVersion()) || !Objects.equals(a.toWarehouseId(),in.toWarehouseId())
            || !Objects.equals(a.toWarehouseVersion(),in.toWarehouseVersion()) || !equal(a.quantity(),in.quantity())
            || ("POSITIVE_ADJUSTMENT".equals(a.kind()) ? !equal(a.value(),in.value()) : in.value()!=null)
            || !Objects.equals(a.date(),in.date()) || !Objects.equals(a.reason(),in.reason()) || in.trackingRows()==null
            || tracks.size()!=in.trackingRows().size()) return false;
        for(int i=0;i<tracks.size();i++) {
            Track t=tracks.get(i); TrackingInput x=in.trackingRows().get(i);
            if(x==null || !Objects.equals(t.serial(),x.serial()) || !Objects.equals(t.lot(),x.lot())
                    || !Objects.equals(t.expiry(),x.expiresOn()) || !equal(t.quantity(),x.quantity())) return false;
        }
        return true;
    }
    private ActionInput input(Action a,List<Track> tracks) {
        return new ActionInput(a.key(),a.kind(),a.productId(),a.productVersion(),a.uomId(),a.uomVersion(),a.settingVersion(),
            a.fromWarehouseId(),a.fromWarehouseVersion(),a.toWarehouseId(),a.toWarehouseVersion(),a.quantity(),
            "POSITIVE_ADJUSTMENT".equals(a.kind())?a.value():null,a.date(),a.reason(),
            tracks.stream().map(t->new TrackingInput(t.serial(),t.lot(),t.expiry(),t.quantity())).toList());
    }
    @Transactional
    public void submitAction(long w,long e,long id,long version) {
        var scope=access.requireContext(w,e,true,"OPERATIONS","FINANCE");
        Action a=findAction(w,e,id,true);
        if("POSITIVE_ADJUSTMENT".equals(a.kind()) && "SUBMITTED".equals(a.status()) && a.version()==version+1) return;
        if(!"POSITIVE_ADJUSTMENT".equals(a.kind()) || !"DRAFT".equals(a.status()) || a.version()!=version) throw new DomainException(409,"Action cannot be submitted");
        var tracks=tracking(w,e,id); var in=input(a,tracks); var ref=checkReferences(w,e,in);
        if(!ref.tracking().equals(a.tracking()) || ref.scale()!=a.quantityScale()) throw new DomainException(409,"Product tracking or precision changed");
        validateInput(in,ref);
        db.update("insert into inventory_action_contributors(workspace_id,entity_id,action_id,user_id) values (?,?,?,?) on conflict do nothing",w,e,id,scope.actor().id());
        db.update("update inventory_actions set status='SUBMITTED',version=version+1 where workspace_id=? and entity_id=? and id=?",w,e,id);
        audit.record(scope.actor(),w,e,"SUBMIT","INVENTORY_ACTION",Long.toString(id),version+1,"SUCCESS",a.kind());
    }

    @Transactional
    public ActionResult postAction(long w,long e,long id,long version) {
        var scope=access.requireContext(w,e,true,"OPERATIONS","FINANCE");
        Action a=findAction(w,e,id,true);
        boolean positive="POSITIVE_ADJUSTMENT".equals(a.kind());
        if(positive ? !scope.has("FINANCE") : !scope.has("OPERATIONS")) throw new DomainException(403,positive?"Finance role required":"Operations role required");
        if("POSTED".equals(a.status())) {
            if(a.version()!=version+1 || a.postingHash()==null) throw new DomainException(409,"Action replay does not match");
            return new ActionResult(id,scope.has("FINANCE")?a.journalId():null,true);
        }
        if(a.version()!=version || !(positive?"SUBMITTED":"DRAFT").equals(a.status())) throw new DomainException(409,"Action has changed");
        if(positive && (a.createdBy()==scope.actor().id() || Boolean.TRUE.equals(db.queryForObject(
                "select exists(select 1 from inventory_action_contributors where workspace_id=? and entity_id=? and action_id=? and user_id=?)",
                Boolean.class,w,e,id,scope.actor().id())))) throw new DomainException(403,"A contributor cannot approve this adjustment");
        var tracks=tracking(w,e,id);
        var in=input(a,tracks); var ref=checkReferences(w,e,in);
        if(!ref.tracking().equals(a.tracking()) || ref.scale()!=a.quantityScale()) throw new DomainException(409,"Product tracking or precision changed");
        validateInput(in,ref);
        LocalDate businessDate=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
        LocalDate expiryDate=a.date().isAfter(businessDate)?a.date():businessDate;
        validateTracking(new Source(0,a.productId(),0,a.tracking(),BigDecimal.ZERO,a.quantity(),BigDecimal.ZERO,null),tracks,expiryDate);
        ensureHistoricalShape(w,e,a);
        var pools=db.query("select quantity,value,last_posting_date,pool_sequence from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=? for update",
                (r,n)->new Object[]{r.getBigDecimal(1),r.getBigDecimal(2),r.getObject(3,LocalDate.class),r.getLong(4)},w,e,a.productId());
        BigDecimal poolQ=pools.isEmpty()?BigDecimal.ZERO:(BigDecimal)pools.get(0)[0];
        BigDecimal poolV=pools.isEmpty()?BigDecimal.ZERO:(BigDecimal)pools.get(0)[1];
        if(!pools.isEmpty() && a.date().isBefore((LocalDate)pools.get(0)[2])) throw new DomainException(409,"Stock posting date precedes pool watermark");
        if(!positive && poolQ.compareTo(a.quantity())<0) throw new DomainException(409,"Insufficient stock");
        BigDecimal value=positive?a.value():(poolQ.compareTo(a.quantity())==0?poolV:poolV.multiply(a.quantity()).divide(poolQ,2,RoundingMode.HALF_UP));
        String currency=db.queryForObject("select base_currency from legal_entities where workspace_id=? and id=?",String.class,w,e);
        var journalLines=new ArrayList<Ledger.Line>();
        if(!"TRANSFER".equals(a.kind()) && value.signum()!=0) {
            var configs=db.query("select inventory_account_id,adjustment_gain_account_id,adjustment_loss_account_id from procurement_posting_config where workspace_id=? and entity_id=? for update",
                    (r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)},w,e);
            if(configs.size()!=1) throw new DomainException(409,"Inventory posting configuration required");
            long[] c=configs.get(0); BigDecimal zero=BigDecimal.ZERO.setScale(2);
            if(positive) { journalLines.add(new Ledger.Line(c[0],value,zero)); journalLines.add(new Ledger.Line(c[1],zero,value)); }
            else { journalLines.add(new Ledger.Line(c[2],value,zero)); journalLines.add(new Ledger.Line(c[0],zero,value)); }
        }
        var posted=posting.post(scope,"INVENTORY_ACTION",Long.toString(id),"inventory-action:"+id,a.date(),currency,a.reason(),journalLines);
        long sequence=pools.isEmpty()?1:(long)pools.get(0)[3]+1;
        long finalSequence=Math.addExact(sequence,Math.max(1,tracks.size())-1L);
        BigDecimal quantityDelta=positive?a.quantity():"TRANSFER".equals(a.kind())?BigDecimal.ZERO:a.quantity().negate();
        BigDecimal valueDelta=positive?value:"TRANSFER".equals(a.kind())?BigDecimal.ZERO:value.negate();
        if(pools.isEmpty()) db.update("insert into inventory_valuation_pools(workspace_id,entity_id,product_id,quantity,value,last_posting_date,pool_sequence) values (?,?,?,?,?,?,?)",w,e,a.productId(),quantityDelta,valueDelta,a.date(),finalSequence);
        else db.update("update inventory_valuation_pools set quantity=quantity+?,value=value+?,last_posting_date=?,pool_sequence=? where workspace_id=? and entity_id=? and product_id=?",quantityDelta,valueDelta,a.date(),finalSequence,w,e,a.productId());
        if(a.fromWarehouseId()!=null) changeBalance(w,e,a.productId(),a.fromWarehouseId(),a.quantity().negate());
        if(a.toWarehouseId()!=null) changeBalance(w,e,a.productId(),a.toWarehouseId(),a.quantity());
        applyActionTracking(scope,a,tracks,sequence,value);
        String hash=actionHash(a,tracks);
        db.update("update inventory_actions set status='POSTED',value=?,approved_by=?,posted_by=?,posted_at=now(),posting_hash=?,journal_id=?,version=version+1 where workspace_id=? and entity_id=? and id=?",
                value,positive?scope.actor().id():null,scope.actor().id(),hash,posted.journalId(),w,e,id);
        audit.record(scope.actor(),w,e,"POST","INVENTORY_ACTION",Long.toString(id),version+1,"SUCCESS",a.kind());
        return new ActionResult(id,scope.has("FINANCE")?posted.journalId():null,false);
    }

    private void ensureHistoricalShape(long w,long e,Action a) {
        ensureHistoricalShape(w,e,a.productId(),a.tracking(),a.uomId(),a.quantityScale());
    }
    private void ensureHistoricalShape(long w,long e,long product,String tracking,long uom,int scale) {
        boolean incompatible=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from stock_movements m left join inventory_lots l on l.workspace_id=m.workspace_id and l.entity_id=m.entity_id and l.id=m.lot_id
             where m.workspace_id=? and m.entity_id=? and m.product_id=? and
              ((?='NONE' and (m.serial_id is not null or m.lot_id is not null))
               or (?='SERIAL' and m.serial_id is null)
               or (? in ('LOT','LOT_EXPIRY') and m.lot_id is null)
               or (?='LOT' and l.expires_on is not null) or (?='LOT_EXPIRY' and m.lot_id is not null and l.expires_on is null)))
            """,Boolean.class,w,e,product,tracking,tracking,tracking,tracking,tracking));
        boolean oldUnit=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from stock_movements m
              join procurement_receipt_lines l on l.workspace_id=m.workspace_id and l.entity_id=m.entity_id and l.id::text=split_part(m.source_line_key,':',1)
              join purchase_order_lines p on p.workspace_id=l.workspace_id and p.entity_id=l.entity_id and p.id=l.order_line_id
             where m.workspace_id=? and m.entity_id=? and m.product_id=? and m.source_type='RECEIPT'
               and (p.uom_id<>? or p.tracking<>? or p.quantity_scale<>?))
            """,Boolean.class,w,e,product,uom,tracking,scale));
        boolean oldAction=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from inventory_actions x where x.workspace_id=? and x.entity_id=? and x.product_id=? and x.status='POSTED'
                and (x.uom_id<>? or x.tracking<>? or x.quantity_scale<>?))
            """,Boolean.class,w,e,product,uom,tracking,scale));
        boolean unknownOpening=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from stock_movements m join products p on p.workspace_id=m.workspace_id and p.id=m.product_id
                join uoms u on u.workspace_id=p.workspace_id and u.id=p.uom_id
                where m.workspace_id=? and m.entity_id=? and m.product_id=? and m.source_type='OPENING'
                  and (p.version<>1 or u.version<>1 or p.uom_id<>?))
            """,Boolean.class,w,e,product,uom));
        if(incompatible || oldUnit || oldAction || unknownOpening) throw new DomainException(409,"Existing stock uses different or unverifiable tracking or unit");
        boolean badPool=Boolean.TRUE.equals(db.queryForObject("""
            select coalesce((select p.quantity from inventory_valuation_pools p where p.workspace_id=? and p.entity_id=? and p.product_id=?),0)
                   <> coalesce((select sum(b.on_hand) from inventory_balances b where b.workspace_id=? and b.entity_id=? and b.product_id=?),0)
                or coalesce((select p.quantity from inventory_valuation_pools p where p.workspace_id=? and p.entity_id=? and p.product_id=?),0)
                   <> coalesce((select sum(m.quantity_delta) from stock_movements m where m.workspace_id=? and m.entity_id=? and m.product_id=?),0)
                or coalesce((select p.value from inventory_valuation_pools p where p.workspace_id=? and p.entity_id=? and p.product_id=?),0)
                   <> coalesce((select sum(m.value_delta) from stock_movements m where m.workspace_id=? and m.entity_id=? and m.product_id=?),0)
            """,Boolean.class,w,e,product,w,e,product,w,e,product,w,e,product,w,e,product,w,e,product));
        boolean badTracking=false;
        if("SERIAL".equals(tracking)) badTracking=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from inventory_balances b where b.workspace_id=? and b.entity_id=? and b.product_id=? and b.on_hand<>
                (select count(*) from instrument_serials s where s.workspace_id=b.workspace_id and s.entity_id=b.entity_id and s.product_id=b.product_id and s.warehouse_id=b.warehouse_id and s.status='IN_STOCK'))
              or exists(select 1 from instrument_serials s where s.workspace_id=? and s.entity_id=? and s.product_id=? and s.status='IN_STOCK'
                and not exists(select 1 from inventory_balances b where b.workspace_id=s.workspace_id and b.entity_id=s.entity_id and b.product_id=s.product_id and b.warehouse_id=s.warehouse_id))
            """,Boolean.class,w,e,product,w,e,product));
        else if("LOT".equals(tracking) || "LOT_EXPIRY".equals(tracking)) badTracking=Boolean.TRUE.equals(db.queryForObject("""
            select exists(select 1 from inventory_balances b where b.workspace_id=? and b.entity_id=? and b.product_id=? and b.on_hand<>
                coalesce((select sum(lb.quantity) from inventory_lot_balances lb join inventory_lots l on l.workspace_id=lb.workspace_id and l.entity_id=lb.entity_id and l.id=lb.lot_id
                    where lb.workspace_id=b.workspace_id and lb.entity_id=b.entity_id and l.product_id=b.product_id and lb.warehouse_id=b.warehouse_id),0))
              or exists(select 1 from inventory_lot_balances lb join inventory_lots l on l.workspace_id=lb.workspace_id and l.entity_id=lb.entity_id and l.id=lb.lot_id
                where lb.workspace_id=? and lb.entity_id=? and l.product_id=? and lb.quantity<>0
                  and not exists(select 1 from inventory_balances b where b.workspace_id=lb.workspace_id and b.entity_id=lb.entity_id and b.product_id=l.product_id and b.warehouse_id=lb.warehouse_id))
            """,Boolean.class,w,e,product,w,e,product));
        if(badPool || badTracking) throw new DomainException(409,"Existing stock balances do not reconcile");
    }
    private void changeBalance(long w,long e,long product,long warehouse,BigDecimal delta) {
        if(delta.signum()<0) {
            int changed=db.update("update inventory_balances set on_hand=on_hand+?,version=version+1 where workspace_id=? and entity_id=? and product_id=? and warehouse_id=? and on_hand-reserved>=?",
                    delta,w,e,product,warehouse,delta.negate());
            if(changed!=1) throw new DomainException(409,"Insufficient unreserved warehouse stock");
        } else db.update("insert into inventory_balances(workspace_id,entity_id,product_id,warehouse_id,on_hand) values (?,?,?,?,?) on conflict(workspace_id,entity_id,product_id,warehouse_id) do update set on_hand=inventory_balances.on_hand+excluded.on_hand,version=inventory_balances.version+1",
                w,e,product,warehouse,delta);
    }
    private String actionHash(Action a,List<Track> tracks) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            String data=a.id()+"|"+a.version()+"|"+a.key()+"|"+a.kind()+"|"+a.productId()+"|"+a.fromWarehouseId()+"|"+a.toWarehouseId()+"|"+a.quantity().toPlainString()+"|"+a.value()+"|"+a.date()+"|"+a.reason();
            digest.update(data.getBytes(StandardCharsets.UTF_8));
            for(Track t:tracks) digest.update(("|"+t.id()+"|"+t.serial()+"|"+t.lot()+"|"+t.expiry()+"|"+t.quantity().toPlainString()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void applyActionTracking(AccessControl.Scope scope,Action a,List<Track> tracks,long sequence,BigDecimal value) {
        long w=scope.workspaceId(),e=scope.entityId();
        if(tracks.isEmpty()) {
            actionMovements(scope,a,sequence,"0",a.quantity(),value,null,null);
            return;
        }
        BigDecimal cumulative=BigDecimal.ZERO, assigned=BigDecimal.ZERO;
        for(Track t:tracks) {
            cumulative=cumulative.add(t.quantity());
            BigDecimal part=value.multiply(cumulative).divide(a.quantity(),2,RoundingMode.HALF_UP).subtract(assigned);
            assigned=assigned.add(part);
            Long serialId=null,lotId=null;
            if("SERIAL".equals(a.tracking())) {
                var serials=db.query("select id,product_id,warehouse_id,status from instrument_serials where workspace_id=? and entity_id=? and serial=? for update",
                        (r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4)},w,e,t.serial());
                if(a.fromWarehouseId()==null) {
                    if(!serials.isEmpty()) throw new DomainException(409,"Serial already exists");
                    serialId=db.queryForObject("insert into instrument_serials(workspace_id,entity_id,product_id,warehouse_id,serial,status) values (?,?,?,?,?,'IN_STOCK') returning id",
                            Long.class,w,e,a.productId(),a.toWarehouseId(),t.serial());
                } else {
                    if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from inventory_reservations r join instrument_serials s on s.workspace_id=r.workspace_id and s.entity_id=r.entity_id and s.id=r.serial_id where r.workspace_id=? and r.entity_id=? and s.serial=? and r.released_qty+r.consumed_qty<r.quantity)",Boolean.class,w,e,t.serial())))
                        throw new DomainException(409,"Serial is reserved for an order");
                    if(serials.size()!=1 || (long)serials.get(0)[1]!=a.productId() || (long)serials.get(0)[2]!=a.fromWarehouseId() || !"IN_STOCK".equals(serials.get(0)[3]))
                        throw new DomainException(409,"Serial is not available in the source warehouse");
                    serialId=(long)serials.get(0)[0];
                    if(a.toWarehouseId()==null) db.update("update instrument_serials set status='ISSUED' where workspace_id=? and entity_id=? and id=?",w,e,serialId);
                    else db.update("update instrument_serials set warehouse_id=? where workspace_id=? and entity_id=? and id=?",a.toWarehouseId(),w,e,serialId);
                }
            } else {
                var lots=db.query("select id,expires_on from inventory_lots where workspace_id=? and entity_id=? and product_id=? and lot=? for update",
                        (r,n)->new Object[]{r.getLong(1),r.getObject(2,LocalDate.class)},w,e,a.productId(),t.lot());
                if(lots.isEmpty()) {
                    if(a.fromWarehouseId()!=null) throw new DomainException(409,"Lot does not exist");
                    lotId=db.queryForObject("insert into inventory_lots(workspace_id,entity_id,product_id,warehouse_id,lot,expires_on) values (?,?,?,?,?,?) returning id",
                            Long.class,w,e,a.productId(),a.toWarehouseId(),t.lot(),t.expiry());
                } else {
                    if(!Objects.equals(lots.get(0)[1],t.expiry())) throw new DomainException(409,"Lot expiry does not match existing lot");
                    lotId=(long)lots.get(0)[0];
                }
                if(a.fromWarehouseId()!=null) {
                    int changed=db.update("update inventory_lot_balances set quantity=quantity-?,version=version+1 where workspace_id=? and entity_id=? and lot_id=? and warehouse_id=? and quantity-reserved>=?",
                            t.quantity(),w,e,lotId,a.fromWarehouseId(),t.quantity());
                    if(changed!=1) throw new DomainException(409,"Insufficient unreserved lot quantity");
                }
                if(a.toWarehouseId()!=null) db.update("insert into inventory_lot_balances(workspace_id,entity_id,lot_id,warehouse_id,quantity) values (?,?,?,?,?) on conflict(workspace_id,entity_id,lot_id,warehouse_id) do update set quantity=inventory_lot_balances.quantity+excluded.quantity,version=inventory_lot_balances.version+1",
                        w,e,lotId,a.toWarehouseId(),t.quantity());
            }
            actionMovements(scope,a,sequence++,Long.toString(t.id()),t.quantity(),part,serialId,lotId);
        }
    }
    private void actionMovements(AccessControl.Scope scope,Action a,long sequence,String key,BigDecimal quantity,BigDecimal value,Long serial,Long lot) {
        if(a.fromWarehouseId()!=null) actionMovement(scope,a,sequence,key+":out",a.fromWarehouseId(),quantity.negate(),value.negate(),serial,lot);
        if(a.toWarehouseId()!=null) actionMovement(scope,a,sequence,key+":in",a.toWarehouseId(),quantity,value,serial,lot);
    }
    private void actionMovement(AccessControl.Scope scope,Action a,long sequence,String key,long warehouse,BigDecimal quantity,BigDecimal value,Long serial,Long lot) {
        db.update("insert into stock_movements(workspace_id,entity_id,product_id,warehouse_id,source_type,source_id,pool_sequence,quantity_delta,value_delta,posting_date,source_line_key,serial_id,lot_id,created_by) values (?,?,?,?,'INVENTORY_ACTION',?,?,?,?,?,?,?,?,?)",
                scope.workspaceId(),scope.entityId(),a.productId(),warehouse,Long.toString(a.id()),sequence,quantity,value,a.date(),key,serial,lot,scope.actor().id());
    }

    /** Called only by the receipt posting transaction, before the receipt is marked posted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void receive(AccessControl.Scope supplied, long receiptId, LocalDate postingDate, List<ReceiptStockLine> lines) {
        if (supplied == null || supplied.entityId() == null) throw new DomainException(403, "Entity context required");
        var scope = access.requireContext(supplied.workspaceId(), supplied.entityId(), true, "OPERATIONS");
        if (supplied.actor()==null || scope.actor().id() != supplied.actor().id()) throw new DomainException(403, "Invalid actor");
        long w = scope.workspaceId(), e = scope.entityId();
        if (postingDate == null || lines == null || lines.isEmpty() || lines.size() > 100) throw new DomainException(400, "Invalid receipt stock lines");
        var headers = db.query("select posting_date from procurement_receipts where workspace_id=? and entity_id=? and id=? and kind='GOODS' and status='DRAFT' for update", (r,n) -> r.getObject(1, LocalDate.class), w,e,receiptId);
        if (headers.size()!=1 || !postingDate.equals(headers.get(0))) throw new DomainException(409, "Receipt is not an eligible goods draft");
        LocalDate businessDate = db.queryForObject("select business_date from scenario_workspaces where id=?", LocalDate.class, w);
        if (businessDate == null) throw new DomainException(409, "Workspace business date is required");
        var sources = db.query("""
            select l.id,p.product_id,l.warehouse_id,p.tracking,l.slice_start,l.slice_end,l.net,
                   p.quantity,p.unit_cost,p.subtotal,p.discount,p.net,p.tax,p.gross
              from procurement_receipt_lines l
              join purchase_order_lines p on p.workspace_id=l.workspace_id and p.entity_id=l.entity_id and p.id=l.order_line_id
              join purchase_order_revisions v on v.workspace_id=p.workspace_id and v.entity_id=p.entity_id and v.id=p.revision_id
              join procurement_receipts h on h.workspace_id=l.workspace_id and h.entity_id=l.entity_id and h.id=l.receipt_id
              join warehouses wh on wh.workspace_id=l.workspace_id and wh.entity_id=l.entity_id and wh.id=l.warehouse_id and wh.version=l.warehouse_version and wh.active
             where l.workspace_id=? and l.entity_id=? and l.receipt_id=? and l.active and p.active and l.warehouse_id is not null
               and v.status='APPROVED' and v.order_id=h.order_id and p.product_category not in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE')
             order by l.id
            """, (r,n) -> new Source(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getBigDecimal(5),r.getBigDecimal(6),r.getBigDecimal(7),
                    new SourceAmounts.Snapshot(r.getBigDecimal(8),r.getBigDecimal(9),r.getBigDecimal(10),r.getBigDecimal(11),r.getBigDecimal(12),r.getBigDecimal(13),r.getBigDecimal(14))), w,e,receiptId);
        Integer count = db.queryForObject("select count(*) from procurement_receipt_lines where workspace_id=? and entity_id=? and receipt_id=? and active", Integer.class,w,e,receiptId);
        if (sources.size()!=count || lines.size()!=count) throw new DomainException(409, "Receipt lines do not match approved physical sources");
        Set<Long> seen = new HashSet<>();
        for (Source source : sources) {
            ReceiptStockLine input = lines.stream().filter(x -> x != null && x.receiptLineId()==source.id()).findFirst().orElseThrow(() -> new DomainException(409,"Receipt line mismatch"));
            if (!seen.add(input.receiptLineId()) || input.productId()!=source.product() || input.warehouseId()!=source.warehouse()
                || !Objects.equals(input.tracking(),source.tracking()) || !equal(input.quantity(),source.end().subtract(source.start()))
                || !equal(input.net(),source.net()) || !equal(source.net(),SourceAmounts.slice(source.snapshot(),source.start(),source.end()).net()))
                throw new DomainException(409,"Receipt stock does not match approved source");
            var tracks = db.query("select id,serial,lot,expires_on,quantity from receipt_tracking where workspace_id=? and entity_id=? and receipt_line_id=? and active order by id",
                (r,n) -> new Track(r.getLong(1),r.getString(2),r.getString(3),r.getObject(4,LocalDate.class),r.getBigDecimal(5)),w,e,source.id());
            if (input.trackingRows()==null || input.trackingRows().size()!=tracks.size()) throw new DomainException(409,"Receipt tracking mismatch");
            var unmatched = new ArrayList<>(input.trackingRows());
            for (Track t : tracks) {
                int index = -1;
                for (int i=0;i<unmatched.size();i++) { TrackingInput x=unmatched.get(i); if (x!=null && Objects.equals(x.serial(),t.serial()) && Objects.equals(x.lot(),t.lot()) && Objects.equals(x.expiresOn(),t.expiry()) && equal(x.quantity(),t.quantity())) { index=i; break; } }
                if (index<0) throw new DomainException(409,"Receipt tracking mismatch");
                unmatched.remove(index);
            }
            validateTracking(source, tracks, postingDate.isAfter(businessDate) ? postingDate : businessDate);
            var units=db.query("select p.uom_id,p.quantity_scale from procurement_receipt_lines l join purchase_order_lines p on p.workspace_id=l.workspace_id and p.entity_id=l.entity_id and p.id=l.order_line_id where l.workspace_id=? and l.entity_id=? and l.id=?",
                    (r,n)->new long[]{r.getLong(1),r.getInt(2)},w,e,source.id());
            if(units.size()!=1) throw new DomainException(409,"Receipt unit snapshot is unavailable");
            int scale=(int)units.get(0)[1];
            for(Track t:tracks) if(t.quantity().stripTrailingZeros().scale()>scale) throw new DomainException(400,"Tracking quantity exceeds unit precision");
            ensureHistoricalShape(w,e,source.product(),source.tracking(),units.get(0)[0],scale);
            applyReceipt(scope,receiptId,postingDate,source,tracks);
        }
    }

    private void validateTracking(Source s, List<Track> rows, LocalDate date) {
        if ("NONE".equals(s.tracking())) { if (!rows.isEmpty()) throw new DomainException(400,"Untracked product cannot have tracking rows"); return; }
        BigDecimal total=BigDecimal.ZERO;
        Set<String> keys=new HashSet<>();
        for (Track t:rows) {
            if (t.quantity()==null || t.quantity().signum()<=0 || t.quantity().scale()>6 || t.quantity().scale() < -18 || (long)t.quantity().precision()-t.quantity().scale()>12) throw new DomainException(400,"Invalid tracking quantity");
            total=total.add(t.quantity());
            if ("SERIAL".equals(s.tracking())) {
                if (t.serial()==null || t.serial().isBlank() || t.serial().length()>200 || t.lot()!=null || t.expiry()!=null || t.quantity().compareTo(BigDecimal.ONE)!=0 || !keys.add(t.serial())) throw new DomainException(400,"Invalid serial tracking");
            } else if ("LOT".equals(s.tracking()) || "LOT_EXPIRY".equals(s.tracking())) {
                if (t.lot()==null || t.lot().isBlank() || t.lot().length()>200 || t.serial()!=null || !keys.add(t.lot()) || ("LOT".equals(s.tracking()) && t.expiry()!=null) || ("LOT_EXPIRY".equals(s.tracking()) && (t.expiry()==null || !t.expiry().isAfter(date)))) throw new DomainException(400,"Invalid or expired lot tracking");
            } else throw new DomainException(400,"Invalid tracking mode");
        }
        if (total.compareTo(s.end().subtract(s.start()))!=0) throw new DomainException(400,"Tracking quantities must equal receipt quantity");
    }

    private void applyReceipt(AccessControl.Scope scope, long receiptId, LocalDate date, Source s, List<Track> tracks) {
        long w=scope.workspaceId(), e=scope.entityId();
        if (Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from stock_movements where workspace_id=? and entity_id=? and source_type='RECEIPT' and source_id=? and source_line_key like ?)",Boolean.class,w,e,Long.toString(receiptId),s.id()+":%"))) throw new DomainException(409,"Receipt stock already applied");
        var pool=db.query("select last_posting_date,pool_sequence from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=? for update",(r,n)->new Object[]{r.getObject(1,LocalDate.class),r.getLong(2)},w,e,s.product());
        long sequence=1;
        BigDecimal quantity=s.end().subtract(s.start());
        if (pool.isEmpty()) db.update("insert into inventory_valuation_pools(workspace_id,entity_id,product_id,quantity,value,last_posting_date,pool_sequence) values (?,?,?,?,?,?,?)",w,e,s.product(),quantity,s.net(),date,(long)Math.max(1,tracks.size()));
        else {
            if (date.isBefore((LocalDate)pool.get(0)[0])) throw new DomainException(409,"Stock posting date precedes pool watermark");
            sequence=(long)pool.get(0)[1]+1;
            db.update("update inventory_valuation_pools set quantity=quantity+?,value=value+?,last_posting_date=?,pool_sequence=? where workspace_id=? and entity_id=? and product_id=?",quantity,s.net(),date,Math.addExact(sequence,Math.max(1,tracks.size())-1L),w,e,s.product());
        }
        db.update("insert into inventory_balances(workspace_id,entity_id,product_id,warehouse_id,on_hand) values (?,?,?,?,?) on conflict(workspace_id,entity_id,product_id,warehouse_id) do update set on_hand=inventory_balances.on_hand+excluded.on_hand,version=inventory_balances.version+1",w,e,s.product(),s.warehouse(),quantity);
        if (tracks.isEmpty()) { movement(scope,receiptId,date,s,sequence,s.id()+":0",quantity,s.net(),null,null); return; }
        BigDecimal cumulative=BigDecimal.ZERO, assigned=BigDecimal.ZERO;
        for (Track t:tracks) {
            cumulative=cumulative.add(t.quantity());
            BigDecimal value=s.net().multiply(cumulative).divide(quantity,2,RoundingMode.HALF_UP).subtract(assigned); assigned=assigned.add(value);
            Long serial=null, lot=null;
            if (t.serial()!=null) {
                if (Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from instrument_serials where workspace_id=? and entity_id=? and serial=?)",Boolean.class,w,e,t.serial()))) throw new DomainException(409,"Serial already exists");
                serial=db.queryForObject("insert into instrument_serials(workspace_id,entity_id,product_id,warehouse_id,serial,status) values (?,?,?,?,?,'IN_STOCK') returning id",Long.class,w,e,s.product(),s.warehouse(),t.serial());
            } else {
                var found=db.query("select id,expires_on from inventory_lots where workspace_id=? and entity_id=? and product_id=? and lot=? for update",(r,n)->new Object[]{r.getLong(1),r.getObject(2,LocalDate.class)},w,e,s.product(),t.lot());
                if (found.isEmpty()) lot=db.queryForObject("insert into inventory_lots(workspace_id,entity_id,product_id,warehouse_id,lot,expires_on) values (?,?,?,?,?,?) returning id",Long.class,w,e,s.product(),s.warehouse(),t.lot(),t.expiry());
                else { if (!Objects.equals(found.get(0)[1],t.expiry())) throw new DomainException(409,"Lot expiry does not match existing lot"); lot=(long)found.get(0)[0]; }
                db.update("insert into inventory_lot_balances(workspace_id,entity_id,lot_id,warehouse_id,quantity) values (?,?,?,?,?) on conflict(workspace_id,entity_id,lot_id,warehouse_id) do update set quantity=inventory_lot_balances.quantity+excluded.quantity,version=inventory_lot_balances.version+1",w,e,lot,s.warehouse(),t.quantity());
            }
            movement(scope,receiptId,date,s,sequence++,s.id()+":"+t.id(),t.quantity(),value,serial,lot);
        }
    }
    private void movement(AccessControl.Scope scope,long receipt,LocalDate date,Source s,long sequence,String key,BigDecimal q,BigDecimal value,Long serial,Long lot) {
        db.update("insert into stock_movements(workspace_id,entity_id,product_id,warehouse_id,source_type,source_id,pool_sequence,quantity_delta,value_delta,posting_date,source_line_key,serial_id,lot_id,created_by) values (?,?,?,?,'RECEIPT',?,?,?,?,?,?,?,?,?)",scope.workspaceId(),scope.entityId(),s.product(),s.warehouse(),Long.toString(receipt),sequence,q,value,date,key,serial,lot,scope.actor().id());
    }
    /** Reserve stock for one order line; the caller holds the order-line lock. */
    @Transactional(propagation=Propagation.MANDATORY)
    public long reserveOrderStock(AccessControl.Scope scope,long line,long product,long warehouse,String tracking,
                                  Long serial,Long lot,BigDecimal quantity,String key,String hash) {
        if(scope==null || scope.entityId()==null || scope.actor()==null) throw new DomainException(403,"Entity context required");
        var current=access.requireContext(scope.workspaceId(),scope.entityId(),true,"OPERATIONS");
        if(current.actor().id()!=scope.actor().id()) throw new DomainException(403,"Invalid actor");
        if(quantity==null || quantity.signum()<=0) throw new DomainException(400,"Invalid reservation quantity");
        long w=scope.workspaceId(),e=scope.entityId();
        checkOrderStockShape(w,e,line,product,tracking);
        LocalDate date=db.queryForObject("select business_date from scenario_workspaces where id=?",LocalDate.class,w);
        if ("SERIAL".equals(tracking)) {
            if(serial==null || lot!=null || quantity.compareTo(BigDecimal.ONE)!=0 ||
               !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from instrument_serials s where s.workspace_id=? and s.entity_id=? and s.id=? and s.product_id=? and s.warehouse_id=? and s.status='IN_STOCK' and not exists(select 1 from inventory_reservations r where r.workspace_id=s.workspace_id and r.entity_id=s.entity_id and r.serial_id=s.id and r.released_qty+r.consumed_qty<r.quantity))",Boolean.class,w,e,serial,product,warehouse)))
                throw new DomainException(409,"Serial is unavailable");
        } else if ("LOT".equals(tracking) || "LOT_EXPIRY".equals(tracking)) {
            if(lot==null || serial!=null || !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from inventory_lots where workspace_id=? and entity_id=? and id=? and product_id=? and (?='LOT' and expires_on is null or ?='LOT_EXPIRY' and expires_on>?))",Boolean.class,w,e,lot,product,tracking,tracking,date)))
                throw new DomainException(409,"Lot is unavailable or expired");
            if(db.update("update inventory_lot_balances set reserved=reserved+?,version=version+1 where workspace_id=? and entity_id=? and lot_id=? and warehouse_id=? and quantity-reserved>=?",quantity,w,e,lot,warehouse,quantity)!=1)
                throw new DomainException(409,"Insufficient unreserved lot stock");
        } else if (!"NONE".equals(tracking) || serial!=null || lot!=null) throw new DomainException(400,"Invalid tracking selection");
        if(db.update("update inventory_balances set reserved=reserved+?,version=version+1 where workspace_id=? and entity_id=? and product_id=? and warehouse_id=? and on_hand-reserved>=?",quantity,w,e,product,warehouse,quantity)!=1)
            throw new DomainException(409,"Insufficient unreserved warehouse stock");
        long id=db.queryForObject("insert into inventory_reservations(workspace_id,entity_id,order_line_id,product_id,warehouse_id,serial_id,lot_id,quantity,intent_key,intent_hash,created_by) values (?,?,?,?,?,?,?,?,?,?,?) returning id",Long.class,w,e,line,product,warehouse,serial,lot,quantity,key,hash,scope.actor().id());
        db.update("insert into inventory_reservation_events(workspace_id,entity_id,reservation_id,kind,quantity,intent_key,intent_hash,created_by) values (?,?,?,'RESERVE',?,?,?,?)",w,e,id,quantity,key,hash,scope.actor().id());
        return id;
    }

    public record ReservationPart(long reservationId, BigDecimal quantity, String eventKey, String intentHash) {}

    /** Price one shipment line from the current pool, then allocate its cost across exact tracking rows. */
    @Transactional(propagation=Propagation.MANDATORY)
    public BigDecimal issueOrderStock(AccessControl.Scope scope,long product,long warehouse,long orderLine,long shipment,
                                      LocalDate date,List<ReservationPart> parts) {
        if(scope==null || scope.entityId()==null || scope.actor()==null) throw new DomainException(403,"Entity context required");
        var current=access.requireContext(scope.workspaceId(),scope.entityId(),true,"OPERATIONS");
        if(current.actor().id()!=scope.actor().id()) throw new DomainException(403,"Invalid actor");
        if(date==null || parts==null || parts.isEmpty()) throw new DomainException(400,"Shipment tracking required");
        long w=scope.workspaceId(),e=scope.entityId();
        checkOrderStockShape(w,e,orderLine,product,null);
        BigDecimal total=BigDecimal.ZERO;
        Set<Long> seen=new HashSet<>();
        for(var part:parts) {
            if(part==null || part.quantity()==null || part.quantity().signum()<=0 || !seen.add(part.reservationId())) throw new DomainException(400,"Invalid shipment reservation");
            if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from inventory_reservations where workspace_id=? and entity_id=? and id=? and product_id=? and warehouse_id=? and order_line_id=?)",Boolean.class,w,e,part.reservationId(),product,warehouse,orderLine)))
                throw new DomainException(409,"Reservation does not belong to shipment source");
            total=total.add(part.quantity());
        }
        var pools=db.queryForList("select quantity,value,last_posting_date from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=? for update",w,e,product);
        if(pools.size()!=1) throw new DomainException(409,"Stock pool unavailable");
        var pool=pools.get(0); BigDecimal q=(BigDecimal)pool.get("quantity"),v=(BigDecimal)pool.get("value");
        LocalDate watermark=pool.get("last_posting_date") instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate)pool.get("last_posting_date");
        if(q.compareTo(total)<0 || date.isBefore(watermark)) throw new DomainException(409,"Insufficient stock or posting before stock watermark");
        BigDecimal cost=q.compareTo(total)==0?v:v.multiply(total).divide(q,2,RoundingMode.HALF_UP);
        BigDecimal cumulative=BigDecimal.ZERO,assigned=BigDecimal.ZERO;
        for(var part:parts) {
            cumulative=cumulative.add(part.quantity());
            BigDecimal allocated=cost.multiply(cumulative).divide(total,2,RoundingMode.HALF_UP).subtract(assigned);
            assigned=assigned.add(allocated);
            applyOrderReservationInternal(scope,part.reservationId(),part.quantity(),true,date,shipment,part.eventKey(),part.intentHash(),allocated);
        }
        return cost;
    }
    private void checkOrderStockShape(long w,long e,long line,long product,String expectedTracking) {
        var sources=db.query("select tracking,uom_id,quantity_scale from sales_order_lines where workspace_id=? and entity_id=? and id=? and product_id=? for update",
                (r,n)->new Object[]{r.getString(1),r.getLong(2),r.getInt(3)},w,e,line,product);
        if(sources.size()!=1 || (expectedTracking!=null && !expectedTracking.equals(sources.get(0)[0])))
            throw new DomainException(409,"Order stock source changed");
        var source=sources.get(0);
        ensureHistoricalShape(w,e,product,(String)source[0],(long)source[1],(int)source[2]);
    }

    /** Release or consume a persisted reservation, updating the corresponding exact tracking balance. */
    @Transactional(propagation=Propagation.MANDATORY)
    public BigDecimal applyOrderReservation(AccessControl.Scope scope,long reservation,BigDecimal quantity,
                                            boolean consume,LocalDate date,long shipment,String eventKey,String hash) {
        if(consume) throw new DomainException(400,"Shipment issue requires complete tracking allocation");
        return applyOrderReservationInternal(scope,reservation,quantity,false,date,shipment,eventKey,hash,null);
    }
    private BigDecimal applyOrderReservationInternal(AccessControl.Scope scope,long reservation,BigDecimal quantity,
                                            boolean consume,LocalDate date,long shipment,String eventKey,String hash,BigDecimal allocatedCost) {
        if(scope==null || scope.entityId()==null || scope.actor()==null) throw new DomainException(403,"Entity context required");
        var current=access.requireContext(scope.workspaceId(),scope.entityId(),true,"OPERATIONS");
        if(current.actor().id()!=scope.actor().id()) throw new DomainException(403,"Invalid actor");
        long w=scope.workspaceId(),e=scope.entityId();
        var rows=db.queryForList("select * from inventory_reservations where workspace_id=? and entity_id=? and id=? for update",w,e,reservation);
        if(rows.size()!=1 || quantity==null || quantity.signum()<=0) throw new DomainException(409,"Reservation unavailable");
        var r=rows.get(0);
        BigDecimal remaining=((BigDecimal)r.get("quantity")).subtract((BigDecimal)r.get("released_qty")).subtract((BigDecimal)r.get("consumed_qty"));
        if(remaining.compareTo(quantity)<0) throw new DomainException(409,"Reservation quantity changed");
        long product=((Number)r.get("product_id")).longValue(), warehouse=((Number)r.get("warehouse_id")).longValue();
        Long serial=(Long)r.get("serial_id"),lot=(Long)r.get("lot_id");
        if(serial!=null && quantity.compareTo(BigDecimal.ONE)!=0) throw new DomainException(400,"Serial quantity must be one");
        BigDecimal cost=BigDecimal.ZERO.setScale(2);
        if(consume) {
            if(date==null) throw new DomainException(400,"Shipment date required");
            var pool=db.queryForList("select quantity,value,last_posting_date,pool_sequence from inventory_valuation_pools where workspace_id=? and entity_id=? and product_id=? for update",w,e,product);
            if(pool.size()!=1) throw new DomainException(409,"Stock pool unavailable");
            var p=pool.get(0); BigDecimal q=(BigDecimal)p.get("quantity"),v=(BigDecimal)p.get("value");
            LocalDate watermark=p.get("last_posting_date") instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate)p.get("last_posting_date");
            if(q.compareTo(quantity)<0 || date.isBefore(watermark)) throw new DomainException(409,"Insufficient stock or posting before stock watermark");
            cost=allocatedCost;
            if(cost==null || cost.signum()<0 || cost.compareTo(v)>0) throw new DomainException(409,"Invalid allocated stock value");
            long seq=Math.addExact(((Number)p.get("pool_sequence")).longValue(),1);
            if(lot!=null && !Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from inventory_lots where workspace_id=? and entity_id=? and id=? and (expires_on is null or expires_on>? and expires_on>(select business_date from scenario_workspaces where id=?)))",Boolean.class,w,e,lot,date,w)))
                throw new DomainException(409,"Lot expired");
            if(serial!=null && db.update("update instrument_serials set status='ISSUED' where workspace_id=? and entity_id=? and id=? and product_id=? and warehouse_id=? and status='IN_STOCK'",w,e,serial,product,warehouse)!=1)
                throw new DomainException(409,"Serial unavailable");
            db.update("update inventory_valuation_pools set quantity=quantity-?,value=value-?,last_posting_date=?,pool_sequence=? where workspace_id=? and entity_id=? and product_id=?",quantity,cost,date,seq,w,e,product);
            db.update("insert into stock_movements(workspace_id,entity_id,product_id,warehouse_id,source_type,source_id,pool_sequence,quantity_delta,value_delta,posting_date,source_line_key,serial_id,lot_id,created_by) values (?,?,?,?,'SHIPMENT',?,?,?,?,?,?,?,?,?)",w,e,product,warehouse,Long.toString(shipment),seq,quantity.negate(),cost.negate(),date,eventKey,serial,lot,scope.actor().id());
        }
        String balance=consume?"on_hand=on_hand-?,reserved=reserved-?":"reserved=reserved-?";
        int changed=consume?db.update("update inventory_balances set "+balance+",version=version+1 where workspace_id=? and entity_id=? and product_id=? and warehouse_id=? and reserved>=? and on_hand>=?",quantity,quantity,w,e,product,warehouse,quantity,quantity):
            db.update("update inventory_balances set "+balance+",version=version+1 where workspace_id=? and entity_id=? and product_id=? and warehouse_id=? and reserved>=?",quantity,w,e,product,warehouse,quantity);
        if(changed!=1) throw new DomainException(409,"Reservation balance changed");
        if(lot!=null) {
            changed=consume?db.update("update inventory_lot_balances set quantity=quantity-?,reserved=reserved-?,version=version+1 where workspace_id=? and entity_id=? and lot_id=? and warehouse_id=? and reserved>=? and quantity>=?",quantity,quantity,w,e,lot,warehouse,quantity,quantity):
                db.update("update inventory_lot_balances set reserved=reserved-?,version=version+1 where workspace_id=? and entity_id=? and lot_id=? and warehouse_id=? and reserved>=?",quantity,w,e,lot,warehouse,quantity);
            if(changed!=1) throw new DomainException(409,"Lot reservation balance changed");
        }
        db.update("update inventory_reservations set "+(consume?"consumed_qty":"released_qty")+"="+(consume?"consumed_qty":"released_qty")+"+?,version=version+1 where workspace_id=? and entity_id=? and id=?",quantity,w,e,reservation);
        db.update("insert into inventory_reservation_events(workspace_id,entity_id,reservation_id,kind,quantity,intent_key,intent_hash,created_by) values (?,?,?,?,?,?,?,?)",w,e,reservation,consume?"CONSUME":"RELEASE",quantity,eventKey,hash,scope.actor().id());
        return cost;
    }

    private static boolean equal(BigDecimal a,BigDecimal b) { return a!=null && b!=null && a.compareTo(b)==0; }
}
