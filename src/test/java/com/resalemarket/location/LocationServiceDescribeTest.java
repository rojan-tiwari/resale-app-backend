package com.resalemarket.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.location.dto.LocationRef;
import com.resalemarket.location.entity.District;
import com.resalemarket.location.entity.Municipality;
import com.resalemarket.location.entity.Province;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Names for many places at once: the lists of ads must not cost three queries per ad. */
@ExtendWith(MockitoExtension.class)
class LocationServiceDescribeTest {

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

    @Test
    void manyPlacesAreNamedWithOneQueryPerLevel() {
        Province bagmati = province(3, "Bagmati");
        Province gandaki = province(4, "Gandaki");
        District kathmandu = district(25, 3, "Kathmandu");
        District kaski = district(40, 4, "Kaski");
        Municipality ktm = municipality(1, 25, "Kathmandu");
        when(provinces.findAllById(anyCollection())).thenReturn(List.of(bagmati, gandaki));
        when(districts.findAllById(anyCollection())).thenReturn(List.of(kathmandu, kaski));
        when(municipalities.findAllById(anyCollection())).thenReturn(List.of(ktm));

        LocationRef inKathmandu = new LocationRef(3, 25, 1);
        LocationRef inKaski = new LocationRef(4, 40, null);
        LocationRef sameAgain = new LocationRef(3, 25, 1);

        Map<LocationRef, LocationNames> names = service.describe(List.of(inKathmandu, inKaski, sameAgain));

        assertThat(names).hasSize(2);
        assertThat(names.get(inKathmandu)).isEqualTo(new LocationNames("Bagmati", "Kathmandu", "Kathmandu"));
        assertThat(names.get(inKaski)).isEqualTo(new LocationNames("Gandaki", "Kaski", null));
        verify(provinces).findAllById(Set.of(3, 4));
        verify(districts).findAllById(Set.of(25, 40));
        verify(municipalities).findAllById(Set.of(1));
    }

    @Test
    void levelsNobodyUsesCostNoQuery() {
        when(provinces.findAllById(anyCollection())).thenReturn(List.of(province(3, "Bagmati")));

        Map<LocationRef, LocationNames> names = service.describe(List.of(new LocationRef(3, null, null)));

        assertThat(names.get(new LocationRef(3, null, null))).isEqualTo(new LocationNames("Bagmati", null, null));
        verify(districts, never()).findAllById(anyCollection());
        verify(municipalities, never()).findAllById(anyCollection());
    }

    @Test
    void anIdThatDoesNotExistGivesANullName() {
        when(provinces.findAllById(anyCollection())).thenReturn(List.of());

        assertThat(service.describe(List.of(new LocationRef(99, null, null))).get(new LocationRef(99, null, null)))
                .isEqualTo(new LocationNames(null, null, null));
    }

    @Test
    void nothingToDescribeCostsNothing() {
        assertThat(service.describe(List.of())).isEmpty();
        verify(provinces, never()).findAllById(anyCollection());
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
}
