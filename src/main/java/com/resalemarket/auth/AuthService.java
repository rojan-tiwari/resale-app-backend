package com.resalemarket.auth;

import com.resalemarket.auth.dto.AuthResponse;
import com.resalemarket.auth.dto.LoginRequest;
import com.resalemarket.auth.dto.RegisterRequest;
import com.resalemarket.common.exception.ForbiddenException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.UserResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Authentication use cases: registration, login, refresh and logout. Passwords never leave this service in plain text,
 * and the {@code user} module is only ever reached through {@link UserService}.
 *
 * <p>Deliberately not {@code @Transactional}: each call into a collaborator is its own transaction, so a token
 * revocation recorded while a request is being refused is committed even though the request then fails.
 */
@Service
public class AuthService {

    /** The same words for an unknown address and a wrong password, so the endpoint cannot be used to find accounts. */
    static final String INVALID_CREDENTIALS = "Invalid email or password.";

    static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;

    public AuthService(
            UserService userService,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
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

    /**
     * Signs a person in and starts a new session.
     *
     * @throws UnauthorizedException if the address is unknown or the password is wrong (the same answer for both)
     * @throws ForbiddenException    if the password is right but the account is suspended or locked
     */
    public AuthSession login(LoginRequest request, ClientInfo client) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        } catch (DisabledException ex) {
            throw new ForbiddenException("This account has been suspended.");
        } catch (LockedException ex) {
            throw new ForbiddenException("This account is temporarily locked. Please try again later.");
        } catch (BadCredentialsException ex) {
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        UserResponse user = userService.findActiveByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException(INVALID_CREDENTIALS));
        String refreshToken = refreshTokens.start(user.id(), client);
        log.info("User {} signed in", user.id());
        return new AuthSession(authResponse(user), refreshToken);
    }

    /**
     * Exchanges the refresh token from the cookie for a new one and a new access token. This is also how the browser
     * restores the session when the page is loaded.
     *
     * @param refreshToken the cookie value, or {@code null} when the browser sent none
     * @throws UnauthorizedException if the token is missing, unknown, expired, revoked or reused, or the account may
     *     no longer sign in
     */
    public AuthSession refresh(String refreshToken, ClientInfo client) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException(SESSION_EXPIRED);
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken, client)
                .orElseThrow(() -> new UnauthorizedException(SESSION_EXPIRED));
        // The account is read again on every refresh, so a suspension ends the session within one access-token lifetime.
        UserResponse user = userService.findActiveById(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException(SESSION_EXPIRED));
        return new AuthSession(authResponse(user), rotation.token());
    }

    /** Ends the session the refresh token belongs to. Always succeeds: signing out twice, or with no token, is fine. */
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revoke(refreshToken);
        }
    }

    /**
     * Ends every session of the user on every device, this one included. Used when a phone is lost, or someone is not
     * sure who else is signed in. Access tokens that were already issued stay valid until they expire (at most 15 minutes).
     */
    public void logoutAll(UUID userId) {
        int ended = refreshTokens.revokeAll(userId);
        log.info("User {} signed out of all devices ({} session(s) ended)", userId, ended);
    }

    private AuthResponse authResponse(UserResponse user) {
        AccessTokenService.AccessToken accessToken = accessTokens.issue(user);
        return new AuthResponse(accessToken.value(), AuthResponse.BEARER, accessToken.expiresInSeconds(), user);
    }
}
