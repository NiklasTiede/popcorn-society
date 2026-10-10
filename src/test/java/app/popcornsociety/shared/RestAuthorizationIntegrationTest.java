package app.popcornsociety.shared;

import static app.popcornsociety.support.SecurityMockUsers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import app.popcornsociety.account.api.RoleService;
import app.popcornsociety.account.internal.persistence.AccountRepository;
import app.popcornsociety.engagement.internal.persistence.Comment;
import app.popcornsociety.engagement.internal.persistence.CommentRepository;
import app.popcornsociety.shared.security.UserPrincipal;
import app.popcornsociety.support.BaseControllerIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RestAuthorizationIntegrationTest extends BaseControllerIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired CommentRepository comments;
  @Autowired AccountRepository accounts;
  @Autowired RoleService roles;

  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.junit.jupiter.api.BeforeEach
  void persistedOtherAccount() {
    jdbc.update(
        "insert into account(id, username, email, locked, enabled) values (3, 'other', 'other@example.com', false, true)");
    jdbc.update("insert into account_roles(account_id, roles_id) values (3, 2)");
  }

  enum Actor {
    ANONYMOUS,
    OWNER,
    OTHER_USER,
    ADMIN
  }

  private RequestPostProcessor actor(Actor actor) {
    return switch (actor) {
      case ANONYMOUS -> anonymous();
      case OWNER -> testUser();
      // Only ADMIN: verifies that admin access does not depend on a second role.
      case ADMIN ->
          user(
              new UserPrincipal(
                  1L,
                  "Test",
                  "Admin",
                  "test_user_one",
                  "one@gmail.com",
                  "",
                  false,
                  true,
                  List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
      case OTHER_USER ->
          user(
              new UserPrincipal(
                  3L,
                  "Other",
                  "User",
                  "other",
                  "other@example.com",
                  "",
                  false,
                  true,
                  List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    };
  }

  private int statusFor(Actor actor, int allowed) {
    return switch (actor) {
      case ANONYMOUS -> 401;
      case OTHER_USER -> 403;
      default -> allowed;
    };
  }

  @ParameterizedTest
  @EnumSource(Actor.class)
  void conciergeDelegationRequiresLoginAndReturnsNonCacheableCredentials(Actor actor)
      throws Exception {
    var result =
        mvc.perform(
            post("/api/v1/auth/concierge-delegation")
                .session(new org.springframework.mock.web.MockHttpSession())
                .with(actor(actor))
                .with(csrf()));
    if (actor == Actor.ANONYMOUS) {
      result.andExpect(status().isUnauthorized());
    } else {
      result
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.token").isNotEmpty())
          .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }
  }

  @Test
  void conciergeDelegationRejectsMissingCsrfAndUnsupportedRoles() throws Exception {
    mvc.perform(post("/api/v1/auth/concierge-delegation").with(testUser()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/auth/concierge-delegation")
                .with(
                    user(
                        new UserPrincipal(
                            3L,
                            "Other",
                            "User",
                            "other",
                            "other@example.com",
                            "",
                            false,
                            true,
                            List.of(new SimpleGrantedAuthority("ROLE_GUEST")))))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @EnumSource(Actor.class)
  void commentUpdatesRequireOwnershipOrAdmin(Actor actor) throws Exception {
    Comment comment = comments.saveAndFlush(new Comment("Original", 2L, 1L));
    mvc.perform(
            put("/api/v1/comments/{id}", comment.getId())
                .with(actor(actor))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Updated\"}"))
        .andExpect(status().is(statusFor(actor, 200)));
    assertThat(comments.getCommentById(comment.getId()).getMessage())
        .isEqualTo(statusFor(actor, 200) == 200 ? "Updated" : "Original");
  }

  @ParameterizedTest
  @EnumSource(Actor.class)
  void commentDeletesRequireOwnershipOrAdmin(Actor actor) throws Exception {
    Comment comment = comments.saveAndFlush(new Comment("Original", 2L, 1L));
    var response =
        mvc.perform(
                delete("/api/v1/comments/{id}", comment.getId()).with(actor(actor)).with(csrf()))
            .andExpect(status().is(statusFor(actor, 204)));
    if (statusFor(actor, 204) == 204) response.andExpect(content().string(""));
    else response.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    assertThat(comments.existsById(comment.getId())).isEqualTo(statusFor(actor, 204) != 204);
  }

  @ParameterizedTest
  @EnumSource(Actor.class)
  void accountUpdatesRequireOwnershipOrAdmin(Actor actor) throws Exception {
    String original = accounts.getAccountByUsername("test_user_two").getBio();
    mvc.perform(
            put("/api/v1/accounts/test_user_two")
                .with(actor(actor))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"Updated bio\"}"))
        .andExpect(status().is(statusFor(actor, 200)));
    assertThat(accounts.getAccountByUsername("test_user_two").getBio())
        .isEqualTo(statusFor(actor, 200) == 200 ? "Updated bio" : original);
  }

  @ParameterizedTest
  @EnumSource(Actor.class)
  void accountDeletesRequireOwnershipOrAdmin(Actor actor) throws Exception {
    mvc.perform(delete("/api/v1/accounts/test_user_two").with(actor(actor)).with(csrf()))
        .andExpect(status().is(statusFor(actor, 204)));
    assertThat(accounts.existsById(2L)).isEqualTo(statusFor(actor, 204) != 204);
  }

  @Test
  @WithMockUser(roles = "USER")
  void roleServiceRejectsNonAdminEvenWithoutController() {
    assertThatThrownBy(() -> roles.giveAdminRole("test_user_two", null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> roles.removeAdminRole("test_user_one", null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void roleServiceAllowsAdmin() {
    roles.giveAdminRole("test_user_two", null);
    roles.giveAdminRole("test_user_two", null);
    roles.removeAdminRole("test_user_two", null);
  }

  @Test
  void publicReadDoesNotMakeMovieCreationPublicOrExemptItFromCsrf() throws Exception {
    mvc.perform(get("/api/v1/movies?ids=1")).andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/movies")
                .with(testAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("access_denied"));
    mvc.perform(
            post("/api/v1/movies")
                .with(testUser())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
          {"primaryTitle":"Example","originalTitle":"Example","startYear":2020,
          "endYear":2020,"runtimeMinutes":100,"genres":["ACTION"],"movieType":"MOVIE","adult":false}
          """))
        .andExpect(status().isForbidden());
  }
}
