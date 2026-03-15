package com.linlay.agentplatform.schedule;

import com.linlay.agentplatform.model.AgentRequest;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.service.AgentQueryService;
import com.linlay.agentplatform.stream.model.StreamRequest;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledQueryDispatchServiceTest {

    @Test
    void shouldResolveTeamDefaultAgentAndDispatchQuery() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService);

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
                        UUID.randomUUID().toString(),
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
                null,
                null,
                "a1b2c3d4e5f6",
                "hello",
                Map.of("x", 1),
                "/tmp/daily.yml"
        );
        service.dispatch(descriptor);

        ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(agentQueryService).prepare(requestCaptor.capture());
        verify(agentQueryService).stream(any(AgentQueryService.QuerySession.class));

        QueryRequest request = requestCaptor.getValue();
        assertThat(request.agentKey()).isEqualTo("demoModeReact");
        assertThat(request.teamId()).isEqualTo("a1b2c3d4e5f6");
        assertThat(request.message()).isEqualTo("hello");
        assertThat(request.stream()).isFalse();
        assertThat(request.params()).containsEntry("x", 1);
        assertThat(request.params()).containsKey("__schedule");
        @SuppressWarnings("unchecked")
        Map<String, Object> scheduleMeta = (Map<String, Object>) request.params().get("__schedule");
        assertThat(scheduleMeta).containsEntry("scheduleDescription", "每天早上汇总一次");
    }

    @Test
    void shouldDispatchViewportWeatherQueryToDemoViewport() {
        AgentQueryService agentQueryService = mock(AgentQueryService.class);
        TeamRegistryService teamRegistryService = mock(TeamRegistryService.class);
        ScheduledQueryDispatchService service = new ScheduledQueryDispatchService(agentQueryService, teamRegistryService);

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
                "Asia/Shanghai",
                "demoViewport",
                null,
                query,
                Map.of("source", "built-in"),
                "/tmp/demo_viewport_weather_minutely.yml"
        );

        service.dispatch(descriptor);

        ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(agentQueryService).prepare(requestCaptor.capture());
        QueryRequest request = requestCaptor.getValue();
        assertThat(request.agentKey()).isEqualTo("demoViewport");
        assertThat(request.teamId()).isNull();
        assertThat(request.message()).isEqualTo(query);
        assertThat(request.params()).containsEntry("source", "built-in");
        assertThat(request.params()).containsKey("__schedule");
    }
}
