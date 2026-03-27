package com.linlay.agentplatform.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataFilePathNormalizerTest {

    @Test
    void shouldNormalizeResourceApiReference() {
        assertThat(DataFilePathNormalizer.normalizeAssetReference("/api/resource?file=folder%2Fimage.png"))
                .isEqualTo("folder/image.png");
    }

    @Test
    void shouldRejectRemovedDataApiReference() {
        assertThat(DataFilePathNormalizer.normalizeAssetReference("/api/data?file=legacy_api_image.png"))
                .isNull();
    }
}
