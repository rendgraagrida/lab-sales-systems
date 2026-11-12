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
public class SupplierService {
    public record Supplier(long id,String code,String name,String email,boolean active,long version) {}
    public record Account(long id,String erpCode,Long termsId,boolean active,long version) {}
    private static final String[] READ={"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE"};
    private static final String[] WRITE={"OPERATIONS","FINANCE"};
    private static final String VISIBLE="(EXISTS (SELECT 1 FROM supplier_entity_accounts a JOIN entity_memberships em ON em.workspace_id=a.workspace_id AND em.entity_id=a.entity_id AND em.active JOIN legal_entities e ON e.workspace_id=em.workspace_id AND e.id=em.entity_id AND e.active WHERE a.workspace_id=s.workspace_id AND a.supplier_id=s.id AND a.active AND em.user_id=?) OR (s.created_by=? AND NOT EXISTS (SELECT 1 FROM supplier_entity_accounts a WHERE a.workspace_id=s.workspace_id AND a.supplier_id=s.id)))";
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit;
    public SupplierService(JdbcTemplate db,AccessControl access,AuditLog audit){this.db=db;this.access=access;this.audit=audit;}
    private static Supplier map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Supplier(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5),r.getLong(6));}
    public List<Supplier> list(long ws,long entity,String query){var s=access.requireContext(ws,entity,false,READ);String q=query==null?"":query.trim();if(q.length()>200)throw new DomainException(400,"Search is too long");return db.query("SELECT s.id,s.code,s.name,s.email,s.active,s.version FROM suppliers s WHERE s.workspace_id=? AND "+VISIBLE+" AND (position(lower(?) in lower(s.code))>0 OR position(lower(?) in lower(s.name))>0) ORDER BY s.code,s.id",SupplierService::map,ws,s.actor().id(),s.actor().id(),q,q);}
    public Supplier get(long ws,long entity,long id){var s=access.requireContext(ws,entity,false,READ);return visible(ws,s.actor().id(),id,false);}
    private Supplier visible(long ws,long actor,long id,boolean lock){var rows=db.query("SELECT s.id,s.code,s.name,s.email,s.active,s.version FROM suppliers s WHERE s.workspace_id=? AND "+VISIBLE+" AND s.id=?"+(lock?" FOR UPDATE OF s":""),SupplierService::map,ws,actor,actor,id);if(rows.isEmpty())throw new DomainException(404,"Supplier not found");return rows.get(0);}
    @Transactional public long save(long ws,long entity,Long id,Long version,String code,String name,String email){
        var s=access.requireContext(ws,entity,true,WRITE);code=text(code, 80, true, "Code", "code");name=text(name, 200, true, "Name", "name");email=text(email, 200, false, "Email", "email");
        if(id==null && version!=null)throw new DomainException(400,"Unexpected version");Supplier old=id==null?null:visible(ws,s.actor().id(),id,true);
        if(old!=null && (version==null || old.version()!=version))throw new DomainException(409,"Supplier has changed");
        try{long result,revision;if(old==null){result=db.queryForObject("INSERT INTO suppliers(workspace_id,created_by,code,name,email) VALUES (?,?,?,?,?) RETURNING id",Long.class,ws,s.actor().id(),code,name,email);revision=1;}else{db.update("UPDATE suppliers SET code=?,name=?,email=?,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",code,name,email,ws,id);result=id;revision=old.version()+1;}audit.record(s.actor(),ws,entity,old==null?"CREATE":"UPDATE","suppliers",Long.toString(result),revision,"SUCCESS","supplier fields");return result;}catch(DataIntegrityViolationException e){throw new DomainException(409,"Supplier conflicts with existing data");}
    }
    @Transactional public void deactivate(long ws,long entity,long id,long version){var s=access.requireContext(ws,entity,true,WRITE);Supplier old=visible(ws,s.actor().id(),id,true);if(old.version()!=version)throw new DomainException(409,"Supplier has changed");db.update("UPDATE suppliers SET active=false,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",ws,id);audit.record(s.actor(),ws,entity,"DEACTIVATE","suppliers",Long.toString(id),version+1,"SUCCESS","active");}
    public boolean isFinance(long ws,long entity){return access.requireContext(ws,entity,false,READ).has("FINANCE");}
    public Account account(long ws,long entity,long supplier){var s=access.requireContext(ws,entity,false,"FINANCE");visible(ws,s.actor().id(),supplier,false);return findAccount(ws,entity,supplier,false);}
    private Account findAccount(long ws,long entity,long supplier,boolean lock){var rows=db.query("SELECT id,erp_code,terms_id,active,version FROM supplier_entity_accounts WHERE workspace_id=? AND entity_id=? AND supplier_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Account(r.getLong(1),r.getString(2),(Long)r.getObject(3),r.getBoolean(4),r.getLong(5)),ws,entity,supplier);return rows.isEmpty()?null:rows.get(0);}
    @Transactional public void assign(long ws,long entity,long supplier,long version,String erp){var s=access.requireContext(ws,entity,true,WRITE);Supplier row=visible(ws,s.actor().id(),supplier,true);if(row.version()!=version)throw new DomainException(409,"Supplier has changed");erp=text(erp, 80, true, "ERP code", "erpCode");if(findAccount(ws,entity,supplier,true)!=null)throw new DomainException(409,"Account already exists");try{long id=db.queryForObject("INSERT INTO supplier_entity_accounts(workspace_id,entity_id,supplier_id,erp_code) VALUES (?,?,?,?) RETURNING id",Long.class,ws,entity,supplier,erp);audit.record(s.actor(),ws,entity,"CREATE","supplier_entity_accounts",Long.toString(id),1,"SUCCESS","entity assignment");}catch(DataIntegrityViolationException e){throw new DomainException(409,"Account conflicts with existing data");}}
    @Transactional public void saveAccount(long ws,long entity,long supplier,long supplierVersion,Long version,String erp,Long terms,Long termsVersion,boolean active){
        var s=access.requireContext(ws,entity,true,"FINANCE");Supplier row=visible(ws,s.actor().id(),supplier,true);if(row.version()!=supplierVersion)throw new DomainException(409,"Supplier has changed");erp=text(erp, 80, true, "ERP code", "erpCode");
        if(terms!=null){var found=db.query("SELECT version,active FROM payment_terms WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,entity,terms);if(found.isEmpty())throw new DomainException(404,"Payment terms not found");if(termsVersion==null || found.get(0)[0]!=termsVersion)throw new DomainException(409,"Payment terms have changed");if(found.get(0)[1]==0)throw new DomainException(400,"Payment terms are inactive","terms");}else if(termsVersion!=null)throw new DomainException(400,"Unexpected terms version","terms");
        Account old=findAccount(ws,entity,supplier,true);if(old==null?version!=null:version==null||old.version()!=version)throw new DomainException(409,"Account has changed");
        try{long id,revision;if(old==null){id=db.queryForObject("INSERT INTO supplier_entity_accounts(workspace_id,entity_id,supplier_id,erp_code,terms_id,active) VALUES (?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,supplier,erp,terms,active);revision=1;}else{id=old.id();revision=old.version()+1;db.update("UPDATE supplier_entity_accounts SET erp_code=?,terms_id=?,active=?,version=version+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",erp,terms,active,ws,entity,id);}audit.record(s.actor(),ws,entity,old==null?"CREATE":"UPDATE","supplier_entity_accounts",Long.toString(id),revision,"SUCCESS","erp_code terms_id active");}catch(DataIntegrityViolationException e){throw new DomainException(409,"Account conflicts with existing data");}
    }
    private static String text(String value,int max,boolean required,String label,String field){String v=value==null?"":value.trim();if(required&&v.isBlank())throw new DomainException(400,label+" is required",field);if(v.length()>max)throw new DomainException(400,label+" is too long",field);return v;}
}
