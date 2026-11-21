package com.resalemarket.auth;

import com.resalemarket.auth.dto.ForgotPasswordRequest;
import com.resalemarket.auth.dto.ResetPasswordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forgotten and reset passwords. They live in the {@code auth} module, not in {@code user}, because resetting a password
 * ends sessions and sessions are owned here.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Authentication", description = "Registration and sign-in")
public class PasswordController {

    private final PasswordResetService passwordReset;
    private final AuthRateLimits rateLimits;

    public PasswordController(PasswordResetService passwordReset, AuthRateLimits rateLimits) {
        this.passwordReset = passwordReset;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/auth/forgot-password")
    @Operation(
            summary = "Ask for a password-reset link",
            description = "Public. If the address belongs to an active account, an e-mail with a one-time link is sent to it. "
                    + "The answer is always the same 202 and takes the same time, whether or not the address has an account, "
                    + "so the endpoint cannot be used to find out who is registered.")
    @ApiResponse(responseCode = "202", description = "Accepted. Nothing is revealed about the address")
    @ApiResponse(responseCode = "400", description = "Missing or malformed address (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "429", description = "Too many requests from this address or for this e-mail (TOO_MANY_REQUESTS, with `Retry-After`)")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        rateLimits.forgotPassword(AuthController.clientOf(http), request.email());
        passwordReset.requestReset(request.email());   // runs in the background
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/auth/reset-password")
    @Operation(
            summary = "Set a new password with a reset link",
            description = "Public. The `token` comes from the e-mailed link. It works once and expires after one hour. "
                    + "Success ends every session of the account on every device, and lifts a lock-out.")
    @ApiResponse(responseCode = "204", description = "The password was changed; sign in with the new one")
    @ApiResponse(responseCode = "400", description = "The new password breaks a rule (VALIDATION_FAILED), or the link is unknown, used or expired (INVALID_TOKEN)")
    @ApiResponse(responseCode = "429", description = "Too many attempts from this address (TOO_MANY_REQUESTS, with `Retry-After`)")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        rateLimits.resetPassword(AuthController.clientOf(http));
        passwordReset.reset(request.token(), request.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
