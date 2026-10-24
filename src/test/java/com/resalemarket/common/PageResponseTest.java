package com.resalemarket.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.common.response.PageResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void describesAMiddlePage() {
        PageImpl<String> page = new PageImpl<>(List.of("c", "d"), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly("c", "d");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isFalse();
    }

    @Test
    void marksFirstAndLastPages() {
        assertThat(PageResponse.from(new PageImpl<>(List.of("a"), PageRequest.of(0, 2), 3)).first()).isTrue();
        assertThat(PageResponse.from(new PageImpl<>(List.of("c"), PageRequest.of(1, 2), 3)).last()).isTrue();
    }

    @Test
    void mapsEntitiesToDtosWhileBuildingTheEnvelope() {
        PageImpl<String> page = new PageImpl<>(List.of("ab", "abc"), PageRequest.of(0, 10), 2);

        PageResponse<Integer> response = PageResponse.from(page, String::length);

        assertThat(response.content()).containsExactly(2, 3);
        assertThat(response.totalElements()).isEqualTo(2);
    }
}
