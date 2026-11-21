package com.resalemarket.common.mail;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outgoing mail settings, bound from {@code app.mail.*}.
 *
 * @param transport {@code log} writes each message to the application log instead of sending it (development only: the
 *                  message may contain a password-reset link); {@code smtp} sends through the server in {@code spring.mail.*}.
 *                  The production profile pins {@code smtp}.
 * @param from      the sender address, optionally with a display name: {@code ResaleMarket <no-reply@example.com>}
 */
@ConfigurationProperties(prefix = "app.mail")
public record EmailProperties(
        @DefaultValue("log") String transport,
        @DefaultValue("ResaleMarket <no-reply@resalemarket.local>") String from) {

    public static final String LOG = "log";
    public static final String SMTP = "smtp";

    private static final Set<String> TRANSPORTS = Set.of(LOG, SMTP);

    public EmailProperties {
        if (!TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException(
                    "app.mail.transport (MAIL_TRANSPORT) must be one of " + TRANSPORTS + " but is '" + transport + "'.");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("app.mail.from (MAIL_FROM) must not be blank.");
        }
    }
}
