package com.resalemarket.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One of Nepal's 7 provinces. Reference data seeded by Flyway; read-only for the application. */
@Entity
@Immutable
@Table(name = "provinces")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Province {

    /** The official province number (1..7), so ids are stable across environments. */
    @Id
    private Integer id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "name_ne", length = 80)
    private String nameNe;
}
