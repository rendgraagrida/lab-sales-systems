package dev.labtrial.salestools.identity;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class IdentityPrincipal implements UserDetails, CredentialsContainer, Serializable {
    private final long id;
    private final String login;
    private final long securityVersion;
    private final boolean active;
    private String passwordHash;
    public IdentityPrincipal(long id, String login, long securityVersion, String passwordHash, boolean active) {
        this.id=id; this.login=login; this.securityVersion=securityVersion; this.passwordHash=passwordHash; this.active=active;
    }
    public long id() { return id; }
    public String login() { return login; }
    public long securityVersion() { return securityVersion; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return login; }
    @Override public boolean isEnabled() { return active; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public void eraseCredentials() { passwordHash=null; }
}
