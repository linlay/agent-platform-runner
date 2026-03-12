package com.linlay.agentplatform.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledQueryRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidScheduleFile() throws Exception {
        Files.writeString(tempDir.resolve("daily.json"), """
                {
                  "name": "Daily Summary",
                  "enabled": true,
                  "cron": "0 0 9 * * *",
                  "agentKey": "demoModePlain",
                  "query": "ping",
                  "params": {
                    "k": "v"
                  }
                }
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        ScheduledQueryDescriptor descriptor = service.find("daily").orElseThrow();
        assertThat(descriptor.id()).isEqualTo("daily");
        assertThat(descriptor.name()).isEqualTo("Daily Summary");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.cron()).isEqualTo("0 0 9 * * *");
        assertThat(descriptor.agentKey()).isEqualTo("demoModePlain");
        assertThat(descriptor.teamId()).isNull();
        assertThat(descriptor.query()).isEqualTo("ping");
        assertThat(descriptor.params()).containsEntry("k", "v");
    }

    @Test
    void shouldSkipInvalidCronOrMissingTarget() throws Exception {
        Files.writeString(tempDir.resolve("invalid-cron.json"), """
                {
                  "cron": "invalid cron",
                  "agentKey": "demoModePlain",
                  "query": "ping"
                }
                """);
        Files.writeString(tempDir.resolve("missing-target.json"), """
                {
                  "cron": "0 0 9 * * *",
                  "query": "ping"
                }
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldLoadViewportWeatherMinutelyExample() throws Exception {
        Files.writeString(tempDir.resolve("demo_viewport_weather_minutely.json"), """
                {
                  "name": "Demo Viewport Weather Minutely",
                  "enabled": true,
                  "cron": "0 * * * * *",
                  "zoneId": "Asia/Shanghai",
                  "agentKey": "demoViewport",
                  "query": "请从以下城市中随机选择一个：北京、深圳、大连、广州、上海、纽约、巴黎、东京。调用天气工具查询该城市当前天气；如果工具返回了可用的 viewport 结果，请按约定输出 viewport 视图块。"
                }
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        ScheduledQueryDescriptor descriptor = service.find("demo_viewport_weather_minutely").orElseThrow();
        assertThat(descriptor.name()).isEqualTo("Demo Viewport Weather Minutely");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.cron()).isEqualTo("0 * * * * *");
        assertThat(descriptor.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(descriptor.agentKey()).isEqualTo("demoViewport");
        assertThat(descriptor.query()).contains("北京").contains("东京").contains("viewport");
    }
}
