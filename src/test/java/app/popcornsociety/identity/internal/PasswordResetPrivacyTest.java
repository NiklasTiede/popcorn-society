package app.popcornsociety.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import app.popcornsociety.account.api.AccountIdentity;
import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.identity.api.events.PasswordResetRequested;
import app.popcornsociety.identity.internal.persistence.VerificationToken;
import app.popcornsociety.identity.internal.persistence.VerificationTokenRepository;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEvents;
import app.popcornsociety.shared.api.MessageResponse;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordResetPrivacyTest {
  private final AccountIdentityService accounts = mock(AccountIdentityService.class);
  private final VerificationTokenRepository tokens = mock(VerificationTokenRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private IdentityAccess identity;

  @BeforeEach
  void setUp() {
    identity =
        new IdentityAccess(
            mock(PasswordEncoder.class),
            accounts,
            tokens,
            new TokenHasher(),
            mock(SecurityAuditEvents.class),
            events,
            new IdentityProperties("http://localhost:8080", "http://localhost:3000", true, null),
            Clock.systemUTC());
  }

  @Test
  void existingAndMissingEmailsReceiveTheSameResponse() {
    var account = new AccountIdentity(7L, "movie_fan", "existing@example.com");
    when(accounts.findOptionalByEmail("existing@example.com")).thenReturn(Optional.of(account));
    when(accounts.findOptionalByEmail("missing@example.com")).thenReturn(Optional.empty());

    MessageResponse existing = identity.resetPassword("existing@example.com");
    MessageResponse missing = identity.resetPassword("missing@example.com");

    assertThat(existing).isEqualTo(missing);
    assertThat(existing.message())
        .isEqualTo(
            "If an account exists for this email, password reset instructions will be sent.");
    assertThat(existing.message()).doesNotContain(account.email(), account.username());
    verify(tokens, times(1)).save(any(VerificationToken.class));
    var reset = ArgumentCaptor.forClass(PasswordResetRequested.class);
    verify(events, times(1)).publishEvent(reset.capture());
    assertThat(reset.getValue().emailAddress()).isEqualTo(account.email());
    assertThat(reset.getValue().link()).startsWith("http://localhost:3000/reset-password?token=");
  }

  @Test
  void missingEmailDoesNotIssueATokenOrSendMail() {
    when(accounts.findOptionalByEmail("missing@example.com")).thenReturn(Optional.empty());

    identity.resetPassword("missing@example.com");

    verifyNoInteractions(tokens, events);
  }
}
