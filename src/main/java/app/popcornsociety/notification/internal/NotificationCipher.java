package app.popcornsociety.notification.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.stereotype.Component;

@Component
public class NotificationCipher {
  private static final int SALT_BYTES = 16;
  private final NotificationOutboxProperties properties;
  private final ObjectMapper mapper = new ObjectMapper();
  private final SecureRandom random = new SecureRandom();

  public NotificationCipher(NotificationOutboxProperties properties) {
    this.properties = properties;
    if (properties.activeKey() == null || !properties.keys().containsKey(properties.activeKey())) {
      throw new IllegalStateException("Notification outbox active encryption key is missing");
    }
    for (String key : properties.keys().values()) {
      try {
        if (Base64.getDecoder().decode(key).length != 32) {
          throw new IllegalArgumentException();
        }
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "Notification outbox keys must be base64-encoded 32-byte secrets");
      }
    }
  }

  public String activeKey() {
    return properties.activeKey();
  }

  public byte[] encrypt(String deliveryId, NotificationMessage message) {
    try {
      byte[] salt = new byte[SALT_BYTES];
      random.nextBytes(salt);
      byte[] encrypted =
          formatOneEncryptor(properties.keys().get(activeKey()), salt)
              .encrypt(mapper.writeValueAsBytes(new Envelope(1, deliveryId, message)));
      return ByteBuffer.allocate(salt.length + encrypted.length).put(salt).put(encrypted).array();
    } catch (Exception exception) {
      throw new IllegalStateException("Notification payload encryption failed");
    }
  }

  public NotificationMessage decrypt(String keyId, String deliveryId, byte[] payload) {
    try {
      String key = properties.keys().get(keyId);
      if (key == null || payload.length <= SALT_BYTES) {
        throw new IllegalArgumentException();
      }
      byte[] salt = Arrays.copyOfRange(payload, 0, SALT_BYTES);
      byte[] plaintext =
          formatOneEncryptor(key, salt)
              .decrypt(Arrays.copyOfRange(payload, SALT_BYTES, payload.length));
      Envelope envelope = mapper.readValue(plaintext, Envelope.class);
      if (envelope.version() != 1 || !deliveryId.equals(envelope.deliveryId())) {
        throw new IllegalArgumentException();
      }
      return envelope.message();
    } catch (Exception exception) {
      throw new IllegalStateException("Notification payload cannot be decrypted or authenticated");
    }
  }

  private static BytesEncryptor formatOneEncryptor(String secret, byte[] salt)
      throws GeneralSecurityException {
    // Format 1 uses a random 256-bit configuration secret, not a human password.
    // Keep its derivation and GCM framing compatible with queued payloads and rollback readers.
    PBEKeySpec specification = new PBEKeySpec(secret.toCharArray(), salt, 1024, 256);
    try {
      return AesGcmBytesEncryptor.withSecretKey(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(specification))
          .build();
    } finally {
      specification.clearPassword();
    }
  }

  private record Envelope(int version, String deliveryId, NotificationMessage message) {}
}
