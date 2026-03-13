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
        Files.writeString(tempDir.resolve("daily.yml"), """
                name: 每日摘要
                description: 每天早上 9 点执行一次摘要查询
                enabled: true
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query: ping
                params:
                  k: v
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        ScheduledQueryDescriptor descriptor = service.find("daily").orElseThrow();
        assertThat(descriptor.id()).isEqualTo("daily");
        assertThat(descriptor.name()).isEqualTo("每日摘要");
        assertThat(descriptor.description()).isEqualTo("每天早上 9 点执行一次摘要查询");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.cron()).isEqualTo("0 0 9 * * *");
        assertThat(descriptor.agentKey()).isEqualTo("demoModePlain");
        assertThat(descriptor.teamId()).isNull();
        assertThat(descriptor.query()).isEqualTo("ping");
        assertThat(descriptor.params()).containsEntry("k", "v");
    }

    @Test
    void shouldSkipInvalidCronOrMissingTarget() throws Exception {
        Files.writeString(tempDir.resolve("invalid-cron.yml"), """
                name: Invalid Cron
                description: cron 非法
                cron: "invalid cron"
                agentKey: demoModePlain
                query: ping
                """);
        Files.writeString(tempDir.resolve("missing-target.yml"), """
                name: Missing Target
                description: 缺少执行目标
                cron: "0 0 9 * * *"
                query: ping
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldLoadViewportWeatherMinutelyExample() throws Exception {
        Files.writeString(tempDir.resolve("demo_viewport_weather_minutely.yml"), """
                name: 分钟天气视图播报
                description: 每分钟触发 demoViewport 查询随机城市天气
                enabled: true
                cron: "0 * * * * *"
                zoneId: Asia/Shanghai
                agentKey: demoViewport
                query: 请从以下城市中随机选择一个：北京、深圳、大连、广州、上海、纽约、巴黎、东京。调用天气工具查询该城市当前天气；如果工具返回了可用的 viewport 结果，请按约定输出 viewport 视图块。
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        ScheduledQueryDescriptor descriptor = service.find("demo_viewport_weather_minutely").orElseThrow();
        assertThat(descriptor.name()).isEqualTo("分钟天气视图播报");
        assertThat(descriptor.description()).isEqualTo("每分钟触发 demoViewport 查询随机城市天气");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.cron()).isEqualTo("0 * * * * *");
        assertThat(descriptor.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(descriptor.agentKey()).isEqualTo("demoViewport");
        assertThat(descriptor.query()).contains("北京").contains("东京").contains("viewport");
    }

    @Test
    void shouldIgnoreLegacyJsonFiles() throws Exception {
        Files.writeString(tempDir.resolve("legacy.json"), """
                {"name":"legacy","description":"legacy","cron":"0 0 9 * * *","agentKey":"demo","query":"ping"}
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldRejectInvalidHeaderDisclosure() throws Exception {
        Files.writeString(tempDir.resolve("blank-prefix.yml"), """

                name: Bad
                description: 有空行前缀
                cron: "0 0 9 * * *"
                agentKey: demo
                query: ping
                """);
        Files.writeString(tempDir.resolve("wrong-order.yml"), """
                description: 先写了描述
                name: Wrong Order
                cron: "0 0 9 * * *"
                agentKey: demo
                query: ping
                """);
        Files.writeString(tempDir.resolve("multi-line-description.yml"), """
                name: Multi Line
                description: |
                  不支持
                cron: "0 0 9 * * *"
                agentKey: demo
                query: ping
                """);

        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        ScheduledQueryRegistryService service = new ScheduledQueryRegistryService(new ObjectMapper(), properties);

        assertThat(service.list()).isEmpty();
    }
}
