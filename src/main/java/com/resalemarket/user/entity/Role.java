package com.resalemarket.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A role such as {@code ROLE_USER}. Reference data seeded by Flyway (V8); read-only for the application. */
@Entity
@Immutable
@Table(name = "roles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role {

    /** Granted to every registered account. */
    public static final String USER = "ROLE_USER";

    public static final String ADMIN = "ROLE_ADMIN";

    @Id
    private Integer id;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(length = 200)
    private String description;
}
