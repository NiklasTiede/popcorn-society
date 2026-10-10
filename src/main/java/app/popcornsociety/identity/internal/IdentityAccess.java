package app.popcornsociety.identity.internal;

import static app.popcornsociety.shared.logging.Log.ACCOUNT_ID;
import static net.logstash.logback.argument.StructuredArguments.kv;

import app.popcornsociety.account.api.AccountIdentity;
import app.popcornsociety.account.api.AccountIdentityService;
import app.popcornsociety.identity.api.AuthenticationService;
import app.popcornsociety.identity.api.PasswordResetRequest;
import app.popcornsociety.identity.api.RegistrationRequest;
import app.popcornsociety.identity.api.UserIdentityAvailability;
import app.popcornsociety.identity.api.events.EmailConfirmationRequested;
import app.popcornsociety.identity.api.events.PasswordResetRequested;
import app.popcornsociety.identity.internal.persistence.VerificationToken;
import app.popcornsociety.identity.internal.persistence.VerificationTokenRepository;
import app.popcornsociety.identity.internal.persistence.VerificationTypeEnum;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEventType;
import app.popcornsociety.identity.internal.security.audit.SecurityAuditEvents;
import app.popcornsociety.shared.api.MessageResponse;
import app.popcornsociety.shared.error.NotFoundException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityAccess implements AuthenticationService {

  private static final Logger logger = LoggerFactory.getLogger(IdentityAccess.class);

  private final PasswordEncoder passwordEncoder;
  private final AccountIdentityService accountIdentityService;
  private final VerificationTokenRepository verificationTokenRepository;
  private final TokenHasher tokenHasher;
  private final SecurityAuditEvents auditEvents;
  private final ApplicationEventPublisher events;
  private final IdentityProperties identityProperties;
  private final Clock clock;

  public IdentityAccess(
      PasswordEncoder passwordEncoder,
      AccountIdentityService accountIdentityService,
      VerificationTokenRepository verificationTokenRepository,
      TokenHasher tokenHasher,
      SecurityAuditEvents auditEvents,
      ApplicationEventPublisher events,
      IdentityProperties identityProperties,
      @Qualifier("identityClock") Clock clock) {
    this.passwordEncoder = passwordEncoder;
    this.accountIdentityService = accountIdentityService;
    this.verificationTokenRepository = verificationTokenRepository;
    this.tokenHasher = tokenHasher;
    this.auditEvents = auditEvents;
    this.events = events;
    this.identityProperties = identityProperties;
    this.clock = clock;
  }

  @Override
  public UserIdentityAvailability checkUsernameAvailability(String username) {
    Boolean isAvailable = accountIdentityService.isUsernameAvailable(username);
    logger.info("username [{}] available? {}", username, isAvailable);
    return new UserIdentityAvailability(isAvailable);
  }

  @Override
  @Transactional
  public MessageResponse registerUser(RegistrationRequest request) {

    String username = request.username().toLowerCase(Locale.ROOT);
    String email = request.email().toLowerCase(Locale.ROOT);
    String password = passwordEncoder.encode(request.password());

    AccountIdentity savedAccount =
        accountIdentityService.createAccountForIdentity(
            username, email, password, !identityProperties.emailVerificationEnabled());
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.LOCAL_CREDENTIAL_CREATED, savedAccount.id(), Map.of());
    logger.info("Account with [{}] was registered", kv(ACCOUNT_ID, savedAccount.id()));
    return new MessageResponse(
        identityProperties.emailVerificationEnabled()
            ? createAndSendEmailConfirmationToken(savedAccount)
            : "Account created. You can sign in now.");
  }

  private String createAndSendEmailConfirmationToken(AccountIdentity account) {
    String token = tokenHasher.newRawToken();

    VerificationToken verificationToken =
        new VerificationToken(
            VerificationTypeEnum.EMAIL_CONFIRMATION,
            tokenHasher.hash(token),
            clock.instant().plus(30, ChronoUnit.MINUTES),
            account.id());
    verificationTokenRepository.save(verificationToken);
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.VERIFICATION_TOKEN_ISSUED, account.id(), Map.of());

    String link = identityProperties.frontendHost() + "/confirm-email?token=" + token;
    events.publishEvent(
        new EmailConfirmationRequested(
            account.email(), account.username(), link, verificationToken.getExpiryDateInUtc()));
    logger.info(
        "confirmation email containing activation token for account with [{}] was requested",
        kv(ACCOUNT_ID, account.id()));
    return "Check your email to activate your account.";
  }

  @Override
  @Transactional
  public MessageResponse confirmEmailAddress(String token) {
    VerificationToken verificationToken =
        verificationTokenRepository
            .findForConsumption(tokenHasher.hash(token))
            .orElseThrow(
                () -> new NotFoundException("Email Confirmation Token not found in database."));
    verificationToken.consume(VerificationTypeEnum.EMAIL_CONFIRMATION, clock.instant());
    accountIdentityService.enableAccount(verificationToken.getAccountId());
    verificationTokenRepository.save(verificationToken);
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.VERIFICATION_TOKEN_CONSUMED,
        verificationToken.getAccountId(),
        Map.of());
    logger.info(
        "email address of new account was confirmed for account with [{}]",
        kv(ACCOUNT_ID, verificationToken.getAccountId()));
    return new MessageResponse("Email was confirmed and therefore account was activated");
  }

  @Override
  @Transactional
  public MessageResponse resetPassword(String email) {
    accountIdentityService
        .findOptionalByEmail(email)
        .ifPresent(this::createAndSendPasswordResetToken);
    return new MessageResponse(
        "If an account exists for this email, password reset instructions will be sent.");
  }

  private void createAndSendPasswordResetToken(AccountIdentity account) {
    String token = tokenHasher.newRawToken();
    VerificationToken verificationToken =
        new VerificationToken(
            VerificationTypeEnum.PASSWORD_RESET,
            tokenHasher.hash(token),
            clock.instant().plus(30, ChronoUnit.MINUTES),
            account.id());
    verificationToken.setConfirmedAtInUtc(clock.instant());
    verificationTokenRepository.save(verificationToken);
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.PASSWORD_RESET_TOKEN_ISSUED, account.id(), Map.of());

    String link = identityProperties.frontendHost() + "/reset-password?token=" + token;
    events.publishEvent(
        new PasswordResetRequested(
            account.email(), account.username(), link, verificationToken.getExpiryDateInUtc()));
    logger.info(
        "password reset email for account with [{}] was requested", kv(ACCOUNT_ID, account.id()));
  }

  @Override
  @Transactional
  public MessageResponse saveNewPassword(PasswordResetRequest request) {
    VerificationToken verificationToken =
        verificationTokenRepository
            .findForConsumption(tokenHasher.hash(request.token()))
            .orElseThrow(
                () -> new NotFoundException("Password Reset Token not found in database."));
    verificationToken.consume(VerificationTypeEnum.PASSWORD_RESET, clock.instant());
    accountIdentityService.updatePassword(
        verificationToken.getAccountId(), passwordEncoder.encode(request.newPassword()));
    verificationTokenRepository.save(verificationToken);
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.LOCAL_CREDENTIAL_PASSWORD_CHANGED,
        verificationToken.getAccountId(),
        Map.of());
    auditEvents.recordCredentialEvent(
        SecurityAuditEventType.PASSWORD_RESET_TOKEN_CONSUMED,
        verificationToken.getAccountId(),
        Map.of());
    logger.info(
        "new password was saved for account with [{}]",
        kv(ACCOUNT_ID, verificationToken.getAccountId()));
    return new MessageResponse("New Password was saved");
  }
}
