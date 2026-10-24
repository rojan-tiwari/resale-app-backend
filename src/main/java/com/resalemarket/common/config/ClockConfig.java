package com.resalemarket.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable {@link Clock} (always UTC) so time-dependent code, such as token expiry, can be tested
 * deterministically. All timestamps are stored in UTC and converted to Nepal time only in the UI.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
