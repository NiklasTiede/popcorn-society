package app.popcornsociety.notification.internal;

import java.util.Properties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Fail before delivery if production mail overrides remove transport security. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class MailTransportSecurityConfiguration {
  @Bean
  static BeanPostProcessor secureProductionMailTransport() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof JavaMailSenderImpl sender) {
          validate(sender);
        }
        return bean;
      }
    };
  }

  private static void validate(JavaMailSenderImpl sender) {
    String protocol = sender.getProtocol();
    if (!"smtp".equals(protocol) && !"smtps".equals(protocol)) {
      throw new IllegalStateException("Production mail requires an SMTP TLS transport");
    }
    // A supplied Jakarta Mail Session takes precedence over the sender's properties.
    Properties properties = sender.getSession().getProperties();
    String prefix = "mail." + protocol + ".";
    boolean implicitTls = enabled(properties, prefix + "ssl.enable");
    boolean requiredStartTls =
        enabled(properties, prefix + "starttls.enable")
            && enabled(properties, prefix + "starttls.required");
    if ((!implicitTls && !requiredStartTls)
        || !enabled(properties, prefix + "ssl.checkserveridentity")
        || properties.containsKey(prefix + "ssl.trust")) {
      throw new IllegalStateException(
          "Production mail requires mandatory TLS, server identity verification, and trusted certificates");
    }
  }

  private static boolean enabled(Properties properties, String key) {
    Object value = properties.get(key);
    return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
  }
}
