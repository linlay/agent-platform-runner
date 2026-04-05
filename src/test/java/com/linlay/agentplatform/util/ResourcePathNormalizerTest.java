package com.linlay.agentplatform.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourcePathNormalizerTest {

    @Test
    void shouldNormalizeResourceApiReference() {
        assertThat(ResourcePathNormalizer.normalizeAssetReference("/api/resource?file=folder%2Fimage.png"))
                .isEqualTo("folder/image.png");
    }

    @Test
    void shouldTreatRemovedDataApiReferenceAsPlainRelativePath() {
        assertThat(ResourcePathNormalizer.normalizeAssetReference("/api/data?file=legacy_api_image.png"))
                .isEqualTo("api/data");
    }
}
