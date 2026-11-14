package dev.labtrial.salestools.service;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceConfigurationService {
    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;

    public ServiceConfigurationService(JdbcTemplate db, AccessControl access, AuditLog audit) {
        this.db = db; this.access = access; this.audit = audit;
    }

    public List<Map<String, Object>> coverageTypes(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForList("select id,code,name from service_coverage_types where workspace_id=? and entity_id=? order by code,id", w, e);
    }

    public List<Map<String, Object>> products(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForList("select p.id,p.version,p.sku,p.name,p.category,s.version setting_version,coalesce((select max(v.revision) from service_product_versions v where v.workspace_id=p.workspace_id and v.entity_id=s.entity_id and v.product_id=p.id),0) profile_revision from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active where p.workspace_id=? and p.active and p.status='ACTIVE' and p.tracking='NONE' and p.category in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') order by p.sku", e, w);
    }

    public List<Map<String, Object>> profiles(long w, long e) {
        access.requireContext(w, e, false, "INSIDE_SALES", "SALES_MANAGER");
        return db.queryForList("select v.*,p.sku,p.name,t.code coverage_code from service_product_versions v join products p on p.workspace_id=v.workspace_id and p.id=v.product_id left join service_coverage_types t on t.workspace_id=v.workspace_id and t.entity_id=v.entity_id and t.id=v.coverage_type_id where v.workspace_id=? and v.entity_id=? order by p.sku,v.revision desc", w, e);
    }

    @Transactional
    public long createCoverageType(long w, long e, String code, String name) {
        var scope = access.requireContext(w, e, true, "SALES_MANAGER");
        code = text(code, 80, "code"); name = text(name, 200, "name");
        if (Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from service_coverage_types where workspace_id=? and entity_id=? and code=?)", Boolean.class, w, e, code)))
            throw new DomainException(409, "Coverage type code already exists", "code");
        long id = db.queryForObject("insert into service_coverage_types(workspace_id,entity_id,code,name,created_by) values (?,?,?,?,?) returning id", Long.class, w, e, code, name, scope.actor().id());
        audit.record(scope.actor(), w, e, "CREATE", "service_coverage_types", Long.toString(id), 1, "SUCCESS", "service coverage type");
        return id;
    }

    @Transactional
    public long createProfile(long w, long e, long productId, long productVersion, long settingVersion,
                              long expectedRevision, Long coverageTypeId, Integer durationMonths, boolean enabled) {
        var scope = access.requireContext(w, e, true, "SALES_MANAGER");
        var rows = db.queryForList("select p.category from products p join product_entity_settings s on s.workspace_id=p.workspace_id and s.product_id=p.id and s.entity_id=? and s.active where p.workspace_id=? and p.id=? and p.version=? and s.version=? and p.active and p.status='ACTIVE' and p.tracking='NONE' and p.category in ('SERVICE_PLAN','IMPLEMENTATION_SERVICE') for update of p,s", e, w, productId, productVersion, settingVersion);
        if (rows.size() != 1) throw new DomainException(409, "Service product or entity assignment changed", "productRef", "settingVersion");
        String category = (String) rows.get(0).get("category");
        if ("SERVICE_PLAN".equals(category)) {
            if (coverageTypeId == null || durationMonths == null || durationMonths < 1 || durationMonths > 1200)
                throw new DomainException(400, "Plan requires a coverage type and duration from 1 to 1200 months", "serviceProfile", "quantity");
            if (!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from service_coverage_types where workspace_id=? and entity_id=? and id=?)", Boolean.class, w, e, coverageTypeId)))
                throw new DomainException(409, "Coverage type is unavailable", "serviceProfile");
        } else if (coverageTypeId != null || durationMonths != null) {
            throw new DomainException(400, "Onboarding cannot have coverage or duration", "serviceProfile", "quantity");
        }
        Long current = db.queryForObject("select coalesce(max(revision),0) from service_product_versions where workspace_id=? and entity_id=? and product_id=?", Long.class, w, e, productId);
        if (current == null || current != expectedRevision) throw new DomainException(409, "Service profile changed; reload before saving", "version");
        long revision = current + 1;
        long id = db.queryForObject("insert into service_product_versions(workspace_id,entity_id,product_id,revision,category_snapshot,coverage_type_id,duration_months,enabled,created_by) values (?,?,?,?,?,?,?,?,?) returning id", Long.class, w, e, productId, revision, category, coverageTypeId, durationMonths, enabled, scope.actor().id());
        audit.record(scope.actor(), w, e, "CREATE", "service_product_versions", Long.toString(id), revision, "SUCCESS", "service profile revision");
        return id;
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new DomainException(400, "Required or too long", field);
        return value.trim();
    }
}
