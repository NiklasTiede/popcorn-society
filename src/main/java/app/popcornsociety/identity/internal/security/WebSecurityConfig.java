package app.popcornsociety.identity.internal.security;

import app.popcornsociety.identity.internal.IdentityProperties;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEvents;
import app.popcornsociety.identity.internal.security.oauth2.AccountLinkingOAuth2UserService;
import app.popcornsociety.identity.internal.security.oauth2.AccountLinkingOidcUserService;
import app.popcornsociety.identity.internal.security.ratelimit.AuthRateLimitFilter;
import app.popcornsociety.identity.internal.security.webauthn.AuditingUserCredentialRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(jsr250Enabled = true, securedEnabled = true)
public class WebSecurityConfig {

  private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;
  private final IdentityProperties identityProperties;
  private final AccountLinkingOidcUserService oidcUserService;
  private final AccountLinkingOAuth2UserService oauth2UserService;
  private final AuthRateLimitFilter authRateLimitFilter;

  public WebSecurityConfig(
      ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
      IdentityProperties identityProperties,
      AccountLinkingOidcUserService oidcUserService,
      AccountLinkingOAuth2UserService oauth2UserService,
      AuthRateLimitFilter authRateLimitFilter) {
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.identityProperties = identityProperties;
    this.oidcUserService = oidcUserService;
    this.oauth2UserService = oauth2UserService;
    this.authRateLimitFilter = authRateLimitFilter;
  }

  @Bean
  @Order(2)
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      ProblemDetailAccessDeniedHandler accessDeniedHandler,
      AccountSessionValidator sessionValidator)
      throws Exception {
    CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();

    http.addFilterAfter(
            new AccountSessionValidationFilter(sessionValidator),
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                    .ignoringRequestMatchers(
                        "/api/v1/observability/frontend", "/api/v1/search/movies"))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterAfter(authRateLimitFilter, CsrfCookieFilter.class)
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .securityContext(context -> context.securityContextRepository(securityContextRepository()))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .deleteCookies(JdbcSessionConfig.SESSION_COOKIE_NAME)
                    .logoutSuccessHandler(
                        new CsrfTokenRefreshingLogoutSuccessHandler(csrfTokenRepository))
                    .permitAll())
        .authorizeHttpRequests(
            ar ->
                ar.requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/auth/check-username-availability",
                        "/api/v1/auth/check-email-availability")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        "/api/v1/auth/registration",
                        "/api/v1/auth/password-resets",
                        "/api/v1/auth/email-confirmations",
                        "/api/v1/auth/password-reset-requests")
                    .permitAll()
                    .requestMatchers("/oauth2/**", "/login/oauth2/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/webauthn/authenticate/options", "/login/webauthn")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/webauthn/register/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/webauthn/register/*")
                    .authenticated()
                    .requestMatchers(
                        "/v3/api-docs",
                        "/v3/api-docs.yaml",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/v3/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/movies/**",
                        "/api/v1/recommendations/**",
                        "/api/v1/comments/**",
                        "/api/v1/accounts/summaries",
                        "/api/v1/accounts/*/profile",
                        "/api/v1/accounts/*/comments",
                        "/api/v1/accounts/*/watchlist",
                        "/api/v1/accounts/*/ratings",
                        "/api/v1/accounts/*/library/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/recommendations/home-feed")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/recommendations/discovery-events")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/recommendations/tonight")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/observability/frontend")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/search/movies")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .userInfoEndpoint(
                        userInfo ->
                            userInfo
                                .oidcUserService(oidcUserService)
                                .userService(oauth2UserService))
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/login?error=social"))
        .webAuthn(
            webAuthn ->
                webAuthn
                    .rpName("Popcorn Society")
                    .rpId(identityProperties.webauthn().rpId())
                    .allowedOrigins(
                        identityProperties.webauthn().allowedOrigins().toArray(String[]::new))
                    .disableDefaultRegistrationPage(true))
        .headers(
            headers ->
                headers
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .permissionsPolicyHeader(
                        permissions -> permissions.policy("publickey-credentials-get=(self)")))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable);

    return http.build();
  }

  @Bean
  public SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  public AuthenticationManager authenticationManager(
      AuthenticationConfiguration authenticationConfiguration) throws Exception {
    return authenticationConfiguration.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public PublicKeyCredentialUserEntityRepository publicKeyCredentialUserEntityRepository(
      JdbcOperations jdbcOperations) {
    return new JdbcPublicKeyCredentialUserEntityRepository(jdbcOperations);
  }

  @Bean
  public UserCredentialRepository userCredentialRepository(
      JdbcOperations jdbcOperations, SecurityAuditEvents auditEvents) {
    return new AuditingUserCredentialRepository(
        new JdbcUserCredentialRepository(jdbcOperations), auditEvents);
  }
}
