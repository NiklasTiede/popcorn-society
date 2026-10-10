package app.popcornsociety.account.internal;

import app.popcornsociety.account.api.AccountCredentials;
import app.popcornsociety.account.api.AccountIdentity;
import app.popcornsociety.account.api.AccountIdentityProviderLink;
import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.account.internal.persistence.Account;
import app.popcornsociety.account.internal.persistence.AccountIdentityProvider;
import app.popcornsociety.account.internal.persistence.AccountIdentityProviderRepository;
import app.popcornsociety.account.internal.persistence.AccountRepository;
import app.popcornsociety.account.internal.persistence.LocalCredential;
import app.popcornsociety.account.internal.persistence.LocalCredentialRepository;
import app.popcornsociety.shared.error.NotFoundException;
import jakarta.transaction.Transactional;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AccountIdentities implements AccountIdentityService {

  private final AccountRepository accountRepository;
  private final LocalCredentialRepository localCredentialRepository;
  private final AccountIdentityProviderRepository accountIdentityProviderRepository;
  private final RegisteredUserRoleProvider registeredUserRoleProvider;

  public AccountIdentities(
      AccountRepository accountRepository,
      LocalCredentialRepository localCredentialRepository,
      AccountIdentityProviderRepository accountIdentityProviderRepository,
      RegisteredUserRoleProvider registeredUserRoleProvider) {
    this.accountRepository = accountRepository;
    this.localCredentialRepository = localCredentialRepository;
    this.accountIdentityProviderRepository = accountIdentityProviderRepository;
    this.registeredUserRoleProvider = registeredUserRoleProvider;
  }

  @Override
  public Optional<app.popcornsociety.account.api.AccountSessionState> findSessionState(
      Long accountId) {
    return accountRepository.findSessionState(accountId);
  }

  @Override
  public boolean isUsernameAvailable(String username) {
    return !accountRepository.existsByUsername(username);
  }

  @Override
  public boolean isEmailAvailable(String email) {
    return !accountRepository.existsByEmail(email);
  }

  @Override
  @Transactional
  public AccountIdentity createAccountForIdentity(
      String username, String email, String passwordHash, boolean enabled) {
    Account account =
        new Account(username.toLowerCase(Locale.ROOT), email.toLowerCase(Locale.ROOT));
    account.setEnabled(enabled);
    account.setRoles(registeredUserRoleProvider.rolesForRegisteredUser());
    Account savedAccount = accountRepository.save(account);
    createLocalCredential(savedAccount.getId(), passwordHash);
    return toIdentity(savedAccount);
  }

  @Override
  @Transactional
  public AccountIdentity createSocialAccount(String username, String email) {
    Account account =
        new Account(username.toLowerCase(Locale.ROOT), email.toLowerCase(Locale.ROOT));
    account.setEnabled(true);
    account.setRoles(registeredUserRoleProvider.rolesForRegisteredUser());
    return toIdentity(accountRepository.save(account));
  }

  @Override
  public AccountIdentity findByEmail(String email) {
    return accountRepository
        .findByEmail(email)
        .map(this::toIdentity)
        .orElseThrow(
            () ->
                new NotFoundException(
                    "Account with email address [%s] not found in database.".formatted(email)));
  }

  @Override
  public Optional<AccountIdentity> findOptionalByEmail(String email) {
    return accountRepository.findByEmail(email).map(this::toIdentity);
  }

  @Override
  public AccountIdentity findByUsername(String username) {
    return toIdentity(accountRepository.getAccountByUsername(username));
  }

  @Override
  @Transactional
  public AccountIdentity enableAccount(Long accountId) {
    Account account = getAccount(accountId);
    account.setEnabled(true);
    return toIdentity(accountRepository.save(account));
  }

  @Override
  public boolean hasLocalCredential(Long accountId) {
    return localCredentialRepository.existsByAccountId(accountId);
  }

  @Override
  @Transactional
  public void createLocalCredential(Long accountId, String passwordHash) {
    localCredentialRepository.save(new LocalCredential(accountId, passwordHash));
  }

  @Override
  public Optional<AccountIdentityProviderLink> findProviderLink(
      String provider, String providerUserId) {
    return accountIdentityProviderRepository
        .findByProviderAndProviderUserId(provider, providerUserId)
        .map(this::toProviderLink);
  }

  @Override
  @Transactional
  public void linkProvider(Long accountId, String provider, String providerUserId, String email) {
    accountIdentityProviderRepository.save(
        new AccountIdentityProvider(accountId, provider, providerUserId, email));
  }

  @Override
  @Transactional
  public void updatePassword(Long accountId, String passwordHash) {
    LocalCredential credential =
        localCredentialRepository
            .findByAccountId(accountId)
            .orElseGet(() -> new LocalCredential(accountId, passwordHash));
    credential.setPasswordHash(passwordHash);
    localCredentialRepository.save(credential);
    accountRepository.revokeSessions(accountId);
  }

  @Override
  public AccountCredentials loadCredentialsByUsernameOrEmail(String usernameOrEmail) {
    Account account =
        accountRepository
            .findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "User not found with this username or email: %s"
                            .formatted(usernameOrEmail)));
    return toCredentials(account);
  }

  @Override
  public AccountCredentials loadCredentialsById(Long accountId) {
    return toCredentials(getAccount(accountId));
  }

  private Account getAccount(Long accountId) {
    return accountRepository
        .findById(accountId)
        .orElseThrow(
            () -> new NotFoundException("User not found with id: %s".formatted(accountId)));
  }

  private AccountIdentity toIdentity(Account account) {
    return new AccountIdentity(account.getId(), account.getUsername(), account.getEmail());
  }

  private AccountIdentityProviderLink toProviderLink(AccountIdentityProvider provider) {
    return new AccountIdentityProviderLink(
        provider.getAccountId(),
        provider.getProvider(),
        provider.getProviderUserId(),
        provider.getEmail());
  }

  private AccountCredentials toCredentials(Account account) {
    String passwordHash =
        localCredentialRepository
            .findByAccountId(account.getId())
            .map(LocalCredential::getPasswordHash)
            .orElse(null);
    return new AccountCredentials(
        account.getId(),
        account.getFirstName(),
        account.getLastName(),
        account.getUsername(),
        account.getEmail(),
        passwordHash,
        account.getLocked(),
        account.getEnabled(),
        account.getRoles().stream().map(role -> role.getName().name()).toList(),
        account.getSecurityVersion());
  }
}
