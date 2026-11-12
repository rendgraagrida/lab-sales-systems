package dev.labtrial.salestools.baseline;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.finance.Ledger;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BaselineService {
    private record Product(String sku, String name, String category, String line, String unit, String price, String tracking) {}
    private record Account(String code,String name,String type) {}
    private record Customer(String code,String name,String segment,String territory,String domain,int parent) {}
    private record Contact(String code,String first,String last,String email,String role,int customer) {}
    private record Fixture(List<Product> products,List<String> units,List<Account> accounts,List<Customer> customers,List<Contact> contacts,
        String warehouseCode,String warehouseName,String termsCode,String termsName,int termsDays,String periodCode,LocalDate starts,LocalDate ends,
        String taxCode,String taxName,BigDecimal taxRate,String bookCode,String bookName,String currency,LocalDate cutoff,
        String supplierCode,String supplierName,String supplierEmail,String serial,String lot,String openingDescription,int trProduct,int csProduct,
        BigDecimal trQuantity,BigDecimal trCost,BigDecimal csQuantity,BigDecimal csCost,BigDecimal bank) {}
    private static final Fixture GOODS = new Fixture(List.of(
        new Product("RGA-UNIT-TR120","RGA-Transfer 120","LAB_UNIT","TRANSFER","EA","24000.00","SERIAL"),
        new Product("RGA-UNIT-TR360","RGA-Transfer 360","LAB_UNIT","TRANSFER","EA","46000.00","SERIAL"),
        new Product("RGA-UNIT-CL240","RGA-Clean 240","LAB_UNIT","CLEANUP","EA","18500.00","SERIAL"),
        new Product("RGA-UNIT-PR360","RGA-Prep 360","LAB_UNIT","PREPARATION","EA","58000.00","SERIAL"),
        new Product("RGA-UNIT-RI480","RGA-Rinse 480","LAB_UNIT","RINSE","EA","21000.00","SERIAL"),
        new Product("RGA-UNIT-WC600","RGA-Cell 600","LAB_UNIT","WORKCELL","EA","125000.00","SERIAL"),
        new Product("RGA-SUP-CS080","Cassette Kit 80","LAB_SUPPLY","TRANSFER","PACK","100.00","LOT"),
        new Product("RGA-SUP-CS160","Cassette Kit 160","LAB_SUPPLY","TRANSFER","PACK","180.00","LOT"),
        new Product("RGA-SUP-RS020","Reservoir Set 20","LAB_SUPPLY","PREPARATION","PACK","240.00","LOT"),
        new Product("RGA-SUP-QM010","QualityMix 10","LAB_SUPPLY","CLEANUP","PACK","320.00","LOT_EXPIRY"),
        new Product("RGA-SVC-BASE12","Assurance Base 12","SERVICE_PLAN","CROSS_LINE","TERM","1800.00","NONE"),
        new Product("RGA-SVC-PLUS12","Assurance Plus 12","SERVICE_PLAN","CROSS_LINE","TERM","3200.00","NONE"),
        new Product("RGA-PART-MD010","Motion Drive 10","REPLACEMENT_PART","TRANSFER","EA","950.00","NONE"),
        new Product("RGA-PART-SN020","Level Sensor 20","REPLACEMENT_PART","RINSE","EA","450.00","NONE"),
        new Product("RGA-IMP-ONB01","StartLab Onboarding","IMPLEMENTATION_SERVICE","CROSS_LINE","JOB","1200.00","NONE")),
        List.of("EA","PACK","TERM","JOB"),
        List.of(new Account("BANK","Bank","ASSET"),new Account("INVENTORY","Inventory","ASSET"),new Account("EQUITY","Opening equity","EQUITY"),new Account("TAX_INPUT","Input tax","ASSET"),new Account("TAX_OUTPUT","Output tax","LIABILITY")),
        List.of(new Customer("RGA-CUST-001","Orbelis Research Lab","ACADEMIA","EUROPE","orbelis.example",-1),new Customer("RGA-CUST-002","Teralune Bioanalytics","BIOTECH","NORTH_AMERICA","teralune.example",-1),new Customer("RGA-CUST-003","Orbelis Translational Lab","ACADEMIA","EUROPE","orbelis.example",0)),
        List.of(new Contact("RGA-CON-001","Mira","Senara","mira@orbelis.example","Lab manager",0),new Contact("RGA-CON-002","Arin","Velden","arin@teralune.example","Procurement",1)),
        "RGA-EU-01","RGA EU warehouse","NET30","Net 30",30,"FY2027",LocalDate.of(2027,1,1),LocalDate.of(2027,12,31),
        "SIM-VAT7","Synthetic VAT 7%",new BigDecimal("7"),"EUR-2027","EUR catalog","EUR",LocalDate.of(2027,1,15),
        "RGA-SUPPLIER-001","Nuvarek Components","ops@nuvarek.example","SIM-RGA-TR120-00002","SIM-LOT-CS080-001","O2C goods opening",0,6,
        new BigDecimal("1"),new BigDecimal("12000.00"),new BigDecimal("20"),new BigDecimal("55.00"),new BigDecimal("1800.00"));
    private final JdbcTemplate db; private final AccessControl access; private final AuditLog audit; private final Ledger ledger;
    public BaselineService(JdbcTemplate db, AccessControl access, AuditLog audit, Ledger ledger) { this.db=db; this.access=access; this.audit=audit; this.ledger=ledger; }

    @Transactional
    public long initialize(long ws, long entity, String key, long expectedVersion) {
        var scope=access.requireContext(ws,entity,true,"FINANCE");
        if (!"EMPTY".equals(key) && !"O2C_GOODS".equals(key)) throw new DomainException(400,"Invalid baseline","baselineKey");
        var workspace=db.queryForList("select initialization_state,version,business_date from scenario_workspaces where id=? for update",ws);
        if (workspace.size()!=1) throw new DomainException(404,"Workspace not found");
        String currency=db.queryForObject("select base_currency from legal_entities where workspace_id=? and id=? and active=true for update",String.class,ws,entity);
        if ("O2C_GOODS".equals(key) && !GOODS.currency().equals(currency)) throw new DomainException(400,"O2C goods requires a EUR entity","baselineKey");
        String sourceHash=hash("O2C_GOODS".equals(key) ? GOODS.toString() : "EMPTY");
        String payloadHash=hash(key,"1",Long.toString(ws),Long.toString(entity),workspace.get(0).get("business_date").toString(),currency,sourceHash);
        var prior=db.queryForList("select id,entity_id,baseline_key,baseline_version,source_hash,payload_hash from scenario_baseline_versions where workspace_id=?",ws);
        if (!prior.isEmpty()) {
            var r=prior.get(0);
            if (((Number)r.get("entity_id")).longValue()!=entity || !key.equals(r.get("baseline_key")) || !"1".equals(r.get("baseline_version")) || !sourceHash.equals(r.get("source_hash")) || !payloadHash.equals(r.get("payload_hash"))) throw new DomainException(409,"Workspace already initialized with another baseline");
            return ((Number)r.get("id")).longValue();
        }
        if (!"NEW".equals(workspace.get(0).get("initialization_state")) || ((Number)workspace.get(0).get("version")).longValue()!=expectedVersion) throw new DomainException(409,"Workspace changed");
        long actor=scope.actor().id();
        if ("O2C_GOODS".equals(key)) seedGoods(ws,entity,actor);
        Long receipt=db.queryForObject("insert into scenario_baseline_versions(workspace_id,entity_id,baseline_key,baseline_version,source_hash,payload_hash,receipt_key,status,created_by,approved_by) values (?,?,?,'1',?,?,?,'READY',?,?) returning id",Long.class,ws,entity,key,sourceHash,payloadHash,"workspace-"+ws,actor,actor);
        db.update("update scenario_workspaces set initialization_state='READY',baseline_key=?,baseline_version='1',version=version+1 where id=?",key,ws);
        audit.record(scope.actor(),ws,entity,"INITIALIZE","BASELINE",Long.toString(receipt),1,"SUCCESS",key);
        return receipt;
    }
    private static String hash(String... values) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(String value:values) {
                byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private void seedGoods(long w,long e,long a) {
        for (String table : List.of("customers","contacts","suppliers","uoms","products","warehouses","payment_terms","price_books","chart_accounts","fiscal_periods","tax_codes","journal_entries")) {
            Integer count=db.queryForObject("select count(*) from "+table+" where workspace_id=?",Integer.class,w);
            if (count != null && count>0) throw new DomainException(409,"Workspace contains existing data");
        }
        for(String unit:GOODS.units()) db.update("insert into uoms(workspace_id,code,name,created_by) values (?,?,?,?)",w,unit,unit,a);
        long warehouse=id("insert into warehouses(workspace_id,entity_id,code,name,created_by) values (?,?,?,?,?) returning id",w,e,GOODS.warehouseCode(),GOODS.warehouseName(),a);
        long terms=id("insert into payment_terms(workspace_id,entity_id,code,name,days,created_by) values (?,?,?,?,?,?) returning id",w,e,GOODS.termsCode(),GOODS.termsName(),GOODS.termsDays(),a);
        var accountIds=new java.util.ArrayList<Long>();
        for(Account account:GOODS.accounts()) accountIds.add(account(w,e,a,account.code(),account.name(),account.type()));
        long bank=accountIds.get(0), inventory=accountIds.get(1), equity=accountIds.get(2), input=accountIds.get(3), output=accountIds.get(4);
        db.update("insert into payment_posting_config(workspace_id,entity_id,bank_account_id,updated_by) values (?,?,?,?)",w,e,bank,a);
        db.update("insert into fiscal_periods(workspace_id,entity_id,code,starts_on,ends_on,status,created_by) values (?,?,?,?,?,'OPEN',?)",w,e,GOODS.periodCode(),GOODS.starts(),GOODS.ends(),a);
        long tax=id("insert into tax_codes(workspace_id,entity_id,code,name,created_by) values (?,?,?,?,?) returning id",w,e,GOODS.taxCode(),GOODS.taxName(),a);
        db.update("insert into tax_versions(workspace_id,entity_id,tax_code_id,valid_from,rate_pct,price_inclusive,rounding_mode,input_account_id,output_account_id,created_by) values (?,?,?,?,?,false,'HALF_UP',?,?,?)",w,e,tax,GOODS.starts(),GOODS.taxRate(),input,output,a);
        long book=id("insert into price_books(workspace_id,entity_id,code,name,currency,status,valid_from,created_by) values (?,?,?,?,?,'DRAFT',?,?) returning id",w,e,GOODS.bookCode(),GOODS.bookName(),GOODS.currency(),GOODS.cutoff(),a);
        var productIds=new java.util.ArrayList<Long>();
        for(Product p:GOODS.products()) {
            long unit=db.queryForObject("select id from uoms where workspace_id=? and code=?",Long.class,w,p.unit());
            long product=id("insert into products(workspace_id,sku,name,category,product_line,uom_id,tracking,status,created_by) values (?,?,?,?,?,?,?,'ACTIVE',?) returning id",w,p.sku(),p.name(),p.category(),p.line(),unit,p.tracking(),a);
            db.update("insert into product_entity_settings(workspace_id,entity_id,product_id) values (?,?,?)",w,e,product);
            db.update("insert into price_book_lines(workspace_id,entity_id,price_book_id,product_id,amount) values (?,?,?,?,?)",w,e,book,product,new BigDecimal(p.price()));
            productIds.add(product);
        }
        db.update("update price_books set status='APPROVED',approved_by=?,version=version+1 where workspace_id=? and entity_id=? and id=? and status='DRAFT'",a,w,e,book);
        long tr=productIds.get(GOODS.trProduct()),cs=productIds.get(GOODS.csProduct());
        var customerIds=new java.util.ArrayList<Long>();
        for(Customer c:GOODS.customers()) customerIds.add(customer(w,e,a,terms,c.code(),c.name(),c.segment(),c.territory(),c.domain(),c.parent()<0 ? null : customerIds.get(c.parent())));
        for(Contact c:GOODS.contacts()) contact(w,a,customerIds.get(c.customer()),c.code(),c.first(),c.last(),c.email(),c.role());
        long supplier=id("insert into suppliers(workspace_id,code,name,email,created_by) values (?,?,?,?,?) returning id",w,GOODS.supplierCode(),GOODS.supplierName(),GOODS.supplierEmail(),a);
        db.update("insert into supplier_entity_accounts(workspace_id,entity_id,supplier_id,erp_code,terms_id) values (?,?,?,?,?)",w,e,supplier,GOODS.supplierCode(),terms);
        BigDecimal trValue=GOODS.trQuantity().multiply(GOODS.trCost()), csValue=GOODS.csQuantity().multiply(GOODS.csCost()), inventoryValue=trValue.add(csValue);
        var opening=new Ledger.Opening("O2C_GOODS",1,GOODS.cutoff(),GOODS.currency(),GOODS.openingDescription(),List.of(new Ledger.Line(inventory,inventoryValue,BigDecimal.ZERO),new Ledger.Line(bank,GOODS.bank(),BigDecimal.ZERO),new Ledger.Line(equity,BigDecimal.ZERO,inventoryValue.add(GOODS.bank()))));
        long source=ledger.postOpening(w,e,opening).sourceId();
        long serial=id("insert into instrument_serials(workspace_id,entity_id,product_id,warehouse_id,serial,status,opening_source_id) values (?,?,?,?,?,'IN_STOCK',?) returning id",w,e,tr,warehouse,GOODS.serial(),source);
        long lot=id("insert into inventory_lots(workspace_id,entity_id,product_id,warehouse_id,lot,opening_source_id) values (?,?,?,?,?,?) returning id",w,e,cs,warehouse,GOODS.lot(),source);
        stock(w,e,a,tr,warehouse,source,GOODS.trQuantity(),trValue,GOODS.products().get(GOODS.trProduct()).sku(),serial,null);
        stock(w,e,a,cs,warehouse,source,GOODS.csQuantity(),csValue,GOODS.products().get(GOODS.csProduct()).sku(),null,lot);
    }
    private long account(long w,long e,long a,String code,String name,String type) { return id("insert into chart_accounts(workspace_id,entity_id,code,name,account_type,created_by) values (?,?,?,?,?,?) returning id",w,e,code,name,type,a); }
    private long customer(long w,long e,long a,long terms,String code,String name,String segment,String territory,String domain,Long parent) {
        long id=id("insert into customers(workspace_id,code,name,segment,territory,domain,parent_customer_id,created_by) values (?,?,?,?,?,?,?,?) returning id",w,code,name,segment,territory,domain,parent,a);
        db.update("insert into customer_entity_accounts(workspace_id,entity_id,customer_id,erp_code,credit_status,terms_id) values (?,?,?,?,'APPROVED',?)",w,e,id,code,terms); return id;
    }
    private void contact(long w,long a,long customer,String code,String first,String last,String email,String role) {
        long id=id("insert into contacts(workspace_id,code,first_name,last_name,email,lifecycle,created_by) values (?,?,?,?,?,'CUSTOMER',?) returning id",w,code,first,last,email,a);
        db.update("insert into customer_contact_links(workspace_id,customer_id,contact_id,relationship_role) values (?,?,?,?)",w,customer,id,role);
    }
    private void stock(long w,long e,long a,long product,long warehouse,long source,BigDecimal q,BigDecimal v,String line,Long serial,Long lot) {
        db.update("insert into inventory_balances(workspace_id,entity_id,product_id,warehouse_id,on_hand,value) values (?,?,?,?,?,?)",w,e,product,warehouse,q,v);
        db.update("insert into inventory_valuation_pools(workspace_id,entity_id,product_id,quantity,value,last_posting_date,pool_sequence) values (?,?,?,?,?,?,1)",w,e,product,q,v,GOODS.cutoff());
        db.update("insert into stock_movements(workspace_id,entity_id,product_id,warehouse_id,opening_source_id,quantity_delta,value_delta,posting_date,source_line_key,serial_id,lot_id,created_by) values (?,?,?,?,?,?,?,?,?,?,?,?)",w,e,product,warehouse,source,q,v,GOODS.cutoff(),line,serial,lot,a);
    }
    private long id(String sql,Object... args) { return db.queryForObject(sql,Long.class,args); }
}
