package app.popcornsociety.identity.internal.security.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.popcornsociety.account.api.AccountIdentity;
import app.popcornsociety.account.api.AccountIdentityProviderLink;
import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.identity.internal.security.CustomUserDetailsService;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEventType;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEvents;
import app.popcornsociety.shared.security.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class SocialAccountProvisioningServiceTest {

  @Mock private AccountIdentityService accountIdentityService;

  @Mock private CustomUserDetailsService userDetailsService;

  @Mock private SecurityAuditEvents auditEvents;

  private SocialAccountProvisioningService provisioningService;

  @BeforeEach
  void setUp() {
    provisioningService =
        new SocialAccountProvisioningService(
            accountIdentityService, userDetailsService, auditEvents);
  }

  @Test
  void provision_existingProviderLinkLoadsLinkedAccount() {
    UserPrincipal principal = principal(7L, "linked_user", "linked@example.com");
    when(accountIdentityService.findProviderLink("google", "google-subject"))
        .thenReturn(
            Optional.of(
                new AccountIdentityProviderLink(
                    7L, "google", "google-subject", "linked@example.com")));
    when(userDetailsService.loadUserById(7L)).thenReturn(principal);

    UserPrincipal result =
        provisioningService.provision(
            new SocialProviderIdentity(
                "google", "google-subject", "other@example.com", true, Map.of()));

    assertThat(result).isSameAs(principal);
    verify(accountIdentityService, never())
        .linkProvider(7L, "google", "google-subject", "other@example.com");
  }

  @Test
  void provision_verifiedProviderEmailCannotClaimExistingAccount() {
    when(accountIdentityService.findProviderLink("google", "google-subject"))
        .thenReturn(Optional.empty());
    when(accountIdentityService.findOptionalByEmail("two@web.com"))
        .thenReturn(Optional.of(new AccountIdentity(2L, "test_user_two", "two@web.com")));
    // Allow the vulnerable path to finish so the assertion detects the missing rejection.
    org.mockito.Mockito.lenient()
        .when(userDetailsService.loadUserById(2L))
        .thenReturn(principal(2L, "test_user_two", "two@web.com"));

    assertThatThrownBy(
            () ->
                provisioningService.provision(
                    new SocialProviderIdentity(
                        "google", "google-subject", "two@web.com", true, Map.of())))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(
            error ->
                assertThat(((OAuth2AuthenticationException) error).getError().getErrorCode())
                    .isEqualTo("social_account_link_required"));
    verify(accountIdentityService, never())
        .linkProvider(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    verify(userDetailsService, never()).loadUserById(org.mockito.ArgumentMatchers.anyLong());
    verify(auditEvents)
        .recordCredentialEvent(
            SecurityAuditEventType.SOCIAL_PROVIDER_LINK_FAILED, 2L, Map.of("provider", "google"));
  }

  @Test
  void provision_newVerifiedEmailCreatesSocialAccountWithUniqueUsername() {
    UserPrincipal principal = principal(11L, "social", "social@example.com");
    when(accountIdentityService.findProviderLink("github", "12345")).thenReturn(Optional.empty());
    when(accountIdentityService.findOptionalByEmail("social@example.com"))
        .thenReturn(Optional.empty());
    when(accountIdentityService.isUsernameAvailable("social")).thenReturn(false);
    when(accountIdentityService.isUsernameAvailable("social_2")).thenReturn(true);
    when(accountIdentityService.createSocialAccount("social_2", "social@example.com"))
        .thenReturn(new AccountIdentity(11L, "social_2", "social@example.com"));
    when(userDetailsService.loadUserById(11L)).thenReturn(principal);

    UserPrincipal result =
        provisioningService.provision(
            new SocialProviderIdentity("github", "12345", "social@example.com", true, Map.of()));

    assertThat(result).isSameAs(principal);
    verify(accountIdentityService).linkProvider(11L, "github", "12345", "social@example.com");
  }

  @Test
  void provision_unverifiedEmailFailsWithoutLinking() {
    when(accountIdentityService.findProviderLink("github", "12345")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                provisioningService.provision(
                    new SocialProviderIdentity(
                        "github", "12345", "private@example.com", false, Map.of())))
        .isInstanceOf(OAuth2AuthenticationException.class);

    verify(accountIdentityService, never())
        .linkProvider(2L, "github", "12345", "private@example.com");
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.captor();
    verify(auditEvents)
        .recordCredentialEvent(
            eq(SecurityAuditEventType.SOCIAL_PROVIDER_LINK_FAILED), isNull(), details.capture());
    assertThat(details.getValue()).containsEntry("provider", "github");
  }

  private UserPrincipal principal(Long id, String username, String email) {
    return new UserPrincipal(
        id,
        null,
        null,
        username,
        email,
        null,
        false,
        true,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }
}
