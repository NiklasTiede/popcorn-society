package app.popcornsociety.identity.internal.security.webauthn;

import app.popcornsociety.identity.internal.security.CustomUserDetailsService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class WebAuthnLoginSuccessHandler implements AuthenticationSuccessHandler {

  private final CustomUserDetailsService userDetailsService;
  private final SecurityContextRepository securityContextRepository;

  public WebAuthnLoginSuccessHandler(
      CustomUserDetailsService userDetailsService,
      SecurityContextRepository securityContextRepository) {
    this.userDetailsService = userDetailsService;
    this.securityContextRepository = securityContextRepository;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    if (request.getSession(false) != null) {
      request.changeSessionId();
    }

    UserDetails userDetails = userDetailsService.loadUserByUsername(authentication.getName());
    var authorities = new ArrayList<GrantedAuthority>(userDetails.getAuthorities());
    authentication.getAuthorities().stream()
        .filter(FactorGrantedAuthority.class::isInstance)
        .forEach(authorities::add);
    UsernamePasswordAuthenticationToken normalizedAuthentication =
        new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
    normalizedAuthentication.setDetails(authentication.getDetails());

    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(normalizedAuthentication);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
    response.setStatus(HttpStatus.OK.value());
  }
}
