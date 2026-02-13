package com.linlay.springaiagw.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.springaiagw.config.ViewportCatalogProperties;
import com.linlay.springaiagw.model.agw.ViewportType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ViewportRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadHtmlAndQlcViewports() throws Exception {
        Files.writeString(tempDir.resolve("weather_card.html"), "<div>ok</div>");
        Files.writeString(tempDir.resolve("flight_form.qlc"), "{\"schema\":{\"type\":\"object\"},\"packages\":[\"pkg-a\"]}");

        ViewportCatalogProperties properties = new ViewportCatalogProperties();
        properties.setExternalDir(tempDir.toString());
        ViewportRegistryService service = new ViewportRegistryService(new ObjectMapper(), properties);

        Optional<ViewportRegistryService.ViewportEntry> html = service.find("weather_card");
        Optional<ViewportRegistryService.ViewportEntry> qlc = service.find("flight_form");

        assertThat(html).isPresent();
        assertThat(html.get().viewportType()).isEqualTo(ViewportType.HTML);
        assertThat(html.get().payload()).isEqualTo("<div>ok</div>");

        assertThat(qlc).isPresent();
        assertThat(qlc.get().viewportType()).isEqualTo(ViewportType.QLC);
        assertThat(qlc.get().payload()).isInstanceOf(java.util.Map.class);
    }

    @Test
    void shouldSkipInvalidJsonViewport() throws Exception {
        Files.writeString(tempDir.resolve("bad.qlc"), "{invalid-json");

        ViewportCatalogProperties properties = new ViewportCatalogProperties();
        properties.setExternalDir(tempDir.toString());
        ViewportRegistryService service = new ViewportRegistryService(new ObjectMapper(), properties);

        assertThat(service.find("bad")).isEmpty();
    }
}
