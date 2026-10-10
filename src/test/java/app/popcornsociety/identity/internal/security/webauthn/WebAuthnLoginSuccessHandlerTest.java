package app.popcornsociety.identity.internal.security.webauthn;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import app.popcornsociety.identity.internal.security.CustomUserDetailsService;
import app.popcornsociety.shared.security.UserPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class WebAuthnLoginSuccessHandlerTest {
  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void revokedRoleCannotBePairedWithTheReloadedSecurityVersion() throws Exception {
    var users = mock(CustomUserDetailsService.class);
    var userRole = new SimpleGrantedAuthority("ROLE_USER");
    var current =
        new UserPrincipal(7L, null, null, "user", null, null, false, true, List.of(userRole), 4);
    when(users.loadUserByUsername("user")).thenReturn(current);
    var factor =
        FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY).build();
    var earlier =
        UsernamePasswordAuthenticationToken.authenticated(
            "user", null, List.of(userRole, new SimpleGrantedAuthority("ROLE_ADMIN"), factor));
    var repository = new HttpSessionSecurityContextRepository();
    var handler = new WebAuthnLoginSuccessHandler(users, repository);
    var request = new MockHttpServletRequest();
    handler.onAuthenticationSuccess(request, new MockHttpServletResponse(), earlier);
    var stored = repository.loadDeferredContext(request).get().getAuthentication();
    assertThat(stored.getPrincipal()).isSameAs(current);
    assertThat(stored.getAuthorities())
        .extracting(authority -> authority.getAuthority())
        .containsExactlyInAnyOrder("ROLE_USER", "FACTOR_WEBAUTHN");
    assertThat(stored.getAuthorities())
        .anySatisfy(authority -> assertThat(authority).isSameAs(factor));
  }
}
