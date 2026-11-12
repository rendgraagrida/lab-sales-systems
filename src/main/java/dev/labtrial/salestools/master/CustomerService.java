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
public class CustomerService {
    private static final String[] READ = {"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE"};
    private static final String[] WRITE = {"INSIDE_SALES","SALES_MANAGER"};
    public record Input(String code, String name, String segment, String territory, String domain, Long parentId, Long parentVersion) {}
    private final CustomerRepository customers;
    private final AccessControl access;
    private final JdbcTemplate db;
    private final AuditLog audit;
    public CustomerService(CustomerRepository customers, AccessControl access, JdbcTemplate db, AuditLog audit) {
        this.customers=customers; this.access=access; this.db=db; this.audit=audit;
    }
    public List<CustomerRepository.Customer> list(long ws, long entity, String query) {
        var scope=access.requireContext(ws,entity,false,READ);
        String q=query == null ? "" : query.trim();
        if(q.length()>200) throw new DomainException(400,"Search is too long");
        return customers.list(ws,scope.actor().id(),q);
    }
    public CustomerRepository.Customer get(long ws, long entity, long id) {
        var scope=access.requireContext(ws,entity,false,READ);
        return visible(ws,scope.actor().id(),id,false);
    }
    private CustomerRepository.Customer visible(long ws,long actor,long id,boolean lock) {
        var found=customers.find(ws,actor,id,lock);
        if(found.isEmpty()) throw new DomainException(404,"Customer not found");
        return found.get(0);
    }
    @Transactional
    public long save(long ws,long entity,Long id,Long version,Input input) {
        var scope=access.requireContext(ws,entity,true,WRITE);
        db.queryForObject("SELECT id FROM scenario_workspaces WHERE id=? FOR UPDATE",Long.class,ws);
        String code=text(input.code(),80,true,"Code","code"),name=text(input.name(),200,true,"Name","name"),segment=text(input.segment(),80,false,"Segment","segment"),territory=text(input.territory(),80,false,"Territory","territory"),domain=text(input.domain(),200,false,"Domain","domain");
        if(id == null && version != null) throw new DomainException(400,"Unexpected version");
        CustomerRepository.Customer current=id == null ? null : visible(ws,scope.actor().id(),id,true);
        if(current != null && (version == null || current.version()!=version)) throw new DomainException(409,"Customer has changed");
        if(input.parentId()!=null) {
            var parent=visible(ws,scope.actor().id(),input.parentId(),true);
            if(input.parentVersion() == null || parent.version() != input.parentVersion()) throw new DomainException(409,"Parent customer has changed");
            if(!parent.active()) throw new DomainException(400,"Parent customer is inactive","parent");
            if(id!=null && customers.createsCycle(ws,id,input.parentId())) throw new DomainException(400,"Customer parent cycle is not allowed","parent");
        } else if(input.parentVersion() != null) {
            throw new DomainException(400,"Unexpected parent version","parent");
        }
        try {
            long result;
            long revision;
            if(id==null) { result=customers.insert(ws,scope.actor().id(),code,name,segment,territory,domain,input.parentId()); revision=1; }
            else { customers.update(ws,id,code,name,segment,territory,domain,input.parentId()); result=id; revision=current.version()+1; }
            audit.record(scope.actor(),ws,entity,id==null ? "CREATE" : "UPDATE","customers",Long.toString(result),revision,"SUCCESS","customer fields");
            return result;
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Customer conflicts with existing data"); }
    }
    @Transactional
    public void deactivate(long ws,long entity,long id,long version) {
        var scope=access.requireContext(ws,entity,true,WRITE);
        var current=visible(ws,scope.actor().id(),id,true);
        if(current.version()!=version) throw new DomainException(409,"Customer has changed");
        customers.deactivate(ws,id);
        audit.record(scope.actor(),ws,entity,"DEACTIVATE","customers",Long.toString(id),version+1,"SUCCESS","active");
    }
    private static String text(String value,int max,boolean required,String label,String field) {
        String result=value==null ? "" : value.trim();
        if(required && result.isBlank()) throw new DomainException(400,label+" is required",field);
        if(result.length()>max) throw new DomainException(400,label+" is too long",field);
        return result;
    }
}
