package com.linlay.agentplatform.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogDiffTest {

    @Test
    void shouldDetectAddedRemovedAndUpdatedKeys() {
        Map<String, String> before = Map.of(
                "a", "1",
                "b", "2",
                "c", "3"
        );
        Map<String, String> after = Map.of(
                "b", "2",
                "c", "33",
                "d", "4"
        );

        CatalogDiff diff = CatalogDiff.between(before, after);
        assertThat(diff.addedKeys()).containsExactly("d");
        assertThat(diff.removedKeys()).containsExactly("a");
        assertThat(diff.updatedKeys()).containsExactly("c");
        assertThat(diff.changedKeys()).containsExactlyInAnyOrder("a", "c", "d");
        assertThat(diff.isEmpty()).isFalse();
    }

    @Test
    void shouldBeEmptyWhenCatalogUnchanged() {
        Map<String, String> before = Map.of("a", "1");
        Map<String, String> after = Map.of("a", "1");

        CatalogDiff diff = CatalogDiff.between(before, after);
        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.changedKeys()).isEmpty();
    }
}
