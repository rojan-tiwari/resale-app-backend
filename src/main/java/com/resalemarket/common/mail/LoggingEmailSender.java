package com.resalemarket.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development transport: prints the whole message, including any link in it, to the log, so the password-reset flow can
 * be tried without a mail server. <b>Never use it in production</b> (the log would then hold working reset links); the
 * production profile fixes {@code app.mail.transport=smtp}.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = EmailProperties.LOG, matchIfMissing = true)
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(MailMessage message) {
        log.info("""
                E-mail NOT sent (app.mail.transport=log). Subject: {}
                To: {}

                {}
                """, message.subject(), message.to(), message.text());
    }
}
