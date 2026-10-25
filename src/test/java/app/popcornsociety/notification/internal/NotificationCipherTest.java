package app.popcornsociety.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;

class NotificationCipherTest {
  private static final String FIRST_KEY = key((byte) 1);
  private static final String SECOND_KEY = key((byte) 2);
  private static final NotificationMessage MESSAGE =
      new NotificationMessage(
          NotificationMessage.Kind.PASSWORD_RESET,
          "test@example.com",
          "test",
          "https://example.com/reset?token=synthetic-secret");

  @Test
  void decryptsPayloadWrittenBySpringSecuritySevenOneZero() {
    // Generated with Spring Security 7.1.0 and the synthetic FIRST_KEY, never a real secret.
    byte[] payload =
        Base64.getDecoder()
            .decode(
                "AAECAwQFBgcICQoLDA0OD9o7pf1EyOfloYlU7tJnwJvd+BRds+laYSUcfnZQC1FGcaInuETqzPG62UovTmLs2Ig2SNoAnxMQYnnObLR2aMKP+UVem3wNLB06LxUsM8EA60GBA5WPo7w1NU/juH/Sf3KyDr90O/FGTUckufgxlIcvHuTHR5nnGyPQJmPJ4O00W9LkQrcdQKcV2WFRCy7ysJrPO2SV3qCTuqMrEo9gtdojnYK4cjJ4CCZB7zFVN9Dee9prFDTBP0R8AcuvPpavLbhH37CzWYovHXeYrw6442/jDBXfx2jM96IRZA==");
    var cipher = cipher("first", Map.of("first", FIRST_KEY));
    assertThat(cipher.decrypt("first", "legacy-delivery", payload)).isEqualTo(MESSAGE);
  }

  @Test
  @SuppressWarnings("deprecation") // Independent legacy reader proves rollback compatibility.
  void newPayloadsRemainReadableByThePreUpgradeCipher() throws Exception {
    byte[] payload = cipher("first", Map.of("first", FIRST_KEY)).encrypt("delivery", MESSAGE);
    byte[] salt = Arrays.copyOfRange(payload, 0, 16);
    byte[] plaintext =
        Encryptors.stronger(FIRST_KEY, HexFormat.of().formatHex(salt))
            .decrypt(Arrays.copyOfRange(payload, 16, payload.length));
    var envelope = new ObjectMapper().readTree(new String(plaintext, StandardCharsets.UTF_8));
    assertThat(envelope.get("version").intValue()).isEqualTo(1);
    assertThat(envelope.get("deliveryId").textValue()).isEqualTo("delivery");
    assertThat(new ObjectMapper().treeToValue(envelope.get("message"), NotificationMessage.class))
        .isEqualTo(MESSAGE);
  }

  @Test
  void encryptsWithFreshRandomnessAndAuthenticatesTheDeliveryIdentity() {
    var cipher = cipher("first", Map.of("first", FIRST_KEY));
    byte[] first = cipher.encrypt("delivery-one", MESSAGE);
    byte[] second = cipher.encrypt("delivery-one", MESSAGE);
    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt("first", "delivery-one", first)).isEqualTo(MESSAGE);
    assertThatThrownBy(() -> cipher.decrypt("first", "different-delivery", first))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Notification payload cannot be decrypted or authenticated");
  }

  @Test
  void rejectsTamperedAndTruncatedPayloadsWithoutLeakingTheirContent() {
    var cipher = cipher("first", Map.of("first", FIRST_KEY));
    byte[] payload = cipher.encrypt("delivery", MESSAGE);
    payload[payload.length - 1] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt("first", "delivery", payload))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause()
        .hasMessage("Notification payload cannot be decrypted or authenticated");
    assertThatThrownBy(() -> cipher.decrypt("first", "delivery", new byte[3]))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause();
  }

  @Test
  void retainsDecryptionAcrossKeyRotationAndRefusesMissingKeys() {
    var original = cipher("first", Map.of("first", FIRST_KEY));
    byte[] old = original.encrypt("delivery", MESSAGE);
    var rotated = cipher("second", Map.of("first", FIRST_KEY, "second", SECOND_KEY));
    assertThat(rotated.activeKey()).isEqualTo("second");
    assertThat(rotated.decrypt("first", "delivery", old)).isEqualTo(MESSAGE);
    assertThat(rotated.decrypt("second", "next", rotated.encrypt("next", MESSAGE)))
        .isEqualTo(MESSAGE);
    assertThatThrownBy(
            () -> cipher("second", Map.of("second", SECOND_KEY)).decrypt("first", "delivery", old))
        .isInstanceOf(IllegalStateException.class)
        .hasNoCause();
  }

  @Test
  void rejectsMissingAndWeakKeyConfiguration() {
    assertThatThrownBy(() -> cipher("missing", Map.of("first", FIRST_KEY)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher("first", Map.of("first", "weak")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cipher(null, Map.of())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void diagnosticStringsDoNotRevealKeysOrMailCapabilities() {
    assertThat(new NotificationOutboxProperties("first", Map.of("first", FIRST_KEY)).toString())
        .doesNotContain(FIRST_KEY);
    assertThat(MESSAGE.toString()).doesNotContain(MESSAGE.link(), MESSAGE.recipient());
  }

  private static NotificationCipher cipher(String active, Map<String, String> keys) {
    return new NotificationCipher(new NotificationOutboxProperties(active, keys));
  }

  private static String key(byte value) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, value);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
