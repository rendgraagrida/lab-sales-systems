package dev.labtrial.salestools.crm;

import dev.labtrial.salestools.core.DomainException;
import dev.labtrial.salestools.identity.AccessControl;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Replay identity is checked after write authorization and before mutable-reference checks. */
@Component
public class CrmReceipts {
    private final JdbcTemplate db;
    public CrmReceipts(JdbcTemplate db) { this.db=db; }
    public record Key(String intent,String hash) {}
    public Key key(String intent,Object... fields) {
        intent=InquiryService.text(intent,"intent",200,true);
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(Object value:fields) {
                byte[] bytes=(value==null?"":value.toString()).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array());
                digest.update(bytes);
            }
            return new Key(intent,HexFormat.of().formatHex(digest.digest()));
        } catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public Long previous(AccessControl.Scope scope,String kind,Key key) {
        var rows=db.query("SELECT payload_hash,result_id FROM crm_command_receipts WHERE workspace_id=? AND entity_id=? AND command_kind=? AND intent=?",(r,n)->new Object[]{r.getString(1),r.getLong(2)},scope.workspaceId(),scope.entityId(),kind,key.intent());
        if(rows.isEmpty()) return null;
        if(!rows.get(0)[0].equals(key.hash())) throw new DomainException(409,"Intent already used with different input");
        return (Long)rows.get(0)[1];
    }
    public void record(AccessControl.Scope scope,String kind,Key key,long id) {
        db.update("INSERT INTO crm_command_receipts(workspace_id,entity_id,command_kind,intent,payload_hash,result_id,actor_id) VALUES (?,?,?,?,?,?,?)",scope.workspaceId(),scope.entityId(),kind,key.intent(),key.hash(),id,scope.actor().id());
    }
}
