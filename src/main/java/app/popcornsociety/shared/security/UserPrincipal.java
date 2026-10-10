package app.popcornsociety.shared.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class UserPrincipal implements UserDetails {

  private static final long serialVersionUID = 3317073886489748654L;

  private static final String ADMIN_ROLE = "ROLE_ADMIN";

  @JsonIgnore private final long securityVersion;
  private final Long id;
  private final String firstName;
  private final String lastName;
  private final String username;
  @JsonIgnore private final String email;
  @JsonIgnore private final String password;
  private final boolean locked;
  private final boolean enabled;
  private final Collection<? extends GrantedAuthority> authorities;

  public UserPrincipal(
      Long id,
      String firstName,
      String lastName,
      String username,
      String email,
      String password,
      boolean locked,
      boolean enabled,
      Collection<? extends GrantedAuthority> authorities) {
    this(id, firstName, lastName, username, email, password, locked, enabled, authorities, 0);
  }

  public UserPrincipal(
      Long id,
      String firstName,
      String lastName,
      String username,
      String email,
      String password,
      boolean locked,
      boolean enabled,
      Collection<? extends GrantedAuthority> authorities,
      long securityVersion) {
    this.securityVersion = securityVersion;
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
    this.username = username;
    this.email = email;
    this.password = password;
    this.locked = locked;
    this.enabled = enabled;

    if (authorities == null) {
      this.authorities = null;
    } else {
      this.authorities = new ArrayList<>(authorities);
    }
  }

  public long getSecurityVersion() {
    return securityVersion;
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities == null ? null : new ArrayList<>(authorities);
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return !locked;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) return true;
    if (!(object instanceof UserPrincipal that)) return false;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  public String getFirstName() {
    return firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public static boolean isCurrentAccountAdmin(UserPrincipal currentAccount) {
    return currentAccount.getAuthorities().contains(new SimpleGrantedAuthority(ADMIN_ROLE));
  }
}
