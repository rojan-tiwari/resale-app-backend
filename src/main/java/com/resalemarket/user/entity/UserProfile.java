package com.resalemarket.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** The editable, partly public side of an account (1:1 with {@link User}, sharing its primary key). */
@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    /** The id of the owning user ({@link MapsId}). */
    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    /** E.164 Nepali mobile number ({@code +9779XXXXXXXXX}) or {@code null}. */
    @Column(length = 20)
    private String phone;

    /** The phone number is only public when the user has explicitly agreed. */
    @Column(name = "show_phone", nullable = false)
    private boolean showPhone;

    /** A few words about the person, shown on their public profile. */
    @Column(length = 500)
    private String bio;

    /**
     * Where the person is, as ids of the {@code location} module's reference data (this module does not own those tables,
     * so it holds plain ids). The database keeps the three consistent with composite foreign keys.
     */
    @Column(name = "province_id")
    private Integer provinceId;

    @Column(name = "district_id")
    private Integer districtId;

    @Column(name = "municipality_id")
    private Integer municipalityId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public static UserProfile create(User user, String displayName, String phone) {
        UserProfile profile = new UserProfile();
        profile.user = user;
        profile.displayName = displayName.strip();
        profile.phone = phone;
        return profile;
    }

    /**
     * Replaces everything the owner can edit (the profile form is saved as a whole). {@code phone} is already in E.164 form;
     * optional values arrive as {@code null} when the person cleared them. The caller has checked that the location ids
     * belong together and that {@code showPhone} is only set when there is a phone number.
     */
    public void update(
            String displayName,
            String phone,
            boolean showPhone,
            String bio,
            Integer provinceId,
            Integer districtId,
            Integer municipalityId) {
        this.displayName = displayName.strip();
        this.phone = phone;
        this.showPhone = showPhone;
        this.bio = bio;
        this.provinceId = provinceId;
        this.districtId = districtId;
        this.municipalityId = municipalityId;
    }
}
