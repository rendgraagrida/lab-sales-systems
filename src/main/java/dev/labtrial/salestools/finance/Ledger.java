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

    public Ledger(JdbcTemplate db, AccessControl access, AuditLog audit) {
        this.db = db;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Result postOpening(long workspaceId, long entityId, Opening opening) {
        var scope = access.requireContext(workspaceId, entityId, true, "FINANCE");
        validate(opening);
        db.queryForObject("select id from scenario_workspaces where id=? for update", Long.class, workspaceId);
        var entity = db.queryForList("select base_currency from legal_entities where workspace_id=? and id=? and active=true for update", workspaceId, entityId);
        if (entity.size() != 1) throw new DomainException(404, "Entity not found");
        if (!opening.currency().equals(entity.get(0).get("base_currency"))) throw new DomainException(400, "Currency must match the entity", "currency");
        String hash = hash(opening);
        var prior = db.queryForList("select id,journal_id,payload_hash from opening_balance_sources where workspace_id=? and entity_id=? and source_key=? and revision=?", workspaceId, entityId, opening.sourceKey(), opening.revision());
        if (!prior.isEmpty()) {
            var row = prior.get(0);
            if (!hash.equals(row.get("payload_hash"))) throw new DomainException(409, "Opening source conflicts with an existing posting");
            return new Result(((Number) row.get("id")).longValue(), ((Number) row.get("journal_id")).longValue(), true);
        }
        var periods = db.queryForList("select id,status from fiscal_periods where workspace_id=? and entity_id=? and ? between starts_on and ends_on for update", workspaceId, entityId, opening.date());
        if (periods.size() != 1 || !"OPEN".equals(periods.get(0).get("status"))) throw new DomainException(409, "Posting requires an open fiscal period");
        for (int i=0; i<opening.lines().size(); i++) {
            Line line=opening.lines().get(i);
            Integer count = db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=? and active=true", Integer.class, workspaceId, entityId, line.accountId());
            if (count == null || count != 1) throw new DomainException(400, "Invalid account", "accountId["+i+"]");
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
        audit.record(scope.actor(), workspaceId, entityId, "POST", "OPENING", Long.toString(source), opening.revision(), "SUCCESS", "opening journal posted");
        return new Result(source, journal, false);
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
