package com.resalemarket.category.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A category (a root) or a subcategory (its parent is a root); the database allows two levels only. The parent is a plain id,
 * as in the {@code location} module, so loading categories never triggers lazy-loading surprises.
 *
 * <p>Read-only for now: the admin management of Phase 6 will lift {@link Immutable}.
 */
@Entity
@Immutable
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** {@code null} for a root category. */
    @Column(name = "parent_id")
    private Integer parentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 120)
    private String slug;

    /** Icon key that the frontend maps to a drawing. */
    @Column(length = 50)
    private String icon;

    @Column(length = 300)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** An inactive category is hidden from pickers; listings already in it keep it. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    public boolean isRoot() {
        return parentId == null;
    }
}
