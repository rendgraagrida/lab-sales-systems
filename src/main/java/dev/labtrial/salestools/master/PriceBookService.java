package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceBookService {
    public record Book(long id,String code,String name,String currency,long revision,String status,LocalDate validFrom,LocalDate validTo,long version,boolean active) {}
    public record Line(long id,long productId,String sku,String productName,BigDecimal amount,long version) {}
    private static final String[] READ={"INSIDE_SALES","SALES_MANAGER","FINANCE"};
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit;
    public PriceBookService(JdbcTemplate db,AccessControl access,AuditLog audit){this.db=db;this.access=access;this.audit=audit;}
    private static Book map(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Book(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),r.getObject(7,LocalDate.class),r.getObject(8,LocalDate.class),r.getLong(9),r.getBoolean(10));}
    public List<Book> list(long ws,long entity,String search){access.requireContext(ws,entity,false,READ);String q=search==null?"":search.trim();if(q.length()>200)throw new DomainException(400,"Search is too long");return db.query("SELECT id,code,name,currency,revision,status,valid_from,valid_to,version,active FROM price_books WHERE workspace_id=? AND entity_id=? AND (position(lower(?) in lower(code))>0 OR position(lower(?) in lower(name))>0) ORDER BY code,id",PriceBookService::map,ws,entity,q,q);}
    public Book get(long ws,long entity,long id){access.requireContext(ws,entity,false,READ);return find(ws,entity,id,false);}
    private Book find(long ws,long entity,long id,boolean lock){var rows=db.query("SELECT id,code,name,currency,revision,status,valid_from,valid_to,version,active FROM price_books WHERE workspace_id=? AND entity_id=? AND id=?"+(lock?" FOR UPDATE":""),PriceBookService::map,ws,entity,id);if(rows.isEmpty())throw new DomainException(404,"Price book not found");Book b=rows.get(0);if(lock&&!b.active())throw new DomainException(409,"Price book is inactive");return b;}
    public List<Line> lines(long ws,long entity,long id){access.requireContext(ws,entity,false,READ);find(ws,entity,id,false);return db.query("SELECT l.id,l.product_id,p.sku,p.name,l.amount,l.version FROM price_book_lines l JOIN products p ON p.workspace_id=l.workspace_id AND p.id=l.product_id JOIN product_entity_settings s ON s.workspace_id=l.workspace_id AND s.entity_id=l.entity_id AND s.product_id=l.product_id AND s.active WHERE l.workspace_id=? AND l.entity_id=? AND l.price_book_id=? ORDER BY p.sku,p.id",(r,n)->new Line(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4),r.getBigDecimal(5),r.getLong(6)),ws,entity,id);}
    @Transactional
    public long save(long ws,long entity,Long id,Long version,String code,String name,String currency,LocalDate from,LocalDate to) {
        var s=access.requireContext(ws,entity,true,"SALES_MANAGER");
        code=text(code,80,"Code","code"); name=text(name,200,"Name","name");
        if(currency==null||!List.of("EUR","USD").contains(currency)) throw new DomainException(400,"Invalid currency","currency");
        if(from==null) throw new DomainException(400,"Valid from is required","validFrom");
        if(to!=null&&to.isBefore(from)) throw new DomainException(400,"Valid to must not precede valid from","validTo");
        if(id==null&&version!=null) throw new DomainException(400,"Unexpected version");
        Book old=id==null?null:find(ws,entity,id,true);
        if(old!=null&&(version==null||old.version()!=version)) throw new DomainException(409,"Price book has changed");
        if(old!=null&&!"DRAFT".equals(old.status())) throw new DomainException(409,"Approved price books are immutable");
        try {
            long result,revision;
            if(old==null) {
                result=db.queryForObject("INSERT INTO price_books(workspace_id,entity_id,created_by,code,name,currency,valid_from,valid_to) VALUES (?,?,?,?,?,?,?,?) RETURNING id",Long.class,ws,entity,s.actor().id(),code,name,currency,from,to);
                revision=1;
            } else {
                db.update("UPDATE price_books SET code=?,name=?,currency=?,valid_from=?,valid_to=?,version=version+1,revision=revision+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",code,name,currency,from,to,ws,entity,id);
                result=id; revision=old.version()+1;
            }
            contribute(ws,entity,result,s.actor().id());
            audit.record(s.actor(),ws,entity,old==null?"CREATE":"UPDATE","price_books",Long.toString(result),revision,"SUCCESS","draft fields");
            return result;
        } catch(DataIntegrityViolationException ex) { throw new DomainException(409,"Price book conflicts with existing data"); }
    }

    @Transactional
    public void line(long ws,long entity,long book,long bookVersion,long product,long productVersion,Long lineVersion,BigDecimal amount) {
        var s=access.requireContext(ws,entity,true,"SALES_MANAGER");
        Book b=find(ws,entity,book,true);
        if(b.version()!=bookVersion) throw new DomainException(409,"Price book has changed");
        if(!"DRAFT".equals(b.status())) throw new DomainException(409,"Approved price books are immutable");
        if(amount==null||amount.signum()<0||amount.scale()>2||(long)amount.precision()-amount.scale()>16) throw new DomainException(400,"Invalid amount","amount");
        var p=db.query("SELECT p.version,p.active FROM products p JOIN product_entity_settings x ON x.workspace_id=p.workspace_id AND x.product_id=p.id AND x.entity_id=? AND x.active WHERE p.workspace_id=? AND p.id=? FOR UPDATE OF p,x",(r,n)->new long[]{r.getLong(1),r.getBoolean(2)?1:0},entity,ws,product);
        if(p.isEmpty()) throw new DomainException(404,"Product not found");
        if(p.get(0)[0]!=productVersion) throw new DomainException(409,"Product has changed");
        if(p.get(0)[1]==0) throw new DomainException(400,"Product is inactive","product");
        var lines=db.query("SELECT id,version FROM price_book_lines WHERE workspace_id=? AND entity_id=? AND price_book_id=? AND product_id=? FOR UPDATE",(r,n)->new long[]{r.getLong(1),r.getLong(2)},ws,entity,book,product);
        if(lines.isEmpty()?lineVersion!=null:lineVersion==null||lines.get(0)[1]!=lineVersion) throw new DomainException(409,"Price line has changed");
        long lineId,revision;
        if(lines.isEmpty()) {
            lineId=db.queryForObject("INSERT INTO price_book_lines(workspace_id,entity_id,price_book_id,product_id,amount) VALUES (?,?,?,?,?) RETURNING id",Long.class,ws,entity,book,product,amount);
            revision=1;
        } else {
            lineId=lines.get(0)[0]; revision=lines.get(0)[1]+1;
            db.update("UPDATE price_book_lines SET amount=?,version=version+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",amount,ws,entity,lineId);
        }
        db.update("UPDATE price_books SET revision=revision+1,version=version+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",ws,entity,book);
        contribute(ws,entity,book,s.actor().id());
        audit.record(s.actor(),ws,entity,"UPDATE","price_book_lines",Long.toString(lineId),revision,"SUCCESS","amount");
    }

    @Transactional
    public void approve(long ws,long entity,long id,long version) {
        var scope=access.requireContext(ws,entity,true,"SALES_MANAGER");
        Book book=find(ws,entity,id,true);
        if(book.version()!=version) throw new DomainException(409,"Price book has changed");
        if(!"DRAFT".equals(book.status())) throw new DomainException(409,"Price book is already approved");
        Boolean contributor=db.queryForObject("""
            select exists(select 1 from price_book_contributors
              where workspace_id=? and entity_id=? and price_book_id=? and user_id=?)
              or exists(select 1 from price_books where workspace_id=? and entity_id=? and id=? and created_by=?)
            """,Boolean.class,ws,entity,id,scope.actor().id(),ws,entity,id,scope.actor().id());
        if(Boolean.TRUE.equals(contributor)) throw new DomainException(403,"A creator or editor cannot approve this price book");
        String base=db.queryForObject("select base_currency from legal_entities where workspace_id=? and id=? and active",String.class,ws,entity);
        if(!book.currency().equals(base)) throw new DomainException(409,"Price book currency must match the entity");
        var prices=db.queryForList("""
            select l.id,l.product_id,l.amount,l.version,p.version as product_version,
                   p.active,p.status,s.active as setting_active,s.version as setting_version
            from price_book_lines l join products p on p.workspace_id=l.workspace_id and p.id=l.product_id
            left join product_entity_settings s on s.workspace_id=l.workspace_id and s.entity_id=l.entity_id and s.product_id=l.product_id
            where l.workspace_id=? and l.entity_id=? and l.price_book_id=? order by l.id
            for update of l,p
            """,ws,entity,id);
        if(prices.isEmpty()) throw new DomainException(400,"Add at least one price before approval");
        for(var price:prices) if(!Boolean.TRUE.equals(price.get("active"))||!"ACTIVE".equals(price.get("status"))||!Boolean.TRUE.equals(price.get("setting_active")))
            throw new DomainException(409,"A price product or its entity assignment is inactive");
        String hash=approvalHash(book,prices);
        db.update("insert into price_book_approvals(workspace_id,entity_id,price_book_id,revision,payload_hash,approved_by) values (?,?,?,?,?,?)",ws,entity,id,book.revision(),hash,scope.actor().id());
        db.update("update price_books set status='APPROVED',approved_by=?,version=version+1,updated_at=now() where workspace_id=? and entity_id=? and id=? and version=?",scope.actor().id(),ws,entity,id,version);
        audit.record(scope.actor(),ws,entity,"APPROVE","price_books",Long.toString(id),book.revision(),"SUCCESS",hash);
    }

    private void contribute(long ws,long entity,long book,long actor) {
        db.update("insert into price_book_contributors(workspace_id,entity_id,price_book_id,user_id) values (?,?,?,?) on conflict do nothing",ws,entity,book,actor);
    }

    private static String approvalHash(Book book,List<java.util.Map<String,Object>> prices) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var values=new java.util.ArrayList<String>();
            values.addAll(List.of(Long.toString(book.id()),book.code(),book.name(),book.currency(),Long.toString(book.revision()),book.validFrom().toString(),book.validTo()==null?"":book.validTo().toString()));
            for(var price:prices) for(String key:List.of("id","product_id","amount","version","product_version","setting_version")) values.add(price.get(key).toString());
            for(String value:values) {
                byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @Transactional public void deactivate(long ws,long entity,long id,long version){var s=access.requireContext(ws,entity,true,"SALES_MANAGER");Book b=find(ws,entity,id,true);if(b.version()!=version)throw new DomainException(409,"Price book has changed");db.update("UPDATE price_books SET active=false,version=version+1,updated_at=now() WHERE workspace_id=? AND entity_id=? AND id=?",ws,entity,id);audit.record(s.actor(),ws,entity,"DEACTIVATE","price_books",Long.toString(id),version+1,"SUCCESS","active");}
    private static String text(String v,int max,String label,String field){if(v==null||v.isBlank()||v.trim().length()>max)throw new DomainException(400,label+" is required or too long",field);return v.trim();}
}
