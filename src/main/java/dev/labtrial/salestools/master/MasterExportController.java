package dev.labtrial.salestools.master;

import dev.labtrial.salestools.core.DomainException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/w/{workspaceId}/e/{entityId}")
public class MasterExportController {
    private final CustomerService customers;
    private final ContactService contacts;
    private final SupplierService suppliers;
    private final ProductService products;
    private final PriceBookService priceBooks;
    private final ConfigurationService configuration;

    public MasterExportController(CustomerService customers, ContactService contacts, SupplierService suppliers,
                                  ProductService products, PriceBookService priceBooks, ConfigurationService configuration) {
        this.customers=customers; this.contacts=contacts; this.suppliers=suppliers;
        this.products=products; this.priceBooks=priceBooks; this.configuration=configuration;
    }

    @GetMapping("/masters/{kind}/export")
    public ResponseEntity<byte[]> masters(@PathVariable long workspaceId, @PathVariable long entityId,
                                           @PathVariable String kind, @RequestParam(defaultValue="") String q) {
        StringBuilder csv=new StringBuilder();
        switch(kind) {
            case "customers" -> {
                row(csv,"Code","Name","Status");
                for(var r:customers.list(workspaceId,entityId,q)) row(csv,r.code(),r.name(),r.active()?"Active":"Inactive");
            }
            case "contacts" -> {
                row(csv,"Code","Name","Status");
                for(var r:contacts.list(workspaceId,entityId,q)) row(csv,r.code(),r.firstName()+" "+r.lastName(),r.active()?"Active":"Inactive");
            }
            case "suppliers" -> {
                row(csv,"Code","Name","Status");
                for(var r:suppliers.list(workspaceId,entityId,q)) row(csv,r.code(),r.name(),r.active()?"Active":"Inactive");
            }
            case "products" -> {
                row(csv,"SKU","Name","Status");
                for(var r:products.list(workspaceId,entityId,q)) row(csv,r.sku(),r.name(),r.active()?r.status():"Inactive");
            }
            case "price-books" -> {
                row(csv,"Code","Name","Currency","Status");
                for(var r:priceBooks.list(workspaceId,entityId,q)) row(csv,r.code(),r.name(),r.currency(),r.status()+(r.active()?" / Active":" / Inactive"));
            }
            default -> throw new DomainException(404,"Master not found");
        }
        return download(kind+".csv",csv);
    }

    @GetMapping("/configuration/export")
    public ResponseEntity<byte[]> configuration(@PathVariable long workspaceId, @PathVariable long entityId,
                                                 @RequestParam(defaultValue="uom") String kind,
                                                 @RequestParam(defaultValue="") String q) {
        ConfigurationService.Kind type=ConfigurationService.Kind.parse(kind);
        StringBuilder csv=new StringBuilder();
        if(type==ConfigurationService.Kind.TERMS) row(csv,"Code","Name","Days","Status");
        else row(csv,"Code","Name","Status");
        for(var r:configuration.list(workspaceId,entityId,type,q)) {
            if(type==ConfigurationService.Kind.TERMS) row(csv,r.code(),r.name(),r.days(),r.active()?"Active":"Inactive");
            else row(csv,r.code(),r.name(),r.active()?"Active":"Inactive");
        }
        return download(type.getKey()+".csv",csv);
    }

    private static void row(StringBuilder csv,Object... fields) {
        for(int i=0;i<fields.length;i++) {
            if(i>0) csv.append(',');
            String value=fields[i]==null?"":fields[i].toString();
            String leading=value.stripLeading();
            if(!value.isEmpty() && (value.charAt(0)=='\t' || value.charAt(0)=='\r' || value.charAt(0)=='\n'
                    || (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0))) value="'"+value;
            csv.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        csv.append("\r\n");
    }
    private static ResponseEntity<byte[]> download(String filename,StringBuilder csv) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
