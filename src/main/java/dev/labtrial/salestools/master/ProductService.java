package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    public record Product(long id,String sku,String name,String category,String productLine,long uomId,String tracking,String status,boolean active,long version) {}
    public record Setting(long id,boolean active,long version) {}
    public record Input(String sku,String name,String category,String productLine,long uomId,long uomVersion,String tracking) {}
    private static final String[] READ={"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE"};
    private static final String[] WRITE={"OPERATIONS","SALES_MANAGER"};
    private static final String VISIBLE="(EXISTS (SELECT 1 FROM product_entity_settings a JOIN entity_memberships em ON em.workspace_id=a.workspace_id AND em.entity_id=a.entity_id AND em.active JOIN legal_entities e ON e.workspace_id=em.workspace_id AND e.id=em.entity_id AND e.active WHERE a.workspace_id=p.workspace_id AND a.product_id=p.id AND a.active AND em.user_id=?) OR (p.status='DRAFT' AND p.created_by=? AND NOT EXISTS (SELECT 1 FROM product_entity_settings a WHERE a.workspace_id=p.workspace_id AND a.product_id=p.id)))";
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit;
    public ProductService(JdbcTemplate db,AccessControl access,AuditLog audit){this.db=db;this.access=access;this.audit=audit;}
    private static Product map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Product(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getString(7),r.getString(8),r.getBoolean(9),r.getLong(10));}
    public List<Product> list(long ws,long entity,String query){var s=access.requireContext(ws,entity,false,READ);String q=query==null?"":query.trim();if(q.length()>200)throw new DomainException(400,"Search is too long");return db.query("SELECT p.id,p.sku,p.name,p.category,p.product_line,p.uom_id,p.tracking,p.status,p.active,p.version FROM products p WHERE p.workspace_id=? AND "+VISIBLE+" AND (position(lower(?) in lower(p.sku))>0 OR position(lower(?) in lower(p.name))>0) ORDER BY p.sku,p.id",ProductService::map,ws,s.actor().id(),s.actor().id(),q,q);}
    public List<Product> priceable(long ws,long entity){access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER","FINANCE");return db.query("SELECT p.id,p.sku,p.name,p.category,p.product_line,p.uom_id,p.tracking,p.status,p.active,p.version FROM products p JOIN product_entity_settings s ON s.workspace_id=p.workspace_id AND s.product_id=p.id AND s.entity_id=? AND s.active WHERE p.workspace_id=? AND p.active ORDER BY p.sku,p.id",ProductService::map,entity,ws);}
    public Product get(long ws,long entity,long id){var s=access.requireContext(ws,entity,false,READ);return visible(ws,s.actor().id(),id,false);}
    private Product visible(long ws,long actor,long id,boolean lock){var rows=db.query("SELECT p.id,p.sku,p.name,p.category,p.product_line,p.uom_id,p.tracking,p.status,p.active,p.version FROM products p WHERE p.workspace_id=? AND "+VISIBLE+" AND p.id=?"+(lock?" FOR UPDATE OF p":""),ProductService::map,ws,actor,actor,id);if(rows.isEmpty())throw new DomainException(404,"Product not found");return rows.get(0);}
    @Transactional public long save(long ws,long entity,Long id,Long version,Input input){
        var s=access.requireContext(ws,entity,true,WRITE);String sku=text(input.sku(), 80, "SKU", "sku"),name=text(input.name(), 200, "Name", "name");
        if(input.category()==null || !List.of("LAB_UNIT","LAB_SUPPLY","REPLACEMENT_PART","SERVICE_PLAN","IMPLEMENTATION_SERVICE").contains(input.category()))throw new DomainException(400,"Invalid category","category");
        if(input.productLine()==null || !List.of("TRANSFER","CLEANUP","PREPARATION","RINSE","WORKCELL","CROSS_LINE").contains(input.productLine()))throw new DomainException(400,"Invalid product line","productLine");
        if(input.tracking()==null || !List.of("NONE","SERIAL","LOT","LOT_EXPIRY").contains(input.tracking()))throw new DomainException(400,"Invalid tracking","tracking");
        if(id==null&&version!=null)throw new DomainException(400,"Unexpected version");Product old=id==null?null:visible(ws,s.actor().id(),id,true);
        if(old!=null && (version==null||old.version()!=version))throw new DomainException(409,"Product has changed");
        if(old!=null&&!"DRAFT".equals(old.status()))throw new DomainException(409,"Only draft products can be edited");
        var units=db.query("SELECT version,active FROM uoms WHERE workspace_id=? AND id=? FOR UPDATE",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,input.uomId());
        if(units.isEmpty())throw new DomainException(404,"Unit not found");if(units.get(0)[0]!=input.uomVersion())throw new DomainException(409,"Unit has changed");if(units.get(0)[1]==0)throw new DomainException(400,"Unit is inactive","unit");
        try {
            long result,revision;
            if(old==null) {
                result=db.queryForObject("INSERT INTO products(workspace_id,created_by,sku,name,category,product_line,uom_id,tracking) VALUES (?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,s.actor().id(),sku,name,input.category(),input.productLine(),input.uomId(),input.tracking());
                revision=1;
            } else {
                db.update("UPDATE products SET sku=?,name=?,category=?,product_line=?,uom_id=?,tracking=?,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",sku,name,input.category(),input.productLine(),input.uomId(),input.tracking(),ws,id);
                result=id; revision=old.version()+1;
            }
            db.update("insert into product_contributors(workspace_id,product_id,user_id) values (?,?,?) on conflict do nothing",ws,result,s.actor().id());
            audit.record(s.actor(),ws,entity,old==null?"CREATE":"UPDATE","products",Long.toString(result),revision,"SUCCESS","product fields");
            return result;
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Product conflicts with existing data or immutable stock history"); }
    }
    @Transactional public void deactivate(long ws,long entity,long id,long version){var s=access.requireContext(ws,entity,true,WRITE);Product old=visible(ws,s.actor().id(),id,true);if(old.version()!=version)throw new DomainException(409,"Product has changed");db.update("UPDATE products SET active=false,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",ws,id);audit.record(s.actor(),ws,entity,"DEACTIVATE","products",Long.toString(id),version+1,"SUCCESS","active");}
    public Setting setting(long ws,long entity,long product){var s=access.requireContext(ws,entity,false,READ);visible(ws,s.actor().id(),product,false);return findSetting(ws,entity,product,false);}
    private Setting findSetting(long ws,long entity,long product,boolean lock){var rows=db.query("SELECT id,active,version FROM product_entity_settings WHERE workspace_id=? AND entity_id=? AND product_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Setting(r.getLong(1),r.getBoolean(2),r.getLong(3)),ws,entity,product);return rows.isEmpty()?null:rows.get(0);}
    @Transactional public void assign(long ws,long entity,long product,long productVersion,Long version,boolean active){var s=access.requireContext(ws,entity,true,WRITE);Product row=visible(ws,s.actor().id(),product,true);if(row.version()!=productVersion)throw new DomainException(409,"Product has changed");Setting old=findSetting(ws,entity,product,true);if(old==null?version!=null:version==null||old.version()!=version)throw new DomainException(409,"Product setting has changed");try{long id,revision;if(old==null){id=db.queryForObject("INSERT INTO product_entity_settings(workspace_id,entity_id,product_id,active) VALUES (?,?,?,?) RETURNING id",Long.class,ws,entity,product,active);revision=1;}else{id=old.id();revision=old.version()+1;db.update("UPDATE product_entity_settings SET active=?,version=version+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",active,ws,entity,id);}audit.record(s.actor(),ws,entity,old==null?"CREATE":"UPDATE","product_entity_settings",Long.toString(id),revision,"SUCCESS","active");}catch(DataIntegrityViolationException e){throw new DomainException(409,"Product setting conflicts with existing data");}}
    @Transactional
    public void publish(long ws,long entity,long product,long version) {
        var scope=access.requireContext(ws,entity,true,"SALES_MANAGER");
        Product source=visible(ws,scope.actor().id(),product,true);
        if(source.version()!=version) throw new DomainException(409,"Product has changed");
        if(!source.active()||!"DRAFT".equals(source.status())) throw new DomainException(409,"Only active draft products can be published");
        Boolean contributor=db.queryForObject("select exists(select 1 from product_contributors where workspace_id=? and product_id=? and user_id=?)",Boolean.class,ws,product,scope.actor().id());
        if(Boolean.TRUE.equals(contributor)) throw new DomainException(403,"A creator or editor cannot publish this product");
        Setting setting=findSetting(ws,entity,product,true);
        if(setting==null||!setting.active()) throw new DomainException(409,"Assign the product to this entity before publication");
        Integer unit=db.queryForObject("select count(*) from uoms where workspace_id=? and id=? and active",Integer.class,ws,source.uomId());
        if(unit==null||unit!=1) throw new DomainException(409,"The product unit is inactive");
        db.update("insert into product_publications(workspace_id,entity_id,product_id,product_version,approved_by) values (?,?,?,?,?)",ws,entity,product,version,scope.actor().id());
        db.update("update products set status='ACTIVE',version=version+1,updated_at=now() where workspace_id=? and id=? and version=?",ws,product,version);
        audit.record(scope.actor(),ws,entity,"PUBLISH","products",Long.toString(product),version+1,"SUCCESS","independent product publication");
    }
    private static String text(String v,int max,String label,String field){if(v==null||v.isBlank()||v.trim().length()>max)throw new DomainException(400,label+" is required or too long",field);return v.trim();}
}
