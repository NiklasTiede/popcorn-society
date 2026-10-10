package app.popcornsociety.identity.internal.security.oauth2;

import app.popcornsociety.shared.security.UserPrincipal;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

public class SocialUserPrincipal extends UserPrincipal implements OidcUser {

  private static final long serialVersionUID = 9133163625426103909L;

  private final Map<String, Object> attributes;
  private final Map<String, Object> claims;
  private final String name;
  private final OidcIdToken idToken;
  private final OidcUserInfo userInfo;

  private SocialUserPrincipal(
      UserPrincipal principal,
      Map<String, Object> attributes,
      String name,
      Map<String, Object> claims,
      OidcIdToken idToken,
      OidcUserInfo userInfo) {
    super(
        principal.getId(),
        principal.getFirstName(),
        principal.getLastName(),
        principal.getUsername(),
        principal.getEmail(),
        principal.getPassword(),
        !principal.isAccountNonLocked(),
        principal.isEnabled(),
        principal.getAuthorities(),
        principal.getSecurityVersion());
    this.attributes = withoutNullValues(attributes);
    this.claims = withoutNullValues(claims);
    this.name = name;
    this.idToken = idToken;
    this.userInfo = userInfo;
  }

  public static SocialUserPrincipal from(UserPrincipal principal, OAuth2User oauth2User) {
    return new SocialUserPrincipal(
        principal,
        oauth2User.getAttributes(),
        oauth2User.getName(),
        oauth2User.getAttributes(),
        null,
        null);
  }

  public static SocialUserPrincipal from(UserPrincipal principal, OidcUser oidcUser) {
    return new SocialUserPrincipal(
        principal,
        oidcUser.getAttributes(),
        oidcUser.getName(),
        oidcUser.getClaims(),
        oidcUser.getIdToken(),
        oidcUser.getUserInfo());
  }

  @Override
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  @Override
  public Map<String, Object> getClaims() {
    return claims;
  }

  @Override
  public OidcUserInfo getUserInfo() {
    return userInfo;
  }

  @Override
  public OidcIdToken getIdToken() {
    return idToken;
  }

  @Override
  public String getName() {
    return name;
  }

  private static Map<String, Object> withoutNullValues(Map<String, Object> values) {
    return values.entrySet().stream()
        .filter(entry -> entry.getValue() != null)
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }
}
