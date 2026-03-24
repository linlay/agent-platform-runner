package com.linlay.agentplatform.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledQueryRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadValidStructuredScheduleFile() throws Exception {
        Files.writeString(tempDir.resolve("daily.yml"), """
                name: 每日摘要
                description: 每天早上 9 点执行一次摘要查询
                enabled: true
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                environment:
                  zoneId: Asia/Shanghai
                query:
                  requestId: req_daily_001
                  role: system
                  message: ping
                  chatId: 123e4567-e89b-12d3-a456-426614174000
                  references:
                    - id: ref_001
                      type: url
                      name: doc
                      url: https://example.com/doc
                  params:
                    k: v
                  scene:
                    url: https://example.com/app
                    title: demo
                  hidden: true
                """);

        ScheduledQueryRegistryService service = newService(mock(TeamRegistryService.class));

        ScheduledQueryDescriptor descriptor = service.find("daily").orElseThrow();
        assertThat(descriptor.id()).isEqualTo("daily");
        assertThat(descriptor.name()).isEqualTo("每日摘要");
        assertThat(descriptor.description()).isEqualTo("每天早上 9 点执行一次摘要查询");
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.cron()).isEqualTo("0 0 9 * * *");
        assertThat(descriptor.agentKey()).isEqualTo("demoModePlain");
        assertThat(descriptor.teamId()).isNull();
        assertThat(descriptor.environment().zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(descriptor.query().requestId()).isEqualTo("req_daily_001");
        assertThat(descriptor.query().role()).isEqualTo("system");
        assertThat(descriptor.query().message()).isEqualTo("ping");
        assertThat(descriptor.query().chatId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(descriptor.query().references()).hasSize(1);
        assertThat(descriptor.query().references().getFirst()).isEqualTo(new QueryRequest.Reference(
                "ref_001",
                "url",
                "doc",
                null,
                null,
                "https://example.com/doc",
                null,
                null
        ));
        assertThat(descriptor.query().params()).containsEntry("k", "v");
        assertThat(descriptor.query().scene()).isEqualTo(new QueryRequest.Scene("https://example.com/app", "demo"));
        assertThat(descriptor.query().hidden()).isTrue();
    }

    @Test
    void shouldLoadViewportWeatherScheduleWithoutOptionalQueryFields() throws Exception {
        Files.writeString(tempDir.resolve("demo_viewport_weather_minutely.yml"), """
                name: 分钟天气视图播报
                description: 每分钟触发 demoViewport 查询随机城市天气
                enabled: true
                cron: "0 * * * * *"
                agentKey: demoViewport
                environment:
                  zoneId: Asia/Shanghai
                query:
                  message: 请从以下城市中随机选择一个：北京、深圳、大连、广州、上海、纽约、巴黎、东京。调用天气工具查询该城市当前天气；如果工具返回了可用的 viewport 结果，请按约定输出 viewport 视图块。
                """);

        ScheduledQueryRegistryService service = newService(mock(TeamRegistryService.class));

        ScheduledQueryDescriptor descriptor = service.find("demo_viewport_weather_minutely").orElseThrow();
        assertThat(descriptor.enabled()).isTrue();
        assertThat(descriptor.environment().zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(descriptor.query().message()).contains("北京").contains("东京").contains("viewport");
        assertThat(descriptor.query().requestId()).isNull();
        assertThat(descriptor.query().role()).isNull();
        assertThat(descriptor.query().chatId()).isNull();
        assertThat(descriptor.query().references()).isEmpty();
        assertThat(descriptor.query().params()).isEmpty();
        assertThat(descriptor.query().scene()).isNull();
        assertThat(descriptor.query().hidden()).isNull();
    }

    @Test
    void shouldRejectLegacyFlatFormatAndMissingStructuredFields() throws Exception {
        Files.writeString(tempDir.resolve("legacy-flat.yml"), """
                name: Legacy
                description: 旧格式
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                zoneId: Asia/Shanghai
                query: ping
                """);
        Files.writeString(tempDir.resolve("missing-query-message.yml"), """
                name: Missing Query Message
                description: query.message 缺失
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  chatId: 123e4567-e89b-12d3-a456-426614174000
                """);
        Files.writeString(tempDir.resolve("invalid-chat-id.yml"), """
                name: Invalid Chat Id
                description: chatId 非法
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                  chatId: not-a-uuid
                """);
        Files.writeString(tempDir.resolve("invalid-zone.yml"), """
                name: Invalid Zone
                description: zoneId 非法
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                environment:
                  zoneId: Mars/Base
                query:
                  message: ping
                """);
        Files.writeString(tempDir.resolve("team-only.yml"), """
                name: Team Only
                description: 仅 teamId
                cron: "0 0 9 * * *"
                teamId: a1b2c3d4e5f6
                query:
                  message: ping
                """);
        Files.writeString(tempDir.resolve("query-stream.yml"), """
                name: Query Stream
                description: 不支持 stream
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                  stream: true
                """);
        Files.writeString(tempDir.resolve("query-agent-key.yml"), """
                name: Query AgentKey
                description: query.agentKey 不允许
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                  agentKey: demoModeReact
                """);
        Files.writeString(tempDir.resolve("query-team-id.yml"), """
                name: Query TeamId
                description: query.teamId 不允许
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                  teamId: a1b2c3d4e5f6
                """);
        Files.writeString(tempDir.resolve("query-unknown.yml"), """
                name: Query Unknown
                description: query.foo 不允许
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                  foo: bar
                """);

        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        when(teamRegistryService.find("a1b2c3d4e5f6")).thenReturn(Optional.of(new TeamDescriptor(
                "a1b2c3d4e5f6",
                "Default Team",
                List.of("demoModeReact"),
                "demoModeReact",
                "/tmp/team.yml"
        )));

        ScheduledQueryRegistryService service = newService(teamRegistryService);

        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldRejectUnknownOrMismatchedTeamScopedAgent() throws Exception {
        Files.writeString(tempDir.resolve("missing-team.yml"), """
                name: Missing Team
                description: team 不存在
                cron: "0 0 9 * * *"
                agentKey: demoModeReact
                teamId: deadbeefcafe
                query:
                  message: ping
                """);
        Files.writeString(tempDir.resolve("team-mismatch.yml"), """
                name: Team Mismatch
                description: agent 不属于 team
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                teamId: a1b2c3d4e5f6
                query:
                  message: ping
                """);

        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        when(teamRegistryService.find("a1b2c3d4e5f6")).thenReturn(Optional.of(new TeamDescriptor(
                "a1b2c3d4e5f6",
                "Default Team",
                List.of("demoModeReact"),
                "demoModeReact",
                "/tmp/team.yml"
        )));
        when(teamRegistryService.find("deadbeefcafe")).thenReturn(Optional.empty());

        ScheduledQueryRegistryService service = newService(teamRegistryService);

        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldIgnoreLegacyJsonFiles() throws Exception {
        Files.writeString(tempDir.resolve("legacy.json"), """
                {"name":"legacy","description":"legacy","cron":"0 0 9 * * *","agentKey":"demo","query":{"message":"ping"}}
                """);

        ScheduledQueryRegistryService service = newService(mock(TeamRegistryService.class));

        assertThat(service.list()).isEmpty();
    }

    @Test
    void shouldLoadScheduleFromNestedDirectory() throws Exception {
        Path nestedDir = tempDir.resolve("nested");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("daily.yml"), """
                name: 每日摘要
                description: 每天早上 9 点执行
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                """);

        ScheduledQueryRegistryService service = newService(mock(TeamRegistryService.class));

        assertThat(service.find("daily")).isPresent();
    }

    @Test
    void shouldIgnoreExampleScheduleAndLoadDemoSchedule() throws Exception {
        Files.writeString(tempDir.resolve("daily.example.yml"), """
                name: Example Schedule
                description: 模板计划
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ignored
                """);
        Files.writeString(tempDir.resolve("daily.demo.yml"), """
                name: Demo Schedule
                description: demo 计划
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: loaded
                """);

        ScheduledQueryRegistryService service = newService(mock(TeamRegistryService.class));

        assertThat(service.find("daily")).isPresent();
        assertThat(service.find("daily").orElseThrow().description()).isEqualTo("demo 计划");
    }

    @Test
    void shouldRejectInvalidHeaderDisclosure() throws Exception {
        Files.writeString(tempDir.resolve("blank-prefix.yml"), """

                name: Bad
                description: 有空行前缀
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                """);
        Files.writeString(tempDir.resolve("wrong-order.yml"), """
                description: 先写了描述
                name: Wrong Order
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                """);
        Files.writeString(tempDir.resolve("multi-line-description.yml"), """
                name: Multi Line
                description: |
                  不支持
                cron: "0 0 9 * * *"
                agentKey: demoModePlain
                query:
                  message: ping
                """);

        ScheduledQueryRegistryService service = newService(mock(TeamRegistryService.class));

        assertThat(service.list()).isEmpty();
    }

    private ScheduledQueryRegistryService newService(TeamRegistryService teamRegistryService) {
        ScheduleProperties properties = new ScheduleProperties();
        properties.setExternalDir(tempDir.toString());
        return new ScheduledQueryRegistryService(new ObjectMapper(), properties, teamRegistryService);
    }
}
