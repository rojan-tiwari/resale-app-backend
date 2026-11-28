package com.resalemarket.common.mail;

/**
 * Sends an e-mail. Business code depends on this interface only; which implementation runs is a configuration choice
 * ({@code app.mail.transport}): {@link LoggingEmailSender} in development, {@link SmtpEmailSender} wherever mail must really leave.
 */
public interface EmailSender {

    /**
     * Hands the message to the transport.
     *
     * @throws org.springframework.mail.MailException if the transport refuses or cannot deliver it
     */
    void send(MailMessage message);
}
