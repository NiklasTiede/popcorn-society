package app.popcornsociety.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Session;
import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailTransportSecurityConfigurationTest {
  @Test
  void productionDefaultsRequireTlsAndVerifyServerIdentity() throws IOException {
    Properties config = new Properties();
    try (var stream = getClass().getResourceAsStream("/config/application-prod.properties")) {
      config.load(stream);
    }
    assertThat(config.getProperty("spring.mail.properties.mail.smtp.starttls.enable"))
        .isEqualTo("true");
    assertThat(config.getProperty("spring.mail.properties.mail.smtp.starttls.required"))
        .isEqualTo("true");
    assertThat(config.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity"))
        .isEqualTo("true");
  }

  @Test
  void productionRejectsAuthenticatedPlaintextMailBeforeSending() {
    var properties = new Properties();
    properties.setProperty("mail.smtp.auth", "true");
    run("prod", "smtp", properties).run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mail.smtp.starttls.enable",
        "mail.smtp.starttls.required",
        "mail.smtp.ssl.checkserveridentity"
      })
  void productionRejectsTransportSecurityOverrides(String property) {
    var properties = startTlsProperties();
    properties.setProperty(property, "false");
    run("prod", "smtp", properties).run(context -> assertThat(context).hasFailed());
  }

  @Test
  void productionRejectsTrustAllCertificateOverride() {
    var properties = startTlsProperties();
    properties.setProperty("mail.smtp.ssl.trust", "*");
    run("prod", "smtp", properties).run(context -> assertThat(context).hasFailed());
  }

  @Test
  void productionAcceptsMandatoryStartTlsWithoutOpeningAConnection() {
    run("prod", "smtp", startTlsProperties()).run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"smtp", "smtps"})
  void productionAcceptsVerifiedImplicitTls(String protocol) {
    var properties = new Properties();
    properties.setProperty("mail." + protocol + ".ssl.enable", "true");
    properties.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
    run("prod", protocol, properties).run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void productionRejectsDisabledSmtpsTls() {
    var properties = new Properties();
    properties.setProperty("mail.smtps.ssl.enable", "false");
    properties.setProperty("mail.smtps.ssl.checkserveridentity", "true");
    run("prod", "smtps", properties).run(context -> assertThat(context).hasFailed());
  }

  @Test
  void productionRejectsUnknownTransportProtocol() {
    run("prod", "custom", startTlsProperties()).run(context -> assertThat(context).hasFailed());
  }

  @Test
  void productionValidatesEffectiveSessionRatherThanIgnoredSenderProperties() {
    var sender = sender("smtp", startTlsProperties());
    sender.setSession(Session.getInstance(new Properties()));
    runner("prod")
        .withBean(JavaMailSenderImpl.class, () -> sender)
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "local", "test"})
  void nonProductionAllowsPlaintextTestMail(String profile) {
    run(profile, "smtp", new Properties()).run(context -> assertThat(context).hasNotFailed());
  }

  private static Properties startTlsProperties() {
    var properties = new Properties();
    properties.setProperty("mail.smtp.auth", "true");
    properties.setProperty("mail.smtp.starttls.enable", "true");
    properties.setProperty("mail.smtp.starttls.required", "true");
    properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    return properties;
  }

  private static ApplicationContextRunner run(
      String profile, String protocol, Properties properties) {
    return runner(profile).withBean(JavaMailSenderImpl.class, () -> sender(protocol, properties));
  }

  private static ApplicationContextRunner runner(String profile) {
    return new ApplicationContextRunner()
        .withUserConfiguration(SecurityScan.class)
        .withPropertyValues("spring.profiles.active=" + profile);
  }

  private static JavaMailSenderImpl sender(String protocol, Properties properties) {
    var sender = new JavaMailSenderImpl();
    sender.setProtocol(protocol);
    sender.setJavaMailProperties(properties);
    return sender;
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackageClasses = EmailNotificationService.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.REGEX,
              pattern = ".*\\.MailTransportSecurityConfiguration"))
  static class SecurityScan {}
}
