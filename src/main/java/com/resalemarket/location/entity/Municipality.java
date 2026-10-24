package com.resalemarket.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A city or municipality. Major cities ({@code major = true}) are listed first in location pickers. */
@Entity
@Immutable
@Table(name = "municipalities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Municipality {

    @Id
    private Integer id;

    @Column(name = "district_id", nullable = false)
    private Integer districtId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 120)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MunicipalityType type;

    @Column(name = "is_major", nullable = false)
    private boolean major;
}
