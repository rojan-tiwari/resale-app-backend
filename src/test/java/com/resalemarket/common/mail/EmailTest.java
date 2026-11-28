package com.resalemarket.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.resalemarket.common.config.FrontendProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailTest {

    private final MailMessage message =
            new MailMessage("sita@example.com", "Reset your password", "Open https://example.com/reset?token=secret-token");

    @Test
    void aMessageKeepsItsBodyOutOfItsStringForm() {
        assertThat(message.toString()).contains("Reset your password").doesNotContain("secret-token").doesNotContain("sita@example.com");
    }

    @Test
    void theSmtpTransportSendsAPlainTextMailFromTheConfiguredSender() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, new EmailProperties("smtp", "ResaleMarket <no-reply@example.com>"));

        sender.send(message);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("ResaleMarket <no-reply@example.com>");
        assertThat(sent.getValue().getTo()).containsExactly("sita@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("Reset your password");
        assertThat(sent.getValue().getText()).isEqualTo("Open https://example.com/reset?token=secret-token");
    }

    @Test
    void theLogTransportSendsNothingAndDoesNotFail() {
        assertThatCode(() -> new LoggingEmailSender().send(message)).doesNotThrowAnyException();
    }

    @Test
    void theTransportMustBeLogOrSmtp() {
        assertThat(new EmailProperties("log", "a@b.c").transport()).isEqualTo("log");
        assertThat(new EmailProperties("smtp", "a@b.c").transport()).isEqualTo("smtp");
        assertThatThrownBy(() -> new EmailProperties("smpt", "a@b.c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAIL_TRANSPORT")
                .hasMessageContaining("smpt");
        assertThatThrownBy(() -> new EmailProperties("log", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWebAppUrlBuildsLinksWithoutADoubleSlash() {
        assertThat(new FrontendProperties("https://resalemarket.example/").link("/auth/reset-password?token=x"))
                .isEqualTo("https://resalemarket.example/auth/reset-password?token=x");
        assertThat(new FrontendProperties("http://localhost:4200").link("/a")).isEqualTo("http://localhost:4200/a");
    }

    @Test
    void theWebAppUrlMustBeAnAbsoluteHttpUrl() {
        assertThatThrownBy(() -> new FrontendProperties("localhost:4200")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrontendProperties("ftp://example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrontendProperties("/relative")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrontendProperties("not a url")).isInstanceOf(IllegalArgumentException.class);
    }
}
