package app.popcornsociety.account.api;

import java.util.List;
import org.springframework.modulith.NamedInterface;

@NamedInterface("identity")
public record AccountCredentials(
    Long id,
    String firstName,
    String lastName,
    String username,
    String email,
    String password,
    boolean locked,
    boolean enabled,
    List<String> roleNames,
    long securityVersion) {
  public AccountCredentials(
      Long id,
      String firstName,
      String lastName,
      String username,
      String email,
      String password,
      boolean locked,
      boolean enabled,
      List<String> roleNames) {
    this(id, firstName, lastName, username, email, password, locked, enabled, roleNames, 0);
  }
}
