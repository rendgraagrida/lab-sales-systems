package dev.labtrial.salestools.order;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoodsConfigurationService {
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit;
    public GoodsConfigurationService(JdbcTemplate db,AccessControl access,AuditLog audit) { this.db=db; this.access=access; this.audit=audit; }
    public List<Map<String,Object>> products(long w,long e) { access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER"); return db.queryForList("select p.id,p.version,p.sku,p.name,p.category from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active where p.workspace_id=? and p.active and p.category in ('LAB_UNIT','LAB_SUPPLY','REPLACEMENT_PART') order by p.sku",e,w); }
    public List<Map<String,Object>> compatibilities(long w,long e) { access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER"); return db.queryForList("select c.*,p.sku device_sku,q.sku component_sku from product_compatibilities c join products p on p.workspace_id=c.workspace_id and p.id=c.device_id join products q on q.workspace_id=c.workspace_id and q.id=c.component_id where c.workspace_id=? and c.entity_id=? order by c.id",w,e); }
    public List<Map<String,Object>> bundles(long w,long e) { access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER"); return db.queryForList("select c.*,p.sku bundle_sku,q.sku component_sku from bundle_components c join products p on p.workspace_id=c.workspace_id and p.id=c.bundle_id join products q on q.workspace_id=c.workspace_id and q.id=c.component_id where c.workspace_id=? and c.entity_id=? order by c.id",w,e); }
    public List<Map<String,Object>> addresses(long w,long e) { access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER"); return db.queryForList("select a.id,a.address_role,a.customer_account_id,v.revision,v.label,c.erp_code from party_addresses a join customer_entity_accounts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.customer_account_id join lateral (select revision,label from party_address_versions v where v.workspace_id=a.workspace_id and v.entity_id=a.entity_id and v.address_id=a.id order by revision desc limit 1) v on true where a.workspace_id=? and a.entity_id=? order by a.id desc",w,e); }
    public List<Map<String,Object>> addressHistory(long w,long e) { access.requireContext(w,e,false,"INSIDE_SALES","SALES_MANAGER"); return db.queryForList("select a.id,a.address_role,c.erp_code,v.revision,v.label,v.valid_from,v.valid_to,v.created_at from party_addresses a join customer_entity_accounts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.customer_account_id join party_address_versions v on v.workspace_id=a.workspace_id and v.entity_id=a.entity_id and v.address_id=a.id where a.workspace_id=? and a.entity_id=? order by a.id,v.revision",w,e); }
    private void product(long w,long e,long id,long version,boolean device) {
        Integer n=db.queryForObject("select count(*) from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active where p.workspace_id=? and p.id=? and p.version=? and p.active and p.category in ('LAB_UNIT','LAB_SUPPLY','REPLACEMENT_PART') and (not ? or p.category='LAB_UNIT')",Integer.class,e,w,id,version,device);
        if(n==null || n!=1) throw new DomainException(409,"Product reference changed or is unavailable");
    }
    @Transactional public void compatibility(long w,long e,long device,long deviceVersion,long component,long componentVersion) {
        var scope=access.requireContext(w,e,true,"SALES_MANAGER"); product(w,e,device,deviceVersion,true); product(w,e,component,componentVersion,false);
        if(device==component) throw new DomainException(400,"Product cannot refer to itself","second");
        Integer old=db.queryForObject("select count(*) from product_compatibilities where workspace_id=? and entity_id=? and device_id=? and component_id=?",Integer.class,w,e,device,component); if(old!=null && old>0) throw new DomainException(409,"Compatibility already exists");
        long id=db.queryForObject("insert into product_compatibilities(workspace_id,entity_id,device_id,component_id,created_by) values (?,?,?,?,?) returning id",Long.class,w,e,device,component,scope.actor().id()); audit.record(scope.actor(),w,e,"CREATE","product_compatibilities",Long.toString(id),1,"SUCCESS","goods compatibility");
    }
    @Transactional public void bundle(long w,long e,long bundle,long bundleVersion,long component,long componentVersion,BigDecimal quantity) {
        var scope=access.requireContext(w,e,true,"SALES_MANAGER"); product(w,e,bundle,bundleVersion,false); product(w,e,component,componentVersion,false);
        if(bundle==component) throw new DomainException(400,"Product cannot refer to itself","second");
        if(quantity==null || quantity.signum()<=0 || quantity.scale()>6 || quantity.scale() < -18 || (long)quantity.precision()-quantity.scale()>12) throw new DomainException(400,"Invalid bundle quantity","quantity");
        Boolean cycle=db.queryForObject("with recursive descendants(id) as (select component_id from bundle_components where workspace_id=? and entity_id=? and bundle_id=? union select c.component_id from bundle_components c join descendants d on d.id=c.bundle_id where c.workspace_id=? and c.entity_id=?) select exists(select 1 from descendants where id=?)",Boolean.class,w,e,component,w,e,bundle);
        if(Boolean.TRUE.equals(cycle)) throw new DomainException(409,"Bundle would be cyclic");
        Integer old=db.queryForObject("select count(*) from bundle_components where workspace_id=? and entity_id=? and bundle_id=? and component_id=?",Integer.class,w,e,bundle,component); if(old!=null && old>0) throw new DomainException(409,"Bundle component already exists");
        long id=db.queryForObject("insert into bundle_components(workspace_id,entity_id,bundle_id,component_id,quantity,created_by) values (?,?,?,?,?,?) returning id",Long.class,w,e,bundle,component,quantity,scope.actor().id()); audit.record(scope.actor(),w,e,"CREATE","bundle_components",Long.toString(id),1,"SUCCESS","goods bundle");
    }
    @Transactional public void address(long w,long e,long customer,long customerVersion,String role,String label,LocalDate validFrom,LocalDate validTo) {
        var scope=access.requireContext(w,e,true,"INSIDE_SALES","SALES_MANAGER");
        Integer n=db.queryForObject("select count(*) from customer_entity_accounts a join customers c on c.workspace_id=a.workspace_id and c.id=a.customer_id and c.active where a.workspace_id=? and a.entity_id=? and a.id=? and a.version=? and a.active",Integer.class,w,e,customer,customerVersion);
        if(n==null || n!=1) throw new DomainException(409,"Customer account changed");
        if(role==null || !List.of("BILLING","SHIPPING").contains(role)) throw new DomainException(400,"Invalid address role","role");
        if(label==null || label.isBlank() || label.trim().length()>500) throw new DomainException(400,"Invalid address","label");
        if(validFrom==null || (validTo!=null && validTo.isBefore(validFrom))) throw new DomainException(400,"Invalid address validity","validFrom","validTo");
        long id=db.queryForObject("insert into party_addresses(workspace_id,entity_id,customer_account_id,address_role,created_by) values (?,?,?,?,?) returning id",Long.class,w,e,customer,role,scope.actor().id());
        db.update("insert into party_address_versions(workspace_id,entity_id,address_id,revision,label,valid_from,valid_to,created_by) values (?,?,?,1,?,?,?,?)",w,e,id,label.trim(),validFrom,validTo,scope.actor().id());
        audit.record(scope.actor(),w,e,"CREATE","party_addresses",Long.toString(id),1,"SUCCESS","synthetic address");
    }
    @Transactional public void reviseAddress(long w,long e,long address,long version,String label,LocalDate validFrom,LocalDate validTo) {
        var scope=access.requireContext(w,e,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=db.query("select v.revision from party_addresses a join customer_entity_accounts c on c.workspace_id=a.workspace_id and c.entity_id=a.entity_id and c.id=a.customer_account_id and c.active join lateral (select revision from party_address_versions v where v.workspace_id=a.workspace_id and v.entity_id=a.entity_id and v.address_id=a.id order by revision desc limit 1) v on true where a.workspace_id=? and a.entity_id=? and a.id=? for update of a",(r,n)->r.getLong(1),w,e,address);
        if(rows.isEmpty()) throw new DomainException(404,"Address not found");
        if(rows.get(0)!=version) throw new DomainException(409,"Address changed");
        if(label==null || label.isBlank() || label.trim().length()>500) throw new DomainException(400,"Invalid address","label");
        if(validFrom==null || (validTo!=null && validTo.isBefore(validFrom))) throw new DomainException(400,"Invalid address validity","validFrom","validTo");
        db.update("insert into party_address_versions(workspace_id,entity_id,address_id,revision,label,valid_from,valid_to,created_by) values (?,?,?,?,?,?,?,?)",w,e,address,version+1,label.trim(),validFrom,validTo,scope.actor().id());
        audit.record(scope.actor(),w,e,"REVISE","party_addresses",Long.toString(address),version+1,"SUCCESS","synthetic address");
    }
}
