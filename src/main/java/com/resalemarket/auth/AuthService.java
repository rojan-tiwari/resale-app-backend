package com.resalemarket.auth;

import com.resalemarket.auth.dto.RegisterRequest;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Authentication use cases. Phase 2 grows this service step by step: registration first, then login and tokens. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an account. The plain password is hashed here and goes no further; the user module only ever sees the hash.
     *
     * @throws com.resalemarket.common.exception.ConflictException if the e-mail address is already registered
     */
    public UserResponse register(RegisterRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());
        UserResponse user = userService.createUser(
                new NewUser(request.email(), passwordHash, request.displayName(), request.phone()));
        log.info("Registered user {}", user.id());
        return user;
    }
}
