package app.popcornsociety.identity.internal.security.oauth2;

import app.popcornsociety.account.api.AccountIdentity;
import app.popcornsociety.account.api.AccountIdentityProviderLink;
import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.identity.internal.security.CustomUserDetailsService;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEventType;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEvents;
import app.popcornsociety.shared.security.UserPrincipal;
import jakarta.transaction.Transactional;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Service;

@Service
public class SocialAccountProvisioningService {

  private final AccountIdentityService accountIdentityService;
  private final CustomUserDetailsService userDetailsService;
  private final SecurityAuditEvents auditEvents;

  public SocialAccountProvisioningService(
      AccountIdentityService accountIdentityService,
      CustomUserDetailsService userDetailsService,
      SecurityAuditEvents auditEvents) {
    this.accountIdentityService = accountIdentityService;
    this.userDetailsService = userDetailsService;
    this.auditEvents = auditEvents;
  }

  @Transactional
  public UserPrincipal provision(SocialProviderIdentity socialIdentity) {
    Optional<AccountIdentityProviderLink> existingLink =
        accountIdentityService.findProviderLink(
            socialIdentity.provider(), socialIdentity.providerUserId());
    if (existingLink.isPresent()) {
      return loadPrincipal(existingLink.get().accountId());
    }

    if (!socialIdentity.emailVerified() || socialIdentity.email() == null) {
      auditEvents.recordCredentialEvent(
          SecurityAuditEventType.SOCIAL_PROVIDER_LINK_FAILED,
          null,
          Map.of("provider", socialIdentity.provider()));
      throw new OAuth2AuthenticationException("social_email_not_verified");
    }

    Optional<AccountIdentity> accountWithEmail =
        accountIdentityService.findOptionalByEmail(socialIdentity.email());
    if (accountWithEmail.isPresent()) {
      // Provider verification proves mailbox control, not ownership of existing credentials.
      // Never turn an email collision into an implicit account-linking operation.
      auditEvents.recordCredentialEvent(
          SecurityAuditEventType.SOCIAL_PROVIDER_LINK_FAILED,
          accountWithEmail.get().id(),
          Map.of("provider", socialIdentity.provider()));
      throw new OAuth2AuthenticationException("social_account_link_required");
    }

    AccountIdentity account =
        accountIdentityService.createSocialAccount(
            uniqueUsername(socialIdentity.email()), socialIdentity.email());

    accountIdentityService.linkProvider(
        account.id(),
        socialIdentity.provider(),
        socialIdentity.providerUserId(),
        socialIdentity.email());
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.SOCIAL_PROVIDER_LINKED,
        account.id(),
        Map.of("provider", socialIdentity.provider()));
    return loadPrincipal(account.id());
  }

  private String uniqueUsername(String email) {
    String localPart = email.substring(0, email.indexOf('@'));
    String candidate = localPart.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    if (candidate.isBlank()) {
      candidate = "user";
    }
    if (accountIdentityService.isUsernameAvailable(candidate)) {
      return candidate;
    }
    for (int suffix = 2; ; suffix++) {
      String suffixed = candidate + "_" + suffix;
      if (accountIdentityService.isUsernameAvailable(suffixed)) {
        return suffixed;
      }
    }
  }

  private UserPrincipal loadPrincipal(Long accountId) {
    UserDetails userDetails = userDetailsService.loadUserById(accountId);
    if (userDetails instanceof UserPrincipal userPrincipal) {
      return userPrincipal;
    }
    throw new OAuth2AuthenticationException("social_account_principal_not_supported");
  }
}
