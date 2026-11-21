package com.resalemarket.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.location.entity.District;
import com.resalemarket.location.entity.Municipality;
import com.resalemarket.location.entity.Province;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Checks that a person's place makes sense (province -> district -> municipality) and turns the ids into names. */
@ExtendWith(MockitoExtension.class)
class LocationServiceResolveTest {

    @Mock
    private ProvinceRepository provinces;

    @Mock
    private DistrictRepository districts;

    @Mock
    private MunicipalityRepository municipalities;

    private LocationService service;

    @BeforeEach
    void setUp() {
        service = new LocationService(provinces, districts, municipalities, new LocationMapperImpl());
    }

    private static Province province(int id, String name) {
        Province province = new Province() { };
        ReflectionTestUtils.setField(province, "id", id);
        ReflectionTestUtils.setField(province, "name", name);
        return province;
    }

    private static District district(int id, int provinceId, String name) {
        District district = new District() { };
        ReflectionTestUtils.setField(district, "id", id);
        ReflectionTestUtils.setField(district, "provinceId", provinceId);
        ReflectionTestUtils.setField(district, "name", name);
        return district;
    }

    private static Municipality municipality(int id, int districtId, String name) {
        Municipality municipality = new Municipality() { };
        ReflectionTestUtils.setField(municipality, "id", id);
        ReflectionTestUtils.setField(municipality, "districtId", districtId);
        ReflectionTestUtils.setField(municipality, "name", name);
        return municipality;
    }

    private void bagmatiKathmandu() {
        when(provinces.findById(3)).thenReturn(Optional.of(province(3, "Bagmati")));
        when(districts.findById(25)).thenReturn(Optional.of(district(25, 3, "Kathmandu")));
    }

    @Test
    void noPlaceAtAllIsFine() {
        assertThat(service.resolve(null, null, null)).isEqualTo(LocationNames.NONE);

        verifyNoInteractions(provinces, districts, municipalities);
    }

    @Test
    void aProvinceAloneIsFine() {
        when(provinces.findById(3)).thenReturn(Optional.of(province(3, "Bagmati")));

        assertThat(service.resolve(3, null, null)).isEqualTo(new LocationNames("Bagmati", null, null));
    }

    @Test
    void aProvinceAndItsDistrict() {
        bagmatiKathmandu();

        assertThat(service.resolve(3, 25, null)).isEqualTo(new LocationNames("Bagmati", "Kathmandu", null));
    }

    @Test
    void aWholeChainGivesAllThreeNames() {
        bagmatiKathmandu();
        when(municipalities.findById(1)).thenReturn(Optional.of(municipality(1, 25, "Kathmandu Metropolitan City")));

        assertThat(service.resolve(3, 25, 1))
                .isEqualTo(new LocationNames("Bagmati", "Kathmandu", "Kathmandu Metropolitan City"));
    }

    private static void assertRejected(Runnable call, String field, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FieldValidationException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(400);
            assertThat(ex.getViolations()).singleElement().satisfies(violation -> {
                assertThat(violation.field()).isEqualTo(field);
                assertThat(violation.message()).isEqualTo(message);
            });
        });
    }

    @Test
    void aDistrictNeedsItsProvince() {
        assertRejected(() -> service.resolve(null, 25, null), "provinceId", "Choose a province first.");
    }

    @Test
    void aMunicipalityNeedsItsDistrict() {
        when(provinces.findById(3)).thenReturn(Optional.of(province(3, "Bagmati")));

        assertRejected(() -> service.resolve(3, null, 1), "districtId", "Choose a district first.");
    }

    @Test
    void anUnknownProvinceIsRejected() {
        when(provinces.findById(99)).thenReturn(Optional.empty());

        assertRejected(() -> service.resolve(99, null, null), "provinceId", "This province does not exist.");
    }

    @Test
    void aDistrictOfAnotherProvinceIsRejected() {
        when(provinces.findById(1)).thenReturn(Optional.of(province(1, "Koshi")));
        when(districts.findById(25)).thenReturn(Optional.of(district(25, 3, "Kathmandu")));

        assertRejected(() -> service.resolve(1, 25, null), "districtId", "This district is not in the chosen province.");
    }

    @Test
    void anUnknownDistrictIsRejected() {
        when(provinces.findById(3)).thenReturn(Optional.of(province(3, "Bagmati")));
        when(districts.findById(999)).thenReturn(Optional.empty());

        assertRejected(() -> service.resolve(3, 999, null), "districtId", "This district is not in the chosen province.");
    }

    @Test
    void aMunicipalityOfAnotherDistrictIsRejected() {
        bagmatiKathmandu();
        when(municipalities.findById(500)).thenReturn(Optional.of(municipality(500, 26, "Somewhere Else")));

        assertRejected(() -> service.resolve(3, 25, 500), "municipalityId", "This municipality is not in the chosen district.");
    }
}
