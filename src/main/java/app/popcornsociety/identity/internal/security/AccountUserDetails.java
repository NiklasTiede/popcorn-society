package app.popcornsociety.identity.internal.security;

import app.popcornsociety.account.api.AccountCredentials;
import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.shared.security.UserPrincipal;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetails implements UserDetailsService, CustomUserDetailsService {

  private final AccountIdentityService accountIdentityService;

  public AccountUserDetails(AccountIdentityService accountIdentityService) {
    this.accountIdentityService = accountIdentityService;
  }

  @Override
  @Transactional
  public @NonNull UserDetails loadUserByUsername(String usernameOrEmail) {
    try {
      return createUserPrincipal(
          accountIdentityService.loadCredentialsByUsernameOrEmail(usernameOrEmail));
    } catch (RuntimeException ex) {
      throw new UsernameNotFoundException(
          "User not found with this username or email: %s".formatted(usernameOrEmail), ex);
    }
  }

  @Override
  @Transactional
  public UserDetails loadUserById(Long id) {
    try {
      return createUserPrincipal(accountIdentityService.loadCredentialsById(id));
    } catch (RuntimeException ex) {
      throw new UsernameNotFoundException("User not found with id: %s".formatted(id), ex);
    }
  }

  private UserPrincipal createUserPrincipal(AccountCredentials account) {
    return new UserPrincipal(
        account.id(),
        account.firstName(),
        account.lastName(),
        account.username(),
        account.email(),
        account.password(),
        account.locked(),
        account.enabled(),
        account.roleNames().stream().map(SimpleGrantedAuthority::new).toList(),
        account.securityVersion());
  }
}
