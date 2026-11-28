package com.resalemarket.user;

import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserStatus;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/** Entities have no setters on purpose (state changes go through domain methods), so tests build them here. */
final class UserFixtures {

    static final String HASH = "{bcrypt}$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz0123456";

    private UserFixtures() {
    }

    static Role role(String name) {
        Role role = new RoleStub();
        ReflectionTestUtils.setField(role, "id", 1);
        ReflectionTestUtils.setField(role, "name", name);
        return role;
    }

    static User user(String email, Role... roles) {
        User user = User.register(email, HASH, roles.length > 0 ? roles[0] : role(Role.USER));
        for (int i = 1; i < roles.length; i++) {
            user.getRoles().add(roles[i]);
        }
        return user;
    }

    /** Ids are assigned by the database on insert, so a user loaded "from the database" gets one here. */
    static User withIdAndStatus(User user, UUID id, UserStatus status) {
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }

    /** {@link Role}'s constructor is protected; a subclass in the test tree is the simplest way to get an instance. */
    private static final class RoleStub extends Role {
    }
}
