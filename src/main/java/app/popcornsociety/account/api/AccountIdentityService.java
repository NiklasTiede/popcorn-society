package app.popcornsociety.account.api;

import java.util.Optional;
import org.springframework.modulith.NamedInterface;

@NamedInterface("identity")
public interface AccountIdentityService {

  Optional<AccountSessionState> findSessionState(Long accountId);

  boolean isUsernameAvailable(String username);

  boolean isEmailAvailable(String email);

  AccountIdentity createAccountForIdentity(
      String username, String email, String passwordHash, boolean enabled);

  AccountIdentity createSocialAccount(String username, String email);

  AccountIdentity findByEmail(String email);

  Optional<AccountIdentity> findOptionalByEmail(String email);

  AccountIdentity findByUsername(String username);

  AccountIdentity enableAccount(Long accountId);

  boolean hasLocalCredential(Long accountId);

  void createLocalCredential(Long accountId, String passwordHash);

  java.util.Optional<AccountIdentityProviderLink> findProviderLink(
      String provider, String providerUserId);

  void linkProvider(Long accountId, String provider, String providerUserId, String email);

  void updatePassword(Long accountId, String passwordHash);

  AccountCredentials loadCredentialsByUsernameOrEmail(String usernameOrEmail);

  AccountCredentials loadCredentialsById(Long accountId);
}
