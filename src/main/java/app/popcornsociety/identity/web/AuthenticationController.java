package app.popcornsociety.identity.web;

import app.popcornsociety.identity.api.*;
import app.popcornsociety.shared.api.MessageResponse;
import app.popcornsociety.shared.security.CurrentUser;
import app.popcornsociety.shared.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping(path = "/api/v{version}/auth", version = "1")
public class AuthenticationController {

  private final AuthenticationService authenticationService;
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContextRepository;
  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  public AuthenticationController(
      AuthenticationService authenticationService,
      AuthenticationManager authenticationManager,
      SecurityContextRepository securityContextRepository) {
    this.authenticationService = authenticationService;
    this.authenticationManager = authenticationManager;
    this.securityContextRepository = securityContextRepository;
  }

  @GetMapping("/check-username-availability")
  public ResponseEntity<UserIdentityAvailability> checkUsernameAvailability(
      @RequestParam("username") String username) {
    return new ResponseEntity<>(
        authenticationService.checkUsernameAvailability(username), HttpStatus.OK);
  }

  @PostMapping("/login")
  public ResponseEntity<AccountSessionResponse> authenticateAccount(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password()));
    SecurityContext context = securityContextHolderStrategy.createEmptyContext();
    context.setAuthentication(authentication);
    securityContextHolderStrategy.setContext(context);
    httpRequest.getSession();
    httpRequest.changeSessionId();
    securityContextRepository.saveContext(context, httpRequest, httpResponse);
    return new ResponseEntity<>(
        toSessionResponse((UserPrincipal) authentication.getPrincipal()), HttpStatus.OK);
  }

  @GetMapping("/me")
  public ResponseEntity<AccountSessionResponse> currentAccount(@CurrentUser UserPrincipal user) {
    return new ResponseEntity<>(toSessionResponse(user), HttpStatus.OK);
  }

  @PostMapping("/registration")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<MessageResponse> registerAccount(
      @Valid @RequestBody RegistrationRequest request) {
    return new ResponseEntity<>(authenticationService.registerUser(request), HttpStatus.CREATED);
  }

  @PostMapping("/email-confirmations")
  public ResponseEntity<MessageResponse> confirmEmailAddress(
      @Valid @RequestBody EmailConfirmationRequest request) {
    return new ResponseEntity<>(
        authenticationService.confirmEmailAddress(request.token()), HttpStatus.OK);
  }

  @PostMapping("/password-reset-requests")
  public ResponseEntity<MessageResponse> resetPassword(
      @Valid @RequestBody PasswordResetEmailRequest request) {
    return new ResponseEntity<>(
        authenticationService.resetPassword(request.email()), HttpStatus.OK);
  }

  @PostMapping("/password-resets")
  public ResponseEntity<MessageResponse> saveNewPassword(
      @Valid @RequestBody PasswordResetRequest request) {
    return new ResponseEntity<>(authenticationService.saveNewPassword(request), HttpStatus.OK);
  }

  private AccountSessionResponse toSessionResponse(UserPrincipal user) {
    List<String> roles =
        user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    return new AccountSessionResponse(user.getId(), user.getUsername(), user.getEmail(), roles);
  }
}
