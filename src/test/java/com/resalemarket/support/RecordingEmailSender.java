package com.resalemarket.support;

import static org.awaitility.Awaitility.await;

import com.resalemarket.common.mail.EmailSender;
import com.resalemarket.common.mail.MailMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the e-mails the application would send, so integration tests can read the password-reset link. The reset
 * request is handled in the background (that is the point of it), hence the waiting helpers.
 */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(MailMessage message) {
        sent.add(message);
    }

    public void clear() {
        sent.clear();
    }

    /** What has been sent to this address so far (case-insensitive), oldest first. */
    public List<MailMessage> sentTo(String email) {
        return sent.stream().filter(mail -> mail.to().equalsIgnoreCase(email)).toList();
    }

    /** Waits (up to 10 s) until {@code count} messages have gone to this address, and returns them, oldest first. */
    public List<MailMessage> awaitMessagesTo(String email, int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> sentTo(email).size() >= count);
        return sentTo(email);
    }

    /** Waits for the first message to this address and returns it. */
    public MailMessage awaitMessageTo(String email) {
        return awaitMessagesTo(email, 1).get(0);
    }

    /** The reset token in the link of a password-reset e-mail. */
    public static String tokenIn(MailMessage message) {
        Matcher matcher = TOKEN.matcher(message.text());
        if (!matcher.find()) {
            throw new AssertionError("No token= link in the mail: " + message.subject());
        }
        return matcher.group(1);
    }
}
