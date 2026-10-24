package com.resalemarket.location.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One of Nepal's 77 districts. The parent is referenced by its id only (no association): locations are
 * small reference data, so a plain foreign-key column keeps queries trivial and rules out lazy-loading surprises.
 */
@Entity
@Immutable
@Table(name = "districts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class District {

    @Id
    private Integer id;

    @Column(name = "province_id", nullable = false)
    private Integer provinceId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;
}
