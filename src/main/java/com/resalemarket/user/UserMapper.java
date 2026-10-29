package com.resalemarket.user;

import com.resalemarket.user.dto.UserResponse;
import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserProfile;
import java.util.List;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity -> DTO mapping. The password hash is not part of {@link UserResponse}, so it cannot be mapped by accident. */
@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "displayName", source = "profile.displayName")
    @Mapping(target = "phone", source = "profile.phone")
    @Mapping(target = "createdAt", source = "user.createdAt")
    @Mapping(target = "roles", source = "user.roles")
    UserResponse toResponse(User user, UserProfile profile);

    /** Role names in a stable order (the entity holds a set), so responses and tokens do not change between calls. */
    default List<String> roleNames(Set<Role> roles) {
        return roles.stream().map(Role::getName).sorted().toList();
    }
}
