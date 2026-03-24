package com.linlay.agentplatform.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCatalogNamingTest {

    @Test
    void shouldClassifyRuntimeNamesBySuffixMarker() {
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimeName("demoModePlain")).isTrue();
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimeName("agent.demo")).isTrue();
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimeName("agent.demo.yml")).isTrue();
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimeName("agent.example")).isFalse();
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimeName("agent.example.yml")).isFalse();
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimeName(".hidden.example")).isFalse();
    }

    @Test
    void shouldResolveLogicalBaseNameByRemovingMarkerBeforeExtension() {
        assertThat(RuntimeCatalogNaming.logicalBaseName("auth.yml")).isEqualTo("auth");
        assertThat(RuntimeCatalogNaming.logicalBaseName("auth.demo.yml")).isEqualTo("auth");
        assertThat(RuntimeCatalogNaming.logicalBaseName("auth.example.yml")).isEqualTo("auth");
        assertThat(RuntimeCatalogNaming.logicalBaseName("owner.example")).isEqualTo("owner");
    }

    @Test
    void shouldApplyPathBasedFilter() {
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimePath(Path.of("/tmp/demo.yml"))).isTrue();
        assertThat(RuntimeCatalogNaming.shouldLoadRuntimePath(Path.of("/tmp/demo.example.yml"))).isFalse();
    }
}
