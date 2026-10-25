package com.resalemarket.user;

import com.resalemarket.user.dto.UserResponse;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserProfile;
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
    UserResponse toResponse(User user, UserProfile profile);
}
