package com.linlay.agentplatform.schedule;

import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.service.AgentQueryService;
import com.linlay.agentplatform.team.TeamDescriptor;
import com.linlay.agentplatform.team.TeamRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ScheduledQueryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryDispatchService.class);

    private final AgentQueryService agentQueryService;
    private final TeamRegistryService teamRegistryService;

    public ScheduledQueryDispatchService(
            AgentQueryService agentQueryService,
            TeamRegistryService teamRegistryService
    ) {
        this.agentQueryService = agentQueryService;
        this.teamRegistryService = teamRegistryService;
    }

    public void dispatch(ScheduledQueryDescriptor descriptor) {
        if (descriptor == null || !descriptor.enabled()) {
            return;
        }

        DispatchTarget target = resolveTarget(descriptor).orElse(null);
        if (target == null) {
            return;
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (descriptor.params() != null && !descriptor.params().isEmpty()) {
            params.putAll(descriptor.params());
        }
        params.put("__schedule", Map.of(
                "scheduleId", descriptor.id(),
                "scheduleName", descriptor.name(),
                "sourceFile", descriptor.sourceFile(),
                "triggeredAt", System.currentTimeMillis()
        ));

        QueryRequest request = new QueryRequest(
                null,
                UUID.randomUUID().toString(),
                target.agentKey(),
                target.teamId(),
                "user",
                descriptor.query(),
                null,
                params,
                null,
                false
        );

        try {
            AgentQueryService.QuerySession session = agentQueryService.prepare(request);
            Flux<ServerSentEvent<String>> stream = agentQueryService.stream(session);
            stream.blockLast();
            log.info(
                    "Scheduled query completed scheduleId={}, runId={}, chatId={}, agentKey={}, teamId={}",
                    descriptor.id(),
                    session.request().runId(),
                    session.request().chatId(),
                    session.request().agentKey(),
                    session.request().teamId()
            );
        } catch (Exception ex) {
            log.warn(
                    "Scheduled query failed scheduleId={}, agentKey={}, teamId={}",
                    descriptor.id(),
                    target.agentKey(),
                    target.teamId(),
                    ex
            );
        }
    }

    private Optional<DispatchTarget> resolveTarget(ScheduledQueryDescriptor descriptor) {
        String teamId = normalizeNullable(descriptor.teamId());
        String agentKey = normalizeNullable(descriptor.agentKey());
        if (StringUtils.hasText(agentKey)) {
            return Optional.of(new DispatchTarget(agentKey, teamId));
        }
        if (!StringUtils.hasText(teamId)) {
            log.warn("Skip scheduled query '{}' due to missing target", descriptor.id());
            return Optional.empty();
        }

        TeamDescriptor team = teamRegistryService.find(teamId).orElse(null);
        if (team == null) {
            log.warn("Skip scheduled query '{}' due to missing teamId={}", descriptor.id(), teamId);
            return Optional.empty();
        }
        String defaultAgentKey = normalizeNullable(team.defaultAgentKey());
        if (!StringUtils.hasText(defaultAgentKey)) {
            log.warn(
                    "Skip scheduled query '{}' because team '{}' has no defaultAgentKey",
                    descriptor.id(),
                    team.id()
            );
            return Optional.empty();
        }
        boolean existsInTeam = team.agentKeys().stream().anyMatch(defaultAgentKey::equals);
        if (!existsInTeam) {
            log.warn(
                    "Skip scheduled query '{}' because team '{}' defaultAgentKey '{}' is not in agentKeys",
                    descriptor.id(),
                    team.id(),
                    defaultAgentKey
            );
            return Optional.empty();
        }
        return Optional.of(new DispatchTarget(defaultAgentKey, team.id()));
    }

    private String normalizeNullable(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim();
    }

    private record DispatchTarget(String agentKey, String teamId) {
    }
}
