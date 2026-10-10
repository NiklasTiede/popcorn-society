package app.popcornsociety.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.identity.api.ConciergeDelegation;
import app.popcornsociety.support.BaseContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SessionRevocationIntegrationTest extends BaseContainers {
  @Autowired private MockMvc mvc;
  @Autowired private AccountIdentityService accounts;
  @Autowired private PasswordEncoder passwords;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConciergeDelegation delegations;
  @Autowired private app.popcornsociety.identity.api.AuthenticationService authentication;

  @Autowired
  private app.popcornsociety.identity.internal.persistence.VerificationTokenRepository tokens;

  @Autowired private app.popcornsociety.identity.internal.TokenHasher hasher;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void revokedAdministratorCannotRestoreItsRoleUsingAnOldCookie() throws Exception {
    var account = account();
    jdbc.update("insert into account_roles(account_id, roles_id) values (?, 1)", account.id());
    Cookie first = login(account.username());
    Cookie second = login(account.username());
    String grant = grant(first);
    mvc.perform(
            delete("/api/v1/accounts/" + account.username() + "/roles/admin")
                .cookie(first)
                .with(csrf()))
        .andExpect(status().isOk());
    assertThatThrownBy(() -> delegations.verify(grant, "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
    for (Cookie cookie : new Cookie[] {first, second}) {
      mvc.perform(
              put("/api/v1/accounts/" + account.username() + "/roles/admin")
                  .cookie(cookie)
                  .with(csrf()))
          .andExpect(status().isUnauthorized());
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from account_roles where account_id = ? and roles_id = 1",
                Long.class,
                account.id()))
        .isZero();
    mvc.perform(get("/api/v1/auth/me").cookie(login(account.username())))
        .andExpect(status().isOk());
  }

  @Test
  void passwordChangeRejectsAllExistingCookiesAndDelegations() throws Exception {
    var account = account();
    Cookie first = login(account.username());
    Cookie second = login(account.username());
    String grant = grant(first);
    String raw = hasher.newRawToken();
    var token =
        new app.popcornsociety.identity.internal.persistence.VerificationToken(
            app.popcornsociety.identity.internal.persistence.VerificationTypeEnum.PASSWORD_RESET,
            hasher.hash(raw),
            java.time.Instant.now().plusSeconds(600),
            account.id());
    token.setConfirmedAtInUtc(java.time.Instant.now());
    tokens.save(token);
    authentication.saveNewPassword(
        new app.popcornsociety.identity.api.PasswordResetRequest(raw, "Replacement!Pa55word"));
    assertThatThrownBy(() -> delegations.verify(grant, "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
    for (Cookie cookie : new Cookie[] {first, second})
      mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"deleted", "locked", "disabled"})
  void unusableAccountsLoseCookiesAndDelegations(String condition) throws Exception {
    var account = account();
    Cookie cookie = login(account.username());
    String grant = grant(cookie);
    switch (condition) {
      case "deleted" ->
          mvc.perform(delete("/api/v1/accounts/" + account.username()).cookie(cookie).with(csrf()))
              .andExpect(status().isNoContent());
      case "locked" -> jdbc.update("update account set locked = true where id = ?", account.id());
      default -> jdbc.update("update account set enabled = false where id = ?", account.id());
    }
    // Reactivation must not resurrect credentials issued before the lock/disable transition.
    if (!condition.equals("deleted")) {
      jdbc.update("update account set locked = false, enabled = true where id = ?", account.id());
    }
    assertThatThrownBy(() -> delegations.verify(grant, "watchlist:read"))
        .isInstanceOf(AccessDeniedException.class);
    mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  private app.popcornsociety.account.api.AccountIdentity account() {
    String name = "revoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    return accounts.createAccountForIdentity(
        name, name + "@example.com", passwords.encode("Original!Pa55word"), true);
  }

  private Cookie login(String username) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of(
                                "usernameOrEmail", username, "password", "Original!Pa55word"))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie("POPCORN_SESSION");
    assertThat(cookie).isNotNull();
    return cookie;
  }

  private String grant(Cookie cookie) throws Exception {
    var result =
        mvc.perform(post("/api/v1/auth/concierge-delegation").cookie(cookie).with(csrf()))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }
}
