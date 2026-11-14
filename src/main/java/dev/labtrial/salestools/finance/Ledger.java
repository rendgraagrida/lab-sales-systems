package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.AuditLog;
import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Ledger {
    public record Line(long accountId, BigDecimal debit, BigDecimal credit) {}
    public record Opening(String sourceKey, long revision, LocalDate date, String currency,
                          String description, List<Line> lines) {}
    public record Result(long sourceId, long journalId, boolean replayed) {}

    private final JdbcTemplate db;
    private final AccessControl access;
    private final AuditLog audit;
    private final ServiceRevenueService serviceRevenue;

    public Ledger(JdbcTemplate db, AccessControl access, AuditLog audit, ServiceRevenueService serviceRevenue) {
        this.db = db;
        this.access = access;
        this.audit = audit;
        this.serviceRevenue = serviceRevenue;
    }

    @Transactional
    public Result postOpening(long workspaceId, long entityId, Opening opening) {
        return postOpeningInternal(workspaceId, entityId, opening, List.of());
    }

    @Transactional
    public Result postServiceOpening(long workspaceId, long entityId, Opening opening,
                                     List<ServiceRevenueService.OpeningStatement> statements) {
        if (statements == null || statements.isEmpty() || statements.size() > 100)
            throw new DomainException(400, "A typed service opening requires between one and 100 term statements");
        for (var statement : statements) {
            if (statement == null || statement.termId() < 1 || statement.cutoff() == null
                    || !validAmount(statement.billed()) || statement.billed().signum() < 0
                    || !validAmount(statement.earned()) || statement.earned().signum() < 0
                    || statement.billingReference() == null || statement.billingReference().isBlank()
                    || statement.billingReference().length() > 120
                    || statement.settlementReference() == null || statement.settlementReference().isBlank()
                    || statement.settlementReference().length() > 120)
                throw new DomainException(400, "Invalid typed service opening statement");
        }
        var ordered = new ArrayList<>(statements);
        ordered.sort(Comparator.comparingLong(ServiceRevenueService.OpeningStatement::termId));
        return postOpeningInternal(workspaceId, entityId, opening, ordered);
    }

    private Result postOpeningInternal(long workspaceId, long entityId, Opening opening,
                                       List<ServiceRevenueService.OpeningStatement> statements) {
        var scope = access.requireContext(workspaceId, entityId, true, "FINANCE");
        validate(opening);
        db.queryForObject("select id from scenario_workspaces where id=? for update", Long.class, workspaceId);
        var entity = db.queryForList("select base_currency from legal_entities where workspace_id=? and id=? and active=true for update", workspaceId, entityId);
        if (entity.size() != 1) throw new DomainException(404, "Entity not found");
        if (!opening.currency().equals(entity.get(0).get("base_currency"))) throw new DomainException(400, "Currency must match the entity", "currency");
        String hash = statements.isEmpty() ? hash(opening) : hashServiceOpening(opening, statements);
        var prior = db.queryForList("select id,journal_id,payload_hash from opening_balance_sources where workspace_id=? and entity_id=? and source_key=? and revision=?", workspaceId, entityId, opening.sourceKey(), opening.revision());
        if (!prior.isEmpty()) {
            var row = prior.get(0);
            if (!hash.equals(row.get("payload_hash"))) throw new DomainException(409, "Opening source conflicts with an existing posting");
            for (var statement : statements)
                serviceRevenue.recordOpening(scope, ((Number) row.get("id")).longValue(), statement);
            return new Result(((Number) row.get("id")).longValue(), ((Number) row.get("journal_id")).longValue(), true);
        }
        var periods = db.queryForList("select id,status from fiscal_periods where workspace_id=? and entity_id=? and ? between starts_on and ends_on for update", workspaceId, entityId, opening.date());
        Map<Long,BigDecimal> serviceControls = statements.isEmpty() ? Map.of() : serviceControls(scope, opening, statements);
        if (periods.size() != 1 || !"OPEN".equals(periods.get(0).get("status"))) throw new DomainException(409, "Posting requires an open fiscal period");
        for (int i=0; i<opening.lines().size(); i++) {
            Line line=opening.lines().get(i);
            var accounts = db.queryForList("select account_type from chart_accounts where workspace_id=? and entity_id=? and id=? and active=true",
                    workspaceId, entityId, line.accountId());
            if (accounts.size() != 1) throw new DomainException(400, "Invalid account", "accountId["+i+"]");
            if (!statements.isEmpty() && List.of("INCOME","EXPENSE").contains(accounts.get(0).get("account_type")))
                throw new DomainException(400, "Typed service opening cannot replay historical income or expenses", "accountId["+i+"]");
            Integer controls = db.queryForObject("select count(*) from procurement_posting_config where workspace_id=? and entity_id=? and ? in (inventory_account_id,ap_account_id,grni_account_id)", Integer.class, workspaceId, entityId, line.accountId());
            if (controls != null && controls > 0) throw new DomainException(400, "Control accounts require a typed opening source", "accountId["+i+"]");
            Integer arControls=db.queryForObject("select count(*) from sales_posting_config where workspace_id=? and entity_id=? and ar_account_id=?",
                    Integer.class,workspaceId,entityId,line.accountId());
            if(arControls!=null&&arControls>0)
                throw new DomainException(400,"Customer AR requires a separate typed receivables opening source","accountId["+i+"]");
            Integer salesControls=db.queryForObject("select count(*) from sales_posting_config where workspace_id=? and entity_id=? and ? in (ar_account_id,unbilled_account_id)",Integer.class,workspaceId,entityId,line.accountId());
            if(salesControls!=null&&salesControls>0&&!serviceControls.containsKey(line.accountId()))
                throw new DomainException(400,"AR and Unbilled require typed opening sources","accountId["+i+"]");
            Integer deferredControls=db.queryForObject("select count(*) from service_posting_config where workspace_id=? and entity_id=? and deferred_account_id=?",
                    Integer.class,workspaceId,entityId,line.accountId());
            if(deferredControls!=null&&deferredControls>0&&!serviceControls.containsKey(line.accountId()))
                throw new DomainException(400,"Deferred revenue requires a typed service opening statement","accountId["+i+"]");
        }
        db.update("insert into document_sequences(workspace_id,entity_id,document_type,next_value) values (?,?,'OPENING',1) on conflict (workspace_id,entity_id,document_type) do nothing", workspaceId, entityId);
        Long sequence = db.queryForObject("update document_sequences set next_value=next_value+1,version=version+1 where workspace_id=? and entity_id=? and document_type='OPENING' returning next_value-1", Long.class, workspaceId, entityId);
        String postingKey = opening.sourceKey() + ":" + opening.revision();
        Long journal = db.queryForObject("insert into journal_entries(workspace_id,entity_id,number,source_type,source_id,posting_key,posting_date,currency,description,state,created_by) values (?,?,?,'OPENING',?,?,?,?,?,'DRAFT',?) returning id", Long.class, workspaceId, entityId, "OPEN-" + sequence, opening.sourceKey(), postingKey, opening.date(), opening.currency(), opening.description(), scope.actor().id());
        for (Line line : opening.lines()) {
            db.update("insert into journal_lines(workspace_id,entity_id,journal_id,account_id,debit,credit) values (?,?,?,?,?,?)", workspaceId, entityId, journal, line.accountId(), line.debit(), line.credit());
        }
        db.update("update journal_entries set state='POSTED',posted_at=now(),version=version+1 where id=?", journal);
        Long source = db.queryForObject("insert into opening_balance_sources(workspace_id,entity_id,source_key,revision,posting_date,currency,payload_hash,status,created_by,approved_by,journal_id) values (?,?,?,?,?,?,?,'POSTED',?,?,?) returning id", Long.class, workspaceId, entityId, opening.sourceKey(), opening.revision(), opening.date(), opening.currency(), hash, scope.actor().id(), scope.actor().id(), journal);
        for (var statement : statements) serviceRevenue.recordOpening(scope, source, statement);
        audit.record(scope.actor(), workspaceId, entityId, "POST", "OPENING", Long.toString(source), opening.revision(), "SUCCESS", "opening journal posted");
        return new Result(source, journal, false);
    }
    private Map<Long,BigDecimal> serviceControls(AccessControl.Scope scope, Opening opening,
                                               List<ServiceRevenueService.OpeningStatement> statements) {
        var expected = new HashMap<Long,BigDecimal>();
        var seen = new HashSet<Long>();
        for (var statement : statements) {
            if (!seen.add(statement.termId()) || !opening.date().equals(statement.cutoff()))
                throw new DomainException(400, "Each opening term appears once and must use the opening cutoff");
            var terms = db.queryForList("""
                    select source_kind,opening_reference,currency,net_fee,unbilled_account_id,deferred_account_id,status
                    from service_contract_terms where workspace_id=? and entity_id=? and id=? for update
                    """,scope.workspaceId(),scope.entityId(),statement.termId());
            if (terms.size()!=1) throw new DomainException(404,"Opening service term not found");
            var term=terms.get(0);
            if (!"OPENING".equals(term.get("source_kind")) || !"VALIDATED".equals(term.get("status"))
                    || !(opening.sourceKey()+":"+opening.revision()).equals(term.get("opening_reference"))
                    || !opening.currency().equals(term.get("currency")))
                throw new DomainException(409,"Service term does not belong to this opening source");
            BigDecimal net=(BigDecimal)term.get("net_fee");
            if (statement.billed().compareTo(net)>0 || statement.earned().compareTo(net)>0)
                throw new DomainException(400,"Opening earned and billed amounts cannot exceed the approved term fee");
            if (statement.billed().compareTo(net)!=0)
                throw new DomainException(400,"Partially billed service openings require explicit historical fee-interval authority; this opening supports fully billed terms only");
            long unbilled=((Number)term.get("unbilled_account_id")).longValue();
            long deferred=((Number)term.get("deferred_account_id")).longValue();
            expected.merge(unbilled,statement.earned().subtract(statement.billed()).max(BigDecimal.ZERO),BigDecimal::add);
            expected.merge(deferred,statement.billed().subtract(statement.earned()).max(BigDecimal.ZERO).negate(),BigDecimal::add);
        }
        var actual = new HashMap<Long,BigDecimal>();
        for (Line line:opening.lines())
            if (expected.containsKey(line.accountId()))
                actual.merge(line.accountId(),line.debit().subtract(line.credit()),BigDecimal::add);
        for (var entry:expected.entrySet())
            if (entry.getValue().compareTo(actual.getOrDefault(entry.getKey(),BigDecimal.ZERO))!=0)
                throw new DomainException(400,"Opening control-account balances do not reconcile to the typed term statements","debit","credit");
        return expected;
    }

    private static String hashServiceOpening(Opening opening,List<ServiceRevenueService.OpeningStatement> statements) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            put(digest,hash(opening));
            for (var statement:statements) {
                put(digest,Long.toString(statement.termId())); put(digest,statement.cutoff().toString());
                put(digest,statement.billed().setScale(2).toPlainString()); put(digest,statement.earned().setScale(2).toPlainString());
                put(digest,statement.billingReference()); put(digest,statement.settlementReference());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }


    private static void validate(Opening opening) {
        if (opening == null) throw new DomainException(400, "Invalid opening");
        if (opening.sourceKey() == null || opening.sourceKey().isBlank() || opening.sourceKey().length() > 120) throw new DomainException(400, "Invalid opening", "sourceKey");
        if (opening.revision() < 1) throw new DomainException(400, "Invalid opening", "revision");
        if (opening.date() == null) throw new DomainException(400, "Invalid opening", "date");
        if (opening.currency() == null) throw new DomainException(400, "Invalid opening", "currency");
        if (opening.description() == null || opening.description().length() > 500) throw new DomainException(400, "Invalid opening", "description");
        if (opening.lines() == null || opening.lines().size() < 2 || opening.lines().size() > 100) throw new DomainException(400, "Invalid opening", "accountId", "debit", "credit");
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (int i=0; i<opening.lines().size(); i++) {
            Line line=opening.lines().get(i);
            String message="Each line requires one positive amount with at most sixteen integer digits and two decimal places";
            if (line == null) throw new DomainException(400, message, "accountId["+i+"]", "debit["+i+"]", "credit["+i+"]");
            if (line.accountId() < 1) throw new DomainException(400, message, "accountId["+i+"]");
            if (!validAmount(line.debit())) throw new DomainException(400, message, "debit["+i+"]");
            if (!validAmount(line.credit())) throw new DomainException(400, message, "credit["+i+"]");
            if (!((line.debit().signum() > 0 && line.credit().signum() == 0) || (line.credit().signum() > 0 && line.debit().signum() == 0))) throw new DomainException(400, message, "debit["+i+"]", "credit["+i+"]");
            debit = debit.add(line.debit()); credit = credit.add(line.credit());
        }
        if (debit.signum() <= 0 || debit.compareTo(credit) != 0) throw new DomainException(400, "Opening must balance", "debit", "credit");
    }

    private static boolean validAmount(BigDecimal amount) {
        // Bound the representation before setScale/hash can materialize an enormous exponent.
        return amount != null && amount.scale() <= 2 && (long) amount.precision() - amount.scale() <= 16;
    }

    private static String hash(Opening opening) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, opening.sourceKey()); put(digest, Long.toString(opening.revision())); put(digest, opening.date().toString());
            put(digest, opening.currency()); put(digest, opening.description());
            for (Line line : opening.lines()) { put(digest, Long.toString(line.accountId())); put(digest, line.debit().setScale(2).toPlainString()); put(digest, line.credit().setScale(2).toPlainString()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
