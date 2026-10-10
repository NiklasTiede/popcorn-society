package app.popcornsociety.account.internal.persistence;

import app.popcornsociety.account.api.AccountSessionState;
import app.popcornsociety.shared.error.NotFoundException;
import app.popcornsociety.shared.security.UserPrincipal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

  @Query(
      "select new app.popcornsociety.account.api.AccountSessionState(a.securityVersion, a.locked, a.enabled) from Account a where a.id = :accountId")
  Optional<AccountSessionState> findSessionState(Long accountId);

  @Modifying(flushAutomatically = true)
  @Query("update Account a set a.securityVersion = a.securityVersion + 1 where a.id = :accountId")
  void revokeSessions(Long accountId);

  Optional<Account> findByUsername(String username);

  Optional<Account> findByEmail(String email);

  Optional<Account> findByUsernameOrEmail(String username, String email);

  Boolean existsByUsername(String username);

  Boolean existsByEmail(String email);

  boolean existsByImageUrlToken(String token);

  default Account getAccount(UserPrincipal currentUser) {
    return getAccountByUsername(currentUser.getUsername());
  }

  default Account getAccountByUsername(String username) {
    return findByUsername(username)
        .orElseThrow(
            () ->
                new NotFoundException(
                    "User with username [" + username + "] not found in database."));
  }
}
