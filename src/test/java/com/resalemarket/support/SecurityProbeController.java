package com.resalemarket.support;

import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two protected endpoints that exist only on the test classpath, so the bearer-token filter, the roles mapping and
 * {@code @PreAuthorize} can be tested before any real feature uses them. They are not public: the application's
 * deny-by-default rule applies to them like to everything else.
 */
@RestController
@RequestMapping("/api/v1/test-support/security")
public class SecurityProbeController {

    @GetMapping("/whoami")
    public Map<String, Object> whoami(Authentication authentication) {
        List<String> authorities =
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
        return Map.of("name", authentication.getName(), "authorities", authorities);
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> admin() {
        return Map.of("area", "admin");
    }
}
