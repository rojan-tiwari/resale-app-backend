package com.resalemarket.auth;

import com.resalemarket.auth.entity.RevocationReason;
import com.resalemarket.common.config.FrontendProperties;
import com.resalemarket.common.mail.EmailSender;
import com.resalemarket.common.mail.MailMessage;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.UserResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The forgot-password flow: send a one-time link, and let the link set a new password.
 *
 * <p><b>No account enumeration.</b> {@link #requestReset} runs in the background and never reports anything: the HTTP
 * answer is the same, and arrives in the same time, whether or not the address has an account. (Doing the lookup, the
 * database write and the mail inside the request would make an existing address measurably slower than an unknown one.)
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserService userService;
    private final PasswordResetTokens tokens;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final FrontendProperties frontend;
    private final AuthProperties properties;

    public PasswordResetService(
            UserService userService,
            PasswordResetTokens tokens,
            RefreshTokenService refreshTokens,
            PasswordEncoder passwordEncoder,
            EmailSender emailSender,
            FrontendProperties frontend,
            AuthProperties properties) {
        this.userService = userService;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.frontend = frontend;
        this.properties = properties;
    }

    /**
     * Sends a reset link to the address if it belongs to an active account, and does nothing otherwise. Runs in the
     * background and never throws: a problem is logged (without the address) and the person simply gets no e-mail.
     */
    @Async
    public void requestReset(String email) {
        Optional<UserResponse> user;
        try {
            user = userService.findActiveByEmail(email);
        } catch (RuntimeException ex) {
            log.error("Could not look up the account for a password-reset request", ex);
            return;
        }
        user.ifPresent(this::issueAndSend);
    }

    /**
     * Sets a new password with a link token, and ends every session: whoever held the old password (or a stolen session)
     * is signed out everywhere. All of it happens in one transaction, so a failure leaves the link usable.
     *
     * @throws com.resalemarket.common.exception.InvalidTokenException if the link is unknown, used, expired, or belongs to
     *     an account that can no longer sign in
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        UUID userId = tokens.consume(rawToken);
        if (userService.findActiveById(userId).isEmpty()) {
            // Suspended or deleted since the link was sent: as good as an unknown link.
            throw PasswordResetTokens.invalid();
        }
        userService.replacePassword(userId, passwordEncoder.encode(newPassword));
        int ended = refreshTokens.revokeAll(userId, RevocationReason.PASSWORD_CHANGED);
        log.info("User {} reset their password ({} session(s) ended)", userId, ended);
    }

    private void issueAndSend(UserResponse user) {
        try {
            String token = tokens.issue(user.id());
            emailSender.send(message(user, token));
            log.info("Password-reset e-mail sent for user {}", user.id());
        } catch (RuntimeException ex) {
            // Only the type is logged: a mail exception's message can contain the recipient's address.
            log.error("Could not send the password-reset e-mail for user {} ({})", user.id(), ex.getClass().getName());
        }
    }

    private MailMessage message(UserResponse user, String token) {
        String link = frontend.link("/auth/reset-password?token=" + token);
        String text = """
                Hello %s,

                Someone asked to reset the password of your ResaleMarket account. To choose a new password, open this link:

                %s

                The link works once and expires in %s. If you did not ask for this, you can ignore this e-mail: your password stays as it is.
                """.formatted(user.displayName(), link, describe(properties.passwordResetTtl()));
        return new MailMessage(user.email(), "Reset your ResaleMarket password", text);
    }

    /** "1 hour", "2 hours", "30 minutes". */
    static String describe(Duration duration) {
        long minutes = Math.max(1, duration.toMinutes());
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
