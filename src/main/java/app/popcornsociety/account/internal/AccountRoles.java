package app.popcornsociety.account.internal;

import static app.popcornsociety.shared.logging.Log.ACCOUNT_ID;
import static net.logstash.logback.argument.StructuredArguments.kv;

import app.popcornsociety.account.api.RoleService;
import app.popcornsociety.account.internal.persistence.Account;
import app.popcornsociety.account.internal.persistence.AccountRepository;
import app.popcornsociety.account.internal.persistence.Role;
import app.popcornsociety.account.internal.persistence.RoleName;
import app.popcornsociety.account.internal.persistence.RoleRepository;
import app.popcornsociety.shared.api.MessageResponse;
import app.popcornsociety.shared.security.UserPrincipal;
import jakarta.transaction.Transactional;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class AccountRoles implements RoleService {

  private static final Logger logger = LoggerFactory.getLogger(AccountRoles.class);

  private final AccountRepository accountRepository;
  private final RoleRepository roleRepository;

  public AccountRoles(AccountRepository accountRepository, RoleRepository roleRepository) {
    this.accountRepository = accountRepository;
    this.roleRepository = roleRepository;
  }

  @Override
  @Transactional
  @PreAuthorize("hasRole('ADMIN')")
  public MessageResponse giveAdminRole(String username, UserPrincipal currentAccount) {
    Account account = accountRepository.getAccountByUsername(username);
    Collection<Role> roles = account.getRoles();
    Role adminRole = roleRepository.getRoleByRoleName(RoleName.ROLE_ADMIN);
    if (!roles.contains(adminRole)) {
      roles.add(adminRole);
    }
    account.setRoles(roles);
    Account updatedAccount = accountRepository.save(account);
    logger.info(
        "Account with [{}] was given ADMIN permission.", kv(ACCOUNT_ID, updatedAccount.getId()));
    return new MessageResponse(
        "Account with id [%d] was given ADMIN permission.".formatted(updatedAccount.getId()));
  }

  @Override
  @Transactional
  @PreAuthorize("hasRole('ADMIN')")
  public MessageResponse removeAdminRole(String username, UserPrincipal currentAccount) {
    Account account = accountRepository.getAccountByUsername(username);
    Collection<Role> roles = account.getRoles();
    Role adminRole = roleRepository.getRoleByRoleName(RoleName.ROLE_ADMIN);
    roles.remove(adminRole);
    account.setRoles(roles);
    Account updatedAccount = accountRepository.save(account);
    accountRepository.revokeSessions(account.getId());
    logger.info(
        "Account with id [{}] was taken ADMIN permission.", kv(ACCOUNT_ID, updatedAccount.getId()));
    return new MessageResponse(
        "Account with id [%d] was taken ADMIN permission.".formatted(updatedAccount.getId()));
  }
}
