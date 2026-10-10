package app.popcornsociety.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.popcornsociety.identity.api.LoginRequest;
import app.popcornsociety.identity.api.RegistrationRequest;
import app.popcornsociety.support.BaseControllerIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.client.RestTestClient;

class AuthenticationControllerTest extends BaseControllerIntegrationTest {

  @Autowired private RestTestClient restTestClient;

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private PasswordEncoder passwordEncoder;

  @BeforeEach
  void clearSessions() {
    jdbcTemplate.update("delete from spring_session");
    String passwordHash = passwordEncoder.encode("Encrypted!Pa55worD");
    jdbcTemplate.update("update account set password = ? where id = 2", passwordHash);
    jdbcTemplate.update(
        """
        insert into local_credential(account_id, password_hash)
        values (2, ?)
        on conflict (account_id) do update set password_hash = excluded.password_hash
        """,
        passwordHash);
  }

  @Test
  void checkUsernameAvailability_existingUsername() {
    restTestClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/v1/auth/check-username-availability")
                    .queryParam("username", "test_user_one")
                    .build())
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectAll(
            spec -> spec.expectStatus().isOk(),
            spec -> spec.expectHeader().contentType(MediaType.APPLICATION_JSON),
            spec -> spec.expectBody().jsonPath("$.isAvailable").isEqualTo(false));
  }

  @Test
  void emailAvailabilityIsNotPublic() throws Exception {
    for (String email : new String[] {"one@gmail.com", "missing@example.com"}) {
      mockMvc
          .perform(get("/api/v1/auth/check-email-availability").param("email", email))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.isAvailable").doesNotExist());
    }
  }

  @Test
  void passwordResetExistingAndMissingEmailsHaveIdenticalHttpResponses() throws Exception {
    String existing =
        mockMvc
            .perform(
                post("/api/v1/auth/password-reset-requests")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"two@web.com\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String missing =
        mockMvc
            .perform(
                post("/api/v1/auth/password-reset-requests")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"missing@example.com\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(existing).isEqualTo(missing).doesNotContain("two@web.com", "missing@example.com");
  }

  @Test
  void registration_withoutEmailVerificationReturnsSignInInstruction() throws Exception {
    var request =
        new RegistrationRequest(
            "reg_feedback", "registration-feedback@example.com", "Encrypted!Pa55worD");

    mockMvc
        .perform(
            post("/api/v1/auth/registration")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.message").value("Account created. You can sign in now."));
  }

  @Test
  void login_successCreatesServerSessionAndReturnsCurrentAccount() throws Exception {
    var request = new LoginRequest("test_user_one", "Encrypted!Pa55worD");

    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("POPCORN_SESSION"))
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.username").value("test_user_one"))
            .andExpect(jsonPath("$.email").value("one@gmail.com"))
            .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn();

    Cookie session = login.getResponse().getCookie("POPCORN_SESSION");
    assertThat(session).isNotNull();
    assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session", Long.class))
        .isEqualTo(1L);

    // A browser presenting the old cookie name must authenticate again after the package rename.
    mockMvc
        .perform(
            get("/api/v1/auth/me")
                .cookie(new Cookie("SESSION", session.getValue()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(get("/api/v1/auth/me").cookie(session).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.username").value("test_user_one"));
  }

  @Test
  void login_sessionCookiePersistsAcrossBrowserRestarts() throws Exception {
    var request = new LoginRequest("test_user_one", "Encrypted!Pa55worD");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(cookie().maxAge("POPCORN_SESSION", 1_209_600));
  }

  @Test
  void login_usesLocalCredentialInsteadOfLegacyAccountPassword() throws Exception {
    String passwordHash = passwordEncoder.encode("Encrypted!Pa55worD");
    jdbcTemplate.update("update account set password = null where username = 'test_user_two'");
    jdbcTemplate.update(
        """
        insert into local_credential(account_id, password_hash)
        values (2, ?)
        on conflict (account_id) do update set password_hash = excluded.password_hash
        """,
        passwordHash);

    var request = new LoginRequest("test_user_two", "Encrypted!Pa55worD");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(2))
        .andExpect(jsonPath("$.username").value("test_user_two"));
  }

  @Test
  void login_withoutLocalCredentialReturnsUnauthorized() throws Exception {
    jdbcTemplate.update("delete from local_credential where account_id = 2");
    jdbcTemplate.update(
        "update account set password = ? where id = 2",
        passwordEncoder.encode("Encrypted!Pa55worD"));

    var request = new LoginRequest("test_user_two", "Encrypted!Pa55worD");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void login_badCredentials() throws Exception {
    var request = new LoginRequest("test_user_one", "Wrong!Pa55worD");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(
            jsonPath("$.detail").value("Sorry, you're not authorized to access this resource."))
        .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"));
  }

  @Test
  void login_withoutCsrfTokenIsForbidden() throws Exception {
    var request = new LoginRequest("test_user_one", "Encrypted!Pa55worD");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isForbidden());
  }

  @Test
  void me_unauthenticated() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void logout_deletesServerSessionAndRotatesCsrfToken() throws Exception {
    var request = new LoginRequest("test_user_one", "Encrypted!Pa55worD");
    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

    Cookie session = login.getResponse().getCookie("POPCORN_SESSION");
    assertThat(session).isNotNull();

    MvcResult logout =
        mockMvc
            .perform(post("/api/v1/auth/logout").with(csrf()).cookie(session))
            .andExpect(status().isOk())
            .andReturn();

    Cookie csrfToken =
        Arrays.stream(logout.getResponse().getCookies())
            .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
            .filter(cookie -> !cookie.getValue().isBlank())
            .findFirst()
            .orElse(null);
    assertThat(csrfToken).isNotNull();
    assertThat(csrfToken.getValue()).isNotBlank();

    assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session", Long.class))
        .isZero();
    mockMvc
        .perform(get("/api/v1/auth/me").cookie(session).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unsafeNonApiRequest_requiresCsrfToken() {
    restTestClient.post().uri("/csrf-protected").exchange().expectStatus().isForbidden();
  }
}
