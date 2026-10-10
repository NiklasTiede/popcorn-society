package app.popcornsociety.identity.internal.security;

import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.shared.security.UserPrincipal;
import org.springframework.stereotype.Service;

/** Checks authoritative account state on every session or delegation use. */
@Service
public class AccountSessionValidator {
  private final AccountIdentityService accounts;

  public AccountSessionValidator(AccountIdentityService accounts) {
    this.accounts = accounts;
  }

  public boolean isCurrent(UserPrincipal principal) {
    return principal != null
        && principal.isEnabled()
        && principal.isAccountNonLocked()
        && accounts
            .findSessionState(principal.getId())
            .filter(
                state ->
                    state.enabled()
                        && !state.locked()
                        && state.securityVersion() == principal.getSecurityVersion())
            .isPresent();
  }
}
