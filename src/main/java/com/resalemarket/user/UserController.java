package com.resalemarket.user;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.user.dto.PublicUserResponse;
import com.resalemarket.user.dto.UpdateProfileRequest;
import com.resalemarket.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's own profile, and the public profile of any active member. Changing the password is in
 * {@code auth.PasswordController}, because it also ends sessions, which is the {@code auth} module's business.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Own profile and public member profiles")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(
            summary = "Own profile",
            description = "Needs an access token. Returns the account with its private fields (e-mail, phone, consent flag).",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "401", description = "No access token, or it is invalid or expired, or the account is no longer active (UNAUTHORIZED)")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        // Deny-by-default security guarantees a verified access token here; its subject is the user's id.
        return noStore(userService.getActiveAccount(UUID.fromString(authentication.getName())));
    }

    @PutMapping("/me")
    @Operation(
            summary = "Update own profile",
            description = "Needs an access token. Replaces the editable profile as a whole: display name, phone, whether to show it, "
                    + "bio and location. An optional field that is null or missing is cleared.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The saved account")
    @ApiResponse(responseCode = "400", description = "Invalid input, or a location that does not fit together (VALIDATION_FAILED, with the fields in `errors`)")
    @ApiResponse(responseCode = "401", description = "No access token, or it is invalid or expired (UNAUTHORIZED)")
    @ApiResponse(responseCode = "409", description = "The profile was changed elsewhere in the meantime; reload and try again (CONCURRENT_MODIFICATION)")
    public ResponseEntity<UserResponse> updateMe(
            Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        return noStore(userService.updateProfile(UUID.fromString(authentication.getName()), request));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Public profile of a member",
            description = "Public. Display name, bio, member since and place. The e-mail address is never included, and the "
                    + "phone number only when its owner chose to show it. Suspended and deleted accounts answer 404.")
    @ApiResponse(responseCode = "200", description = "The public profile")
    @ApiResponse(responseCode = "404", description = "No active account with this id (NOT_FOUND)")
    public PublicUserResponse publicProfile(@PathVariable UUID id) {
        return userService.getPublicProfile(id);
    }

    /** Own data is private: no browser or proxy may keep a copy. */
    private static ResponseEntity<UserResponse> noStore(UserResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
