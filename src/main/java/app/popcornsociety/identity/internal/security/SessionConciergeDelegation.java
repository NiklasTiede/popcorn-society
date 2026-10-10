package app.popcornsociety.identity.internal.security;

import app.popcornsociety.identity.api.ConciergeDelegation;
import app.popcornsociety.identity.api.ConciergeDelegationResponse;
import app.popcornsociety.shared.security.UserPrincipal;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;

/** A narrowly scoped credential backed by the existing, revocable JDBC login session. */
@Service
public class SessionConciergeDelegation implements ConciergeDelegation {
  private static final String KEY = "CONCIERGE_DELEGATION_KEY";
  private static final String AUDIENCE = "movie-concierge";
  private static final String SCOPES =
      "watchlist:read,watchlist:add,watchlist:remove,ratings:read,ratings:set,ratings:remove";
  private final SessionRepository<? extends Session> sessions;
  private final Clock clock;
  private final AccountSessionValidator validator;

  public SessionConciergeDelegation(
      SessionRepository<? extends Session> sessions,
      @Qualifier("identityClock") Clock clock,
      AccountSessionValidator validator) {
    this.sessions = sessions;
    this.clock = clock;
    this.validator = validator;
  }

  public ConciergeDelegationResponse issue(HttpSession session, UserPrincipal principal) {
    if (session == null || principal == null || !validator.isCurrent(principal)) {
      throw denied();
    }
    String key;
    synchronized (session) {
      key = (String) session.getAttribute(KEY);
      if (key == null) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        key = encode(bytes);
        session.setAttribute(KEY, key);
      }
    }
    var expires = clock.instant().plusSeconds(300);
    String payload =
        session.getId()
            + "|"
            + principal.getId()
            + "|"
            + expires.getEpochSecond()
            + "|"
            + AUDIENCE
            + "|"
            + SCOPES;
    String encoded = encode(payload.getBytes(StandardCharsets.UTF_8));
    return new ConciergeDelegationResponse(encoded + "." + sign(encoded, key), expires);
  }

  @Override
  public Actor verify(String token, String requiredScope) {
    try {
      if (token == null || token.length() > 1024) throw denied();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 2) throw denied();
      String[] claims =
          new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (claims.length != 5
          || !AUDIENCE.equals(claims[3])
          || !SCOPES.equals(claims[4])
          || !Set.of(SCOPES.split(",")).contains(requiredScope)
          || Long.parseLong(claims[2]) <= clock.instant().getEpochSecond()) throw denied();
      Session session = sessions.findById(claims[0]);
      if (session == null || session.isExpired()) throw denied();
      String key = session.getAttribute(KEY);
      if (key == null
          || !MessageDigest.isEqual(
              sign(parts[0], key).getBytes(StandardCharsets.US_ASCII),
              parts[1].getBytes(StandardCharsets.US_ASCII))) throw denied();
      SecurityContext context =
          session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      if (context == null
          || context.getAuthentication() == null
          || !context.getAuthentication().isAuthenticated()
          || !(context.getAuthentication().getPrincipal() instanceof UserPrincipal user)
          || !user.isEnabled()
          || !user.isAccountNonLocked()
          || !user.getId().toString().equals(claims[1])) throw denied();
      if (!validator.isCurrent(user)) {
        sessions.deleteById(session.getId());
        throw denied();
      }
      boolean permitted =
          user.getAuthorities().stream()
              .anyMatch(a -> Set.of("ROLE_USER", "ROLE_ADMIN").contains(a.getAuthority()));
      if (!permitted) throw denied();
      return new Actor(
          user.getId(), sign("conversation-binding:" + session.getId() + ":" + user.getId(), key));
    } catch (IllegalArgumentException ex) {
      throw denied();
    }
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sign(String value, String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(key), "HmacSHA256"));
      return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC unavailable", ex);
    }
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Sign in again to use your watchlist.");
  }
}
