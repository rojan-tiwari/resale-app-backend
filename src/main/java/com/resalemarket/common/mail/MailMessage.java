package com.resalemarket.common.mail;

/**
 * A plain-text e-mail to one recipient. The body may contain a secret (a password-reset link), so it stays out of
 * {@link #toString()} and therefore out of logs that print the object.
 */
public record MailMessage(String to, String subject, String text) {

    @Override
    public String toString() {
        return "MailMessage[subject=" + subject + "]";
    }
}
