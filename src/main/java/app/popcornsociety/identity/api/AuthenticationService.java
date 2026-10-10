package app.popcornsociety.identity.api;

import app.popcornsociety.shared.api.MessageResponse;

public interface AuthenticationService {

  UserIdentityAvailability checkUsernameAvailability(String username);

  MessageResponse registerUser(RegistrationRequest request);

  MessageResponse confirmEmailAddress(String token);

  MessageResponse resetPassword(String email);

  MessageResponse saveNewPassword(PasswordResetRequest request);
}
