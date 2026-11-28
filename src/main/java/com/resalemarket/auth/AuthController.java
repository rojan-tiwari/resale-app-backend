package com.resalemarket.auth;

import com.resalemarket.auth.dto.AuthResponse;
import com.resalemarket.auth.dto.LoginRequest;
import com.resalemarket.auth.dto.RegisterRequest;
import com.resalemarket.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration and sign-in")
public class AuthController {

    /**
     * Request header the browser must add to the two calls that are authenticated by the refresh-token cookie. A cross-site
     * form or image request cannot set it, and a cross-origin script that tries must pass the CORS preflight first, so it
     * works as a CSRF defence on top of the cookie's SameSite attribute. Its value is not checked.
     */
    private static final String REQUESTED_WITH = "X-Requested-With";

    private final AuthService authService;
    private final RefreshCookies refreshCookies;

    public AuthController(AuthService authService, RefreshCookies refreshCookies) {
        this.authService = authService;
        this.refreshCookies = refreshCookies;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Create an account",
            description = "Public. Creates an active account with the USER role. It does not sign the user in: "
                    + "call the login endpoint afterwards.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Invalid input (VALIDATION_FAILED, with the offending fields in `errors`)")
    @ApiResponse(responseCode = "409", description = "The email address is already registered (CONFLICT)")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Sign in",
            description = "Public. Returns a short-lived access token (send it as `Authorization: Bearer <token>`) and the account. "
                    + "A long-lived refresh token is set as an HttpOnly cookie and is never part of the body.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "Email or password missing (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "401", description = "Unknown email or wrong password; the answer is the same for both (UNAUTHORIZED)")
    @ApiResponse(responseCode = "403", description = "The password is right but the account is suspended or locked (FORBIDDEN)")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return respond(authService.login(request, clientOf(http)));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Renew the session",
            description = "Public, but needs the refresh-token cookie. Rotates the refresh token (the old one stops working) and "
                    + "returns a new access token. The browser also calls this on page load to restore the session. "
                    + "Reusing an old refresh token ends the whole session.")
    @ApiResponse(responseCode = "200", description = "New access token; a new refresh-token cookie is set")
    @ApiResponse(responseCode = "400", description = "The X-Requested-With header is missing (BAD_REQUEST)")
    @ApiResponse(responseCode = "401", description = "No cookie, or the refresh token is unknown, expired, revoked or reused (UNAUTHORIZED)")
    public ResponseEntity<AuthResponse> refresh(
            @Parameter(description = "Any value, e.g. XMLHttpRequest. Cross-site requests cannot add this header, which keeps the cookie from being abused.")
                    @RequestHeader(name = REQUESTED_WITH)
                    String requestedWith,
            HttpServletRequest http) {
        return respond(authService.refresh(refreshCookies.read(http), clientOf(http)));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Sign out",
            description = "Public: it is the refresh-token cookie that identifies the session, so it also works after the access "
                    + "token has expired. Revokes the session and clears the cookie. Always answers 204.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    @ApiResponse(responseCode = "400", description = "The X-Requested-With header is missing (BAD_REQUEST)")
    public ResponseEntity<Void> logout(
            @Parameter(description = "Any value, e.g. XMLHttpRequest. See the refresh endpoint.")
                    @RequestHeader(name = REQUESTED_WITH)
                    String requestedWith,
            HttpServletRequest http) {
        authService.logout(refreshCookies.read(http));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> respond(AuthSession session) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (session.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookies.issue(session.refreshToken()).toString());
        }
        return response.body(session.response());
    }

    private static ClientInfo clientOf(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
