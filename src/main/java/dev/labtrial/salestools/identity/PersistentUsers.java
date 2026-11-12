package dev.labtrial.salestools.identity;

import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PersistentUsers implements UserDetailsService {
    private final JdbcTemplate db;
    public PersistentUsers(JdbcTemplate db) { this.db = db; }
    public static String canonical(String login) { return login == null ? "" : login.trim().toLowerCase(Locale.ROOT); }
    @Override public UserDetails loadUserByUsername(String username) {
        var users = db.query("SELECT id,login,security_version,password_hash,active FROM app_users WHERE login=?", (r,n) -> new IdentityPrincipal(r.getLong(1),r.getString(2),r.getLong(3),r.getString(4),r.getBoolean(5)), canonical(username));
        if (users.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        return users.get(0);
    }
}
