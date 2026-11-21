package com.resalemarket.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the (logging) e-mail sender with one the tests can read. Imported by {@link IntegrationTest}. */
@TestConfiguration(proxyBeanMethods = false)
public class TestMailConfiguration {

    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}
