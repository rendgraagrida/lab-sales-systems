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
public class ContactService {
    public record Contact(long id,String code,String firstName,String lastName,String email,String lifecycle,boolean active,long version) {}
    public record Link(long customerId,long contactId,String role,boolean active,long version) {}
    private static final String[] READ={"INSIDE_SALES","SALES_MANAGER","OPERATIONS","FINANCE"};
    private static final String[] WRITE={"INSIDE_SALES","SALES_MANAGER"};
    private static final String VISIBLE="(EXISTS (SELECT 1 FROM customer_contact_links l JOIN customer_entity_accounts a ON a.workspace_id=l.workspace_id AND a.customer_id=l.customer_id AND a.active JOIN entity_memberships em ON em.workspace_id=a.workspace_id AND em.entity_id=a.entity_id AND em.active JOIN legal_entities e ON e.workspace_id=em.workspace_id AND e.id=em.entity_id AND e.active WHERE l.workspace_id=c.workspace_id AND l.contact_id=c.id AND l.active AND em.user_id=?) OR (c.created_by=? AND NOT EXISTS (SELECT 1 FROM customer_contact_links l JOIN customer_entity_accounts a ON a.workspace_id=l.workspace_id AND a.customer_id=l.customer_id WHERE l.workspace_id=c.workspace_id AND l.contact_id=c.id AND l.active)))";
    private final JdbcTemplate db;
    private final AccessControl access;
    private final CustomerRepository customers;
    private final AuditLog audit;
    public ContactService(JdbcTemplate db,AccessControl access,CustomerRepository customers,AuditLog audit) {this.db=db;this.access=access;this.customers=customers;this.audit=audit;}
    private static Contact map(java.sql.ResultSet r,int n) throws java.sql.SQLException {return new Contact(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBoolean(7),r.getLong(8));}
    public List<Contact> list(long ws,long entity,String query) {
        var scope=access.requireContext(ws,entity,false,READ); String q=query==null?"":query.trim();
        if(q.length()>200) throw new DomainException(400,"Search is too long");
        return db.query("SELECT c.id,c.code,c.first_name,c.last_name,c.email,c.lifecycle,c.active,c.version FROM contacts c WHERE c.workspace_id=? AND "+VISIBLE+" AND (position(lower(?) in lower(c.code))>0 OR position(lower(?) in lower(c.first_name || ' ' || c.last_name))>0) ORDER BY c.code,c.id",ContactService::map,ws,scope.actor().id(),scope.actor().id(),q,q);
    }
    public Contact get(long ws,long entity,long id) {var s=access.requireContext(ws,entity,false,READ); return visible(ws,s.actor().id(),id,false);}
    private Contact visible(long ws,long actor,long id,boolean lock) {
        List<Contact> rows=db.query("SELECT c.id,c.code,c.first_name,c.last_name,c.email,c.lifecycle,c.active,c.version FROM contacts c WHERE c.workspace_id=? AND "+VISIBLE+" AND c.id=?"+(lock?" FOR UPDATE OF c":""),ContactService::map,ws,actor,actor,id);
        if(rows.isEmpty()) throw new DomainException(404,"Contact not found"); return rows.get(0);
    }
    @Transactional
    public long save(long ws,long entity,Long id,Long version,String code,String first,String last,String email,String lifecycle) {
        var scope=access.requireContext(ws,entity,true,WRITE);
        code=text(code,80,true,"Code","code");first=text(first,100,true,"First name","firstName");last=text(last,100,true,"Last name","lastName");email=text(email,200,false,"Email","email");lifecycle=text(lifecycle,80,false,"Lifecycle","lifecycle");
        if(id==null && version!=null) throw new DomainException(400,"Unexpected version");
        Contact old=id==null?null:visible(ws,scope.actor().id(),id,true);
        if(old!=null && (version==null || old.version()!=version)) throw new DomainException(409,"Contact has changed");
        try {
            long result,revision;
            if(id==null) {result=db.queryForObject("INSERT INTO contacts(workspace_id,created_by,code,first_name,last_name,email,lifecycle) VALUES (?,?,?,?,?,?,?) RETURNING id",Long.class,ws,scope.actor().id(),code,first,last,email,lifecycle);revision=1;}
            else {db.update("UPDATE contacts SET code=?,first_name=?,last_name=?,email=?,lifecycle=?,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",code,first,last,email,lifecycle,ws,id);result=id;revision=old.version()+1;}
            audit.record(scope.actor(),ws,entity,id==null?"CREATE":"UPDATE","contacts",Long.toString(result),revision,"SUCCESS","contact fields"); return result;
        } catch(DataIntegrityViolationException ex) {throw new DomainException(409,"Contact conflicts with existing data");}
    }
    @Transactional
    public void deactivate(long ws,long entity,long id,long version) {
        var s=access.requireContext(ws,entity,true,WRITE); Contact c=visible(ws,s.actor().id(),id,true);
        if(c.version()!=version) throw new DomainException(409,"Contact has changed");
        db.update("UPDATE contacts SET active=false,version=version+1,updated_at=now() WHERE workspace_id=? AND id=?",ws,id);
        audit.record(s.actor(),ws,entity,"DEACTIVATE","contacts",Long.toString(id),version+1,"SUCCESS","active");
    }
    public List<Link> links(long ws,long entity,long customer) {
        var s=access.requireContext(ws,entity,false,READ);
        if(customers.find(ws,s.actor().id(),customer,false).isEmpty()) throw new DomainException(404,"Customer not found");
        return db.query("SELECT l.customer_id,l.contact_id,l.relationship_role,l.active,l.version FROM customer_contact_links l JOIN contacts c ON c.workspace_id=l.workspace_id AND c.id=l.contact_id WHERE l.workspace_id=? AND l.customer_id=? AND "+VISIBLE+" ORDER BY l.contact_id",(r,n)->new Link(r.getLong(1),r.getLong(2),r.getString(3),r.getBoolean(4),r.getLong(5)),ws,customer,s.actor().id(),s.actor().id());
    }
    @Transactional
    public void link(long ws,long entity,long customer,long customerVersion,long contact,long contactVersion,Long linkVersion,String role,boolean active) {
        var s=access.requireContext(ws,entity,true,WRITE);
        var cr=customers.find(ws,s.actor().id(),customer,true);
        if(cr.isEmpty()) throw new DomainException(404,"Customer not found");
        Contact ct=visible(ws,s.actor().id(),contact,true);
        if(cr.get(0).version()!=customerVersion || ct.version()!=contactVersion) throw new DomainException(409,"Master has changed");
        if(!cr.get(0).active() || !ct.active()) throw new DomainException(400,"Master is inactive");
        role=text(role,80,false,"Relationship role","role");
        List<Long> versions=db.query("SELECT version FROM customer_contact_links WHERE workspace_id=? AND customer_id=? AND contact_id=? FOR UPDATE",(r,n)->r.getLong(1),ws,customer,contact);
        if(versions.isEmpty() ? linkVersion!=null : linkVersion==null || !versions.get(0).equals(linkVersion)) throw new DomainException(409,"Link has changed");
        long revision;
        if(versions.isEmpty()) {db.update("INSERT INTO customer_contact_links(workspace_id,customer_id,contact_id,relationship_role,active) VALUES (?,?,?,?,?)",ws,customer,contact,role,active);revision=1;}
        else {db.update("UPDATE customer_contact_links SET relationship_role=?,active=?,version=version+1 WHERE workspace_id=? AND customer_id=? AND contact_id=?",role,active,ws,customer,contact);revision=linkVersion+1;}
        audit.record(s.actor(),ws,entity,"UPDATE","customer_contact_links",customer+":"+contact,revision,"SUCCESS","relationship_role active");
    }
    private static String text(String value,int max,boolean required,String label,String field) {String v=value==null?"":value.trim();if(required&&v.isBlank())throw new DomainException(400,label+" is required",field);if(v.length()>max)throw new DomainException(400,label+" is too long",field);return v;}
}
