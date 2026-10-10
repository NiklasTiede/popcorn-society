package app.popcornsociety.identity.internal.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.account.api.AccountSessionState;
import app.popcornsociety.shared.security.UserPrincipal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AccountSessionValidationFilterTest {
  private final AccountIdentityService accounts = mock(AccountIdentityService.class);
  private final AccountSessionValidationFilter filter =
      new AccountSessionValidationFilter(new AccountSessionValidator(accounts));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(strings = {"version", "deleted", "locked", "disabled", "unsupported-principal"})
  void rejectsStaleAuthenticationBeforeTheRequestCanUseIt(String change) throws Exception {
    var principal = principal();
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                change.equals("unsupported-principal") ? "unknown" : principal,
                null,
                principal.getAuthorities()));
    when(accounts.findSessionState(7L))
        .thenReturn(
            change.equals("deleted")
                ? Optional.empty()
                : Optional.of(
                    new AccountSessionState(
                        change.equals("version") ? 4 : 3,
                        change.equals("locked"),
                        !change.equals("disabled"))));
    var request = new MockHttpServletRequest();
    var session = new MockHttpSession();
    session.setAttribute("CONCIERGE_DELEGATION_KEY", "old-key");
    request.setSession(session);
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, response) ->
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
    assertThat(session.isInvalid()).isTrue();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void acceptsCurrentAuthenticationWithoutChangingTheSession() throws Exception {
    var principal = principal();
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, principal.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(accounts.findSessionState(7L))
        .thenReturn(Optional.of(new AccountSessionState(3, false, true)));
    var request = new MockHttpServletRequest();
    var session = new MockHttpSession();
    request.setSession(session);
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, response) ->
            assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isSameAs(authentication));
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void publicRequestsDoNotCreateASession() throws Exception {
    var request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {});
    assertThat(request.getSession(false)).isNull();
  }

  private UserPrincipal principal() {
    return new UserPrincipal(
        7L,
        null,
        null,
        "user",
        "user@example.com",
        null,
        false,
        true,
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
        3);
  }
}
