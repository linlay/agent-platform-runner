package com.linlay.agentplatform.schedule;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.service.AgentQueryService;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledQueryDispatchServiceTest {

    @Test
    void shouldDispatchTeamScopedAgentWithChatIdAndParams() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        SchedulePushNotifier pushNotifier = mock(SchedulePushNotifier.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService, pushNotifier, objectMapper);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            TeamDescriptor team = new TeamDescriptor(
                    "a1b2c3d4e5f6",
                    "Default Team",
                    List.of("demoModeReact"),
                    "demoModeReact",
                    "/tmp/a1b2c3d4e5f6.yml"
            );
            when(teamRegistryService.find("a1b2c3d4e5f6")).thenReturn(Optional.of(team));

            AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                    null,
                    new StreamRequest.Query(
                            "req_1",
                            "123e4567-e89b-12d3-a456-426614174000",
                            "user",
                            "message",
                            "demoModeReact",
                            "a1b2c3d4e5f6",
                            null,
                            null,
                            null,
                            false,
                            "chat",
                            "run_1"
                    ),
                    new AgentRequest("message", UUID.randomUUID().toString(), "req_1", "run_1", Map.of())
            );
            when(agentQueryService.prepare(any(QueryRequest.class))).thenReturn(session);
            when(agentQueryService.stream(any(AgentQueryService.QuerySession.class))).thenReturn(Flux.empty());

            ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                    "daily",
                    "Daily Summary",
                    "每天早上汇总一次",
                    true,
                    "0 0 9 * * *",
                    "demoModeReact",
                    "a1b2c3d4e5f6",
                    new ScheduledQueryDescriptor.Environment("Asia/Shanghai"),
                    new ScheduledQueryDescriptor.Query(
                            "req_daily_001",
                            "123e4567-e89b-12d3-a456-426614174000",
                            "assistant",
                            "hello",
                            List.of(new QueryRequest.Reference(
                                    "ref_001",
                                    "url",
                                    "doc",
                                    null,
                                    null,
                                    "https://example.com/doc",
                                    null,
                                    null,
                                    null
                            )),
                            Map.of("x", 1),
                            new QueryRequest.Scene("https://example.com/app", "demo"),
                            true
                    ),
                    null,
                    null,
                    "/tmp/daily.yml"
            );
            service.dispatch(descriptor);

            ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(agentQueryService).prepare(requestCaptor.capture());
            verify(agentQueryService).stream(any(AgentQueryService.QuerySession.class));

            QueryRequest request = requestCaptor.getValue();
            assertThat(request.requestId()).isEqualTo("req_daily_001");
            assertThat(request.agentKey()).isEqualTo("demoModeReact");
            assertThat(request.teamId()).isEqualTo("a1b2c3d4e5f6");
            assertThat(request.chatId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
            assertThat(request.message()).isEqualTo("hello");
            assertThat(request.role()).isEqualTo("assistant");
            assertThat(request.references()).hasSize(1);
            assertThat(request.references().getFirst().url()).isEqualTo("https://example.com/doc");
            assertThat(request.stream()).isFalse();
            assertThat(request.hidden()).isTrue();
            assertThat(request.scene()).isEqualTo(new QueryRequest.Scene("https://example.com/app", "demo"));
            assertThat(request.params()).containsEntry("x", 1);
            assertThat(request.params()).containsKey("__schedule");
            @SuppressWarnings("unchecked")
            Map<String, Object> scheduleMeta = (Map<String, Object>) request.params().get("__schedule");
            assertThat(scheduleMeta).containsEntry("scheduleDescription", "每天早上汇总一次");
            String logs = renderedLogs(appender);
            assertThat(logs).contains("Scheduled query started scheduleId=daily, scheduleName=Daily Summary, cron=0 0 9 * * *");
            assertThat(logs).contains("chatId=123e4567-e89b-12d3-a456-426614174000");
            assertThat(logs).contains("Scheduled query completed scheduleId=daily, scheduleName=Daily Summary, cron=0 0 9 * * *");
            assertThat(logs).contains("runId=run_1");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void shouldDispatchViewportWeatherQueryToDemoViewport() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        SchedulePushNotifier pushNotifier = mock(SchedulePushNotifier.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService, pushNotifier, objectMapper);

        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                null,
                new StreamRequest.Query(
                        "req_2",
                        UUID.randomUUID().toString(),
                        "user",
                        "message",
                        "demoViewport",
                        null,
                        null,
                        null,
                        null,
                        false,
                        "chat",
                        "run_2"
                ),
                new AgentRequest("message", UUID.randomUUID().toString(), "req_2", "run_2", Map.of())
        );
        when(agentQueryService.prepare(any(QueryRequest.class))).thenReturn(session);
        when(agentQueryService.stream(any(AgentQueryService.QuerySession.class))).thenReturn(Flux.empty());

        String query = "请从以下城市中随机选择一个：北京、深圳、大连、广州、上海、纽约、巴黎、东京。调用天气工具查询该城市当前天气；如果工具返回了可用的 viewport 结果，请按约定输出 viewport 视图块。";
        ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                "demo_viewport_weather_minutely",
                "分钟天气视图播报",
                "每分钟触发一次天气视图查询",
                true,
                "0 * * * * *",
                "demoViewport",
                null,
                new ScheduledQueryDescriptor.Environment("Asia/Shanghai"),
                new ScheduledQueryDescriptor.Query(null, null, null, query, List.of(), Map.of(), null, null),
                null,
                null,
                "/tmp/demo_viewport_weather_minutely.yml"
        );

        service.dispatch(descriptor);

        ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(agentQueryService).prepare(requestCaptor.capture());
        QueryRequest request = requestCaptor.getValue();
        assertThat(request.agentKey()).isEqualTo("demoViewport");
        assertThat(request.teamId()).isNull();
        assertThat(request.chatId()).isNull();
        assertThat(request.message()).isEqualTo(query);
        assertThat(request.role()).isEqualTo("user");
        assertThat(request.params()).containsOnlyKeys("__schedule");
    }

    @Test
    void shouldLogFailureWithScheduleNameAndCron() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        SchedulePushNotifier pushNotifier = mock(SchedulePushNotifier.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService, pushNotifier, objectMapper);
        ListAppender<ILoggingEvent> appender = attachAppender();

        try {
            when(agentQueryService.prepare(any(QueryRequest.class))).thenThrow(new IllegalStateException("boom"));

            ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                    "demo_viewport_weather_minutely",
                    "分钟天气视图播报",
                    "每分钟触发一次天气视图查询",
                    true,
                    "0 * * * * *",
                    "demoViewport",
                    null,
                    new ScheduledQueryDescriptor.Environment("Asia/Shanghai"),
                    new ScheduledQueryDescriptor.Query(null, "123e4567-e89b-12d3-a456-426614174000", null, "hello", List.of(), Map.of(), null, null),
                    null,
                    null,
                    "/tmp/demo_viewport_weather_minutely.yml"
            );

            service.dispatch(descriptor);

            String logs = renderedLogs(appender);
            assertThat(logs).contains("Scheduled query failed scheduleId=demo_viewport_weather_minutely, scheduleName=分钟天气视图播报, cron=0 * * * * *");
            assertThat(logs).contains("agentKey=demoViewport");
            assertThat(logs).contains("chatId=123e4567-e89b-12d3-a456-426614174000");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void shouldPushContentWhenPushUrlIsConfigured() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        SchedulePushNotifier pushNotifier = mock(SchedulePushNotifier.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService, pushNotifier, objectMapper);

        String chatId = "123e4567-e89b-12d3-a456-426614174000";
        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                null,
                new StreamRequest.Query(
                        "req_push", chatId, "user", "message",
                        "demoModePlain", null, null, null, null, false, "chat", "run_push"
                ),
                new AgentRequest("message", UUID.randomUUID().toString(), "req_push", "run_push", Map.of())
        );
        when(agentQueryService.prepare(any(QueryRequest.class))).thenReturn(session);

        Flux<ServerSentEvent<String>> sseStream = Flux.just(
                ServerSentEvent.<String>builder().data("{\"type\":\"content.delta\",\"delta\":\"Hello \"}").build(),
                ServerSentEvent.<String>builder().data("{\"type\":\"content.delta\",\"delta\":\"World\"}").build(),
                ServerSentEvent.<String>builder().data("{\"type\":\"content.end\"}").build()
        );
        when(agentQueryService.stream(any(AgentQueryService.QuerySession.class))).thenReturn(sseStream);

        ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                "push_test", "Push Test", "test push", true,
                "0 0 9 * * *", "demoModePlain", null,
                new ScheduledQueryDescriptor.Environment(null),
                new ScheduledQueryDescriptor.Query(null, chatId, null, "hello", List.of(), Map.of(), null, null),
                "http://bridge:8080/api/push",
                "990275",
                "/tmp/push_test.yml"
        );

        service.dispatch(descriptor);

        verify(pushNotifier).push(eq("http://bridge:8080/api/push"), eq("990275"), eq("Hello World"));
    }

    @Test
    void shouldNotPushWhenPushUrlIsNull() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        SchedulePushNotifier pushNotifier = mock(SchedulePushNotifier.class);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService, pushNotifier, objectMapper);

        AgentQueryService.QuerySession session = new AgentQueryService.QuerySession(
                null,
                new StreamRequest.Query(
                        "req_no_push", UUID.randomUUID().toString(), "user", "message",
                        "demoModePlain", null, null, null, null, false, "chat", "run_no_push"
                ),
                new AgentRequest("message", UUID.randomUUID().toString(), "req_no_push", "run_no_push", Map.of())
        );
        when(agentQueryService.prepare(any(QueryRequest.class))).thenReturn(session);
        when(agentQueryService.stream(any(AgentQueryService.QuerySession.class))).thenReturn(Flux.just(
                ServerSentEvent.<String>builder().data("{\"type\":\"content.delta\",\"delta\":\"text\"}").build()
        ));

        ScheduledQueryDescriptor descriptor = new ScheduledQueryDescriptor(
                "no_push", "No Push", "no push test", true,
                "0 0 9 * * *", "demoModePlain", null,
                new ScheduledQueryDescriptor.Environment(null),
                new ScheduledQueryDescriptor.Query(null, null, null, "hello", List.of(), Map.of(), null, null),
                null,
                null,
                "/tmp/no_push.yml"
        );

        service.dispatch(descriptor);

        verify(pushNotifier, never()).push(any(), any(), any());
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduledQueryDispatchService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ScheduledQueryDispatchService.class);
        logger.detachAppender(appender);
    }

    private String renderedLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
