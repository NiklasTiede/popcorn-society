package app.popcornsociety.identity.internal.security;

import static org.assertj.core.api.Assertions.*;

import app.popcornsociety.shared.security.UserPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

class SessionConciergeDelegationTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
  private final MapSessionRepository sessions = new MapSessionRepository(new ConcurrentHashMap<>());
  private final AccountSessionValidator validator =
      org.mockito.Mockito.mock(AccountSessionValidator.class);

  @org.junit.jupiter.api.BeforeEach
  void activeAccounts() {
    org.mockito.Mockito.when(validator.isCurrent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              UserPrincipal user = invocation.getArgument(0);
              return user != null && user.isEnabled() && user.isAccountNonLocked();
            });
  }

  private final SessionConciergeDelegation service =
      new SessionConciergeDelegation(sessions, clock, validator);

  @Test
  void verifiesOnlyTheSignedSessionActorAndAllowedScope() {
    var fixture = fixture(7L);
    var actor = service.verify(fixture.token(), "watchlist:add");
    assertThat(actor.accountId()).isEqualTo(7L);
    for (String scope :
        List.of("watchlist:remove", "ratings:read", "ratings:set", "ratings:remove"))
      assertThat(service.verify(fixture.token(), scope)).isEqualTo(actor);
    assertThat(actor.sessionBinding())
        .isNotEqualTo(service.verify(fixture(8L).token(), "watchlist:read").sessionBinding());
    assertThatThrownBy(() -> service.verify(fixture.token(), "account:delete"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.verify(fixture.token() + "x", "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void logoutAndExpiryRevokeEvenAnOtherwiseValidCredential() {
    var fixture = fixture(7L);
    var later =
        new SessionConciergeDelegation(
            sessions, Clock.offset(clock, Duration.ofSeconds(301)), validator);
    assertThatThrownBy(() -> later.verify(fixture.token(), "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
    sessions.deleteById(fixture.session().getId());
    assertThatThrownBy(() -> service.verify(fixture.token(), "watchlist:add"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void changedPrincipalCannotReuseThePreviousAccountsGrant() {
    var fixture = fixture(7L);
    var session = sessions.findById(fixture.session().getId());
    session.setAttribute("SPRING_SECURITY_CONTEXT", context(8L));
    sessions.save(session);
    assertThatThrownBy(() -> service.verify(fixture.token(), "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void malformedAndAnonymousCredentialsFailClosed() {
    for (String token : List.of("", "garbage", "abc.def", "a.b.c")) {
      assertThatThrownBy(() -> service.verify(token, "watchlist:read"))
          .isInstanceOf(AccessDeniedException.class);
    }
    assertThatThrownBy(() -> service.verify(null, "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void onlyActiveUsersWithAnExistingSessionCanReceiveDelegation() {
    assertThatThrownBy(() -> service.issue(null, user(7L)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.issue(new MockHttpSession(), null))
        .isInstanceOf(AccessDeniedException.class);
    for (var principal :
        List.of(principal(true, true, "ROLE_USER"), principal(false, false, "ROLE_USER"))) {
      assertThatThrownBy(() -> service.issue(new MockHttpSession(), principal))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void renewingDelegationReusesTheSessionKeyAndExpiresAtTheExactDeadline() {
    var http = new MockHttpSession();
    var initial = service.issue(http, user(7L));
    var key = http.getAttribute("CONCIERGE_DELEGATION_KEY");
    var renewed = service.issue(http, user(7L));
    assertThat(http.getAttribute("CONCIERGE_DELEGATION_KEY")).isEqualTo(key);
    assertThat(renewed).isEqualTo(initial);
    assertThat(initial.expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
    var fixture = fixture(7L);
    var deadline =
        new SessionConciergeDelegation(
            sessions, Clock.offset(clock, Duration.ofMinutes(5)), validator);
    assertThatThrownBy(() -> deadline.verify(fixture.token(), "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "session|7|9999999999|movie-concierge",
        "session|7|9999999999|different-audience|watchlist:read,watchlist:add,watchlist:remove,ratings:set,ratings:remove",
        "session|7|9999999999|movie-concierge|account:delete",
        "session|7|not-a-timestamp|movie-concierge|watchlist:read,watchlist:add,watchlist:remove,ratings:read,ratings:set,ratings:remove"
      })
  void invalidClaimsAreRejectedBeforeReadingASession(String claims) {
    var repository = org.mockito.Mockito.mock(StoredSessions.class);
    var verifier = new SessionConciergeDelegation(repository, clock, validator);
    var token =
        java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + ".invalid";
    assertThatThrownBy(() -> verifier.verify(token, "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void oversizedCredentialsAreRejected() {
    assertThatThrownBy(() -> service.verify("x".repeat(1025), "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "missing-key", "missing-context", "missing-authentication", "unauthenticated",
        "wrong-principal", "disabled", "locked", "wrong-role"
      })
  void revokedOrUnusableSessionStateRejectsAnOtherwiseSignedToken(String condition) {
    var fixture = fixture(7L);
    var session = sessions.findById(fixture.session().getId());
    var context = context(7L);
    switch (condition) {
      case "missing-key" -> session.removeAttribute("CONCIERGE_DELEGATION_KEY");
      case "missing-context" -> context = null;
      case "missing-authentication" -> context.setAuthentication(null);
      case "unauthenticated" ->
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.unauthenticated(user(7L), null));
      case "wrong-principal" ->
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated("anonymous", null, List.of()));
      default -> {
        var principal =
            principal(
                condition.equals("locked"),
                !condition.equals("disabled"),
                condition.equals("wrong-role") ? "ROLE_GUEST" : "ROLE_USER");
        context.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
      }
    }
    session.setAttribute("SPRING_SECURITY_CONTEXT", context);
    sessions.save(session);
    assertThatThrownBy(() -> service.verify(fixture.token(), "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void anExpiredSessionIsRejectedEvenIfTheRepositoryReturnsIt() {
    var fixture = fixture(7L);
    var session = fixture.session();
    session.setLastAccessedTime(Instant.EPOCH);
    var repository = org.mockito.Mockito.mock(StoredSessions.class);
    org.mockito.Mockito.when(repository.findById(session.getId())).thenReturn(session);
    var verifier = new SessionConciergeDelegation(repository, clock, validator);
    assertThatThrownBy(() -> verifier.verify(fixture.token(), "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void administratorSessionsMayUsePersonalTools() {
    var fixture = fixture(7L);
    var session = sessions.findById(fixture.session().getId());
    var principal = principal(false, true, "ROLE_ADMIN");
    var context = context(7L);
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, principal.getAuthorities()));
    session.setAttribute("SPRING_SECURITY_CONTEXT", context);
    sessions.save(session);
    assertThat(service.verify(fixture.token(), "ratings:set").accountId()).isEqualTo(7L);
  }

  private UserPrincipal principal(boolean locked, boolean enabled, String role) {
    return new UserPrincipal(
        7L,
        null,
        null,
        "fixture",
        null,
        null,
        locked,
        enabled,
        List.of(new SimpleGrantedAuthority(role)));
  }

  private Fixture fixture(Long id) {
    var http = new MockHttpSession();
    var token = service.issue(http, user(id)).token();
    var stored = new MapSession(http.getId());
    stored.setAttribute("CONCIERGE_DELEGATION_KEY", http.getAttribute("CONCIERGE_DELEGATION_KEY"));
    stored.setAttribute("SPRING_SECURITY_CONTEXT", context(id));
    sessions.save(stored);
    return new Fixture(stored, token);
  }

  private org.springframework.security.core.context.SecurityContext context(Long id) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            user(id), null, user(id).getAuthorities()));
    return context;
  }

  private UserPrincipal user(Long id) {
    return new UserPrincipal(
        id,
        null,
        null,
        "fixture",
        null,
        null,
        false,
        true,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private interface StoredSessions
      extends org.springframework.session.SessionRepository<MapSession> {}

  private record Fixture(MapSession session, String token) {}
}
