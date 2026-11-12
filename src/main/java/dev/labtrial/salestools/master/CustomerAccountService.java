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
public class CustomerAccountService {
    public record Account(long id, String erpCode, String creditStatus, Long termsId, boolean active, long version) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    private final CustomerRepository customers;
    private final AuditLog audit;
    public CustomerAccountService(JdbcTemplate db, AccessControl access, CustomerRepository customers, AuditLog audit) {
        this.db=db; this.access=access; this.customers=customers; this.audit=audit;
    }
    public boolean canRead(long ws,long entity) {
        return access.requireContext(ws,entity,false,"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE").has("FINANCE");
    }
    public Account get(long ws,long entity,long customer) {
        var scope=access.requireContext(ws,entity,false,"FINANCE");
        if(customers.find(ws,scope.actor().id(),customer,false).isEmpty()) throw new DomainException(404,"Customer not found");
        return find(ws,entity,customer,false);
    }
    private Account find(long ws,long entity,long customer,boolean lock) {
        List<Account> rows=db.query("SELECT id,erp_code,credit_status,terms_id,active,version FROM customer_entity_accounts WHERE workspace_id=? AND entity_id=? AND customer_id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Account(r.getLong(1),r.getString(2),r.getString(3),(Long)r.getObject(4),r.getBoolean(5),r.getLong(6)),ws,entity,customer);
        return rows.isEmpty()?null:rows.get(0);
    }
    @Transactional
    public void assign(long ws,long entity,long customer,long customerVersion,String erp) {
        var scope=access.requireContext(ws,entity,true,"INSIDE_SALES","SALES_MANAGER");
        var rows=customers.find(ws,scope.actor().id(),customer,true);
        if(rows.isEmpty()) throw new DomainException(404,"Customer not found");
        if(rows.get(0).version()!=customerVersion) throw new DomainException(409,"Customer has changed");
        if(erp==null || erp.isBlank() || erp.trim().length()>80) throw new DomainException(400,"ERP code is required and must fit 80 characters","erpCode");
        if(find(ws,entity,customer,true)!=null) throw new DomainException(409,"Account already exists");
        try {
            long id=db.queryForObject("INSERT INTO customer_entity_accounts(workspace_id,entity_id,customer_id,erp_code) VALUES (?,?,?,?) RETURNING id",Long.class,ws,entity,customer,erp.trim());
            audit.record(scope.actor(),ws,entity,"CREATE","customer_entity_accounts",Long.toString(id),1,"SUCCESS","entity assignment");
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Account conflicts with existing data"); }
    }
    @Transactional
    public void save(long ws,long entity,long customer,long customerVersion,Long accountVersion,String erp,String credit,Long termsId,Long termsVersion,boolean active) {
        var scope=access.requireContext(ws,entity,true,"FINANCE");
        var rows=customers.find(ws,scope.actor().id(),customer,true);
        if(rows.isEmpty()) throw new DomainException(404,"Customer not found");
        if(rows.get(0).version()!=customerVersion) throw new DomainException(409,"Customer has changed");
        if(erp==null || erp.isBlank() || erp.trim().length()>80) throw new DomainException(400,"ERP code is required and must fit 80 characters","erpCode");
        if(credit==null || !List.of("PENDING","APPROVED","BLOCKED").contains(credit)) throw new DomainException(400,"Invalid credit status","credit");
        if(termsId!=null) {
            var terms=db.query("SELECT version,active FROM payment_terms WHERE workspace_id=? AND entity_id=? AND id=? FOR UPDATE",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},ws,entity,termsId);
            if(terms.isEmpty()) throw new DomainException(404,"Payment terms not found");
            if(termsVersion==null || terms.get(0)[0]!=termsVersion) throw new DomainException(409,"Payment terms have changed");
            if(terms.get(0)[1]==0) throw new DomainException(400,"Payment terms are inactive","terms");
        } else if(termsVersion!=null) throw new DomainException(400,"Unexpected terms version","terms");
        Account current=find(ws,entity,customer,true);
        if(current==null && accountVersion!=null) throw new DomainException(409,"Account has changed");
        if(current!=null && (accountVersion==null || current.version()!=accountVersion)) throw new DomainException(409,"Account has changed");
        try {
            long id,revision;
            if(current==null) {
                id=db.queryForObject("INSERT INTO customer_entity_accounts(workspace_id,entity_id,customer_id,erp_code,credit_status,terms_id,active) VALUES (?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,customer,erp.trim(),credit,termsId,active); revision=1;
            } else {
                id=current.id(); revision=current.version()+1;
                db.update("UPDATE customer_entity_accounts SET erp_code=?,credit_status=?,terms_id=?,active=?,version=version+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",erp.trim(),credit,termsId,active,ws,entity,id);
            }
            audit.record(scope.actor(),ws,entity,current==null?"CREATE":"UPDATE","customer_entity_accounts",Long.toString(id),revision,"SUCCESS","erp_code credit_status terms_id active");
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Account conflicts with existing data"); }
    }
}
