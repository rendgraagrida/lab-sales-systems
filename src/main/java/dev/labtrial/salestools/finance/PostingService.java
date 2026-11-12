package dev.labtrial.salestools.finance;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostingService {
    public record Result(Long journalId, boolean replayed) {}
    private final JdbcTemplate db;
    private final AccessControl access;
    public PostingService(JdbcTemplate db, AccessControl access) { this.db = db; this.access = access; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result post(AccessControl.Scope scope, String sourceType, String sourceId, String postingKey,
                       LocalDate date, String currency, String description, List<Ledger.Line> lines) {
        return postInternal(scope, sourceType, sourceId, postingKey, date, currency, description, lines, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result reverse(AccessControl.Scope scope, long originalJournalId, String postingKey, LocalDate date, String reason) {
        scope = checkedScope(scope);
        if (originalJournalId <= 0 || date == null || reason == null || reason.isBlank() || reason.length() > 500)
            throw new DomainException(400, "Invalid reversal");
        var originals = db.query("select posting_date,currency from journal_entries where workspace_id=? and entity_id=? and id=? and state='POSTED' for update",
                (rs,n) -> new Object[]{rs.getObject(1,LocalDate.class),rs.getString(2)}, scope.workspaceId(), scope.entityId(), originalJournalId);
        if (originals.size() != 1) throw new DomainException(404, "Journal not found");
        var original = originals.get(0);
        LocalDate originalDate = (LocalDate) original[0];
        if (date.isBefore(originalDate)) throw new DomainException(400, "Reversal predates original journal");
        var lines = db.query("select account_id,credit,debit from journal_lines where workspace_id=? and entity_id=? and journal_id=? order by id", (rs,n) -> new Ledger.Line(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), scope.workspaceId(), scope.entityId(), originalJournalId);
        return postInternal(scope, "REVERSAL", Long.toString(originalJournalId), postingKey, date,
                (String) original[1], reason, lines, originalJournalId);
    }

    private Result postInternal(AccessControl.Scope supplied, String sourceType, String sourceId, String postingKey,
                                LocalDate date, String currency, String description, List<Ledger.Line> lines, Long reverseOf) {
        AccessControl.Scope scope = checkedScope(supplied);
        if (!text(sourceType, 80) || !text(sourceId, 120) || !text(postingKey, 160) || date == null || !text(currency, 3)
                || description == null || description.length() > 500 || lines == null || lines.size() > 300)
            throw new DomainException(400, "Invalid posting");
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (Ledger.Line line : lines) {
            if (line == null || line.accountId() <= 0 || !amount(line.debit()) || !amount(line.credit())
                    || (line.debit().signum() > 0 && line.credit().signum() > 0))
                throw new DomainException(400, "Invalid journal line");
            debit = debit.add(line.debit()); credit = credit.add(line.credit());
        }
        if (debit.compareTo(credit) != 0 || (debit.signum() > 0 && lines.size() < 2))
            throw new DomainException(400, "Journal must balance");
        String hash = hash(sourceType, sourceId, postingKey, date, currency, description, lines, reverseOf);
        var prior = db.queryForList("select id,business_posting_hash,state from journal_entries where workspace_id=? and entity_id=? and posting_key=?", scope.workspaceId(), scope.entityId(), postingKey);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.get(0).get("business_posting_hash")) || !"POSTED".equals(prior.get(0).get("state"))) throw new DomainException(409, "Posting key conflicts with existing journal");
            return new Result(((Number) prior.get(0).get("id")).longValue(), true);
        }
        var entities = db.queryForList("select base_currency from legal_entities where workspace_id=? and id=? and active for update", scope.workspaceId(), scope.entityId());
        if (entities.size() != 1) throw new DomainException(404, "Entity not found");
        if (!currency.equals(entities.get(0).get("base_currency"))) throw new DomainException(400, "Currency must match entity");
        var periods = db.queryForList("select status from fiscal_periods where workspace_id=? and entity_id=? and ? between starts_on and ends_on for update", scope.workspaceId(), scope.entityId(), date);
        if (periods.size() != 1 || !"OPEN".equals(periods.get(0).get("status"))) throw new DomainException(409, "Posting requires an open fiscal period");
        if (debit.signum()==0) return new Result(null, false);
        if (reverseOf != null && !db.queryForList("select id from journal_entries where workspace_id=? and entity_id=? and reverse_of=?", scope.workspaceId(), scope.entityId(), reverseOf).isEmpty())
            throw new DomainException(409, "Journal already reversed");
        for (Ledger.Line line : lines) {
            if (line.debit().signum()==0 && line.credit().signum()==0) continue;
            Integer count = db.queryForObject("select count(*) from chart_accounts where workspace_id=? and entity_id=? and id=?" + (reverseOf == null ? " and active" : ""), Integer.class, scope.workspaceId(), scope.entityId(), line.accountId());
            if (count == null || count != 1) throw new DomainException(400, "Invalid account");
        }
        db.update("insert into document_sequences(workspace_id,entity_id,document_type,next_value) values (?,?,'BUSINESS',1) on conflict (workspace_id,entity_id,document_type) do nothing", scope.workspaceId(), scope.entityId());
        Long sequence = db.queryForObject("update document_sequences set next_value=next_value+1,version=version+1 where workspace_id=? and entity_id=? and document_type='BUSINESS' returning next_value-1", Long.class, scope.workspaceId(), scope.entityId());
        Long journal = db.queryForObject("insert into journal_entries(workspace_id,entity_id,number,source_type,source_id,posting_key,posting_date,currency,description,state,created_by,business_posting_hash,reverse_of) values (?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?) returning id", Long.class, scope.workspaceId(), scope.entityId(), "BUS-" + sequence, sourceType, sourceId, postingKey, date, currency, description, scope.actor().id(), hash, reverseOf);
        for (Ledger.Line line : lines) if (line.debit().signum()!=0 || line.credit().signum()!=0) db.update("insert into journal_lines(workspace_id,entity_id,journal_id,account_id,debit,credit) values (?,?,?,?,?,?)", scope.workspaceId(), scope.entityId(), journal, line.accountId(), line.debit(), line.credit());
        db.update("update journal_entries set state='POSTED',posted_at=now(),version=version+1 where id=?", journal);
        return new Result(journal, false);
    }
    private AccessControl.Scope checkedScope(AccessControl.Scope scope) {
        if (scope == null || scope.entityId() == null) throw new DomainException(400, "Entity scope required");
        AccessControl.Scope current = access.requireContext(scope.workspaceId(), scope.entityId(), true);
        if (scope.actor() == null || scope.actor().id() != current.actor().id()) throw new DomainException(403, "Invalid posting scope");
        return current;
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static boolean amount(BigDecimal value) { return value != null && value.signum() >= 0 && value.scale() <= 2 && value.scale() >= -18 && (long)value.precision() - value.scale() <= 16; }
    private static String hash(String sourceType, String sourceId, String key, LocalDate date, String currency, String description, List<Ledger.Line> lines, Long reverseOf) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String s : List.of(sourceType, sourceId, key, date.toString(), currency, description, reverseOf == null ? "" : reverseOf.toString())) put(d, s);
            for (Ledger.Line line : lines) { put(d, Long.toString(line.accountId())); put(d, line.debit().setScale(2).toPlainString()); put(d, line.credit().setScale(2).toPlainString()); }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void put(MessageDigest d, String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); d.update(ByteBuffer.allocate(4).putInt(b.length).array()); d.update(b); }
}
