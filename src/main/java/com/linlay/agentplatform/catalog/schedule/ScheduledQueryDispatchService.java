package com.linlay.agentplatform.catalog.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.engine.query.AgentQueryService;
import com.linlay.agentplatform.catalog.team.TeamDescriptor;
import com.linlay.agentplatform.catalog.team.TeamRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ScheduledQueryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledQueryDispatchService.class);

    private final AgentQueryService agentQueryService;
    private final TeamRegistryService teamRegistryService;
    private final SchedulePushNotifier pushNotifier;
    private final ObjectMapper objectMapper;

    public ScheduledQueryDispatchService(
            AgentQueryService agentQueryService,
            TeamRegistryService teamRegistryService,
            SchedulePushNotifier pushNotifier,
            ObjectMapper objectMapper
    ) {
        this.agentQueryService = agentQueryService;
        this.teamRegistryService = teamRegistryService;
        this.pushNotifier = pushNotifier;
        this.objectMapper = objectMapper;
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
        if (descriptor.query() != null && descriptor.query().params() != null && !descriptor.query().params().isEmpty()) {
            params.putAll(descriptor.query().params());
        }
        params.put("__schedule", Map.of(
                "scheduleId", descriptor.id(),
                "scheduleName", descriptor.name(),
                "scheduleDescription", descriptor.description(),
                "sourceFile", descriptor.sourceFile(),
                "triggeredAt", System.currentTimeMillis()
        ));

        QueryRequest request = new QueryRequest(
                descriptor.query() == null ? null : descriptor.query().requestId(),
                descriptor.query() == null ? null : descriptor.query().chatId(),
                target.agentKey(),
                target.teamId(),
                normalizeRole(descriptor.query() == null ? null : descriptor.query().role()),
                descriptor.query() == null ? null : descriptor.query().message(),
                descriptor.query() == null || descriptor.query().references().isEmpty() ? null : descriptor.query().references(),
                params,
                descriptor.query() == null ? null : descriptor.query().scene(),
                false,
                descriptor.query() == null ? null : descriptor.query().hidden()
        );

        try {
            AgentQueryService.QuerySession session = agentQueryService.prepare(request);
            log.info(
                    "Scheduled query started scheduleId={}, scheduleName={}, cron={}, agentKey={}, teamId={}, chatId={}",
                    descriptor.id(),
                    descriptor.name(),
                    descriptor.cron(),
                    session.request().agentKey(),
                    session.request().teamId(),
                    session.request().chatId()
            );
            Flux<ServerSentEvent<String>> stream = agentQueryService.stream(session);
            String pushUrl = descriptor.pushUrl();
            boolean shouldPush = StringUtils.hasText(pushUrl);
            StringBuilder contentCollector = shouldPush ? new StringBuilder() : null;
            stream.doOnNext(event -> {
                if (shouldPush && event.data() != null) {
                    try {
                        JsonNode node = objectMapper.readTree(event.data());
                        String type = node.path("type").asText("");
                        if ("content.delta".equals(type)) {
                            String delta = node.path("delta").asText("");
                            contentCollector.append(delta);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }).blockLast();
            log.info(
                    "Scheduled query completed scheduleId={}, scheduleName={}, cron={}, runId={}, chatId={}, agentKey={}, teamId={}",
                    descriptor.id(),
                    descriptor.name(),
                    descriptor.cron(),
                    session.request().runId(),
                    session.request().chatId(),
                    session.request().agentKey(),
                    session.request().teamId()
            );
            if (shouldPush && !contentCollector.isEmpty()) {
                String pushTargetId = StringUtils.hasText(descriptor.pushTargetId())
                        ? descriptor.pushTargetId()
                        : session.request().chatId();
                pushNotifier.push(pushUrl, pushTargetId, contentCollector.toString());
            }
        } catch (Exception ex) {
            log.warn(
                    "Scheduled query failed scheduleId={}, scheduleName={}, cron={}, agentKey={}, teamId={}, chatId={}",
                    descriptor.id(),
                    descriptor.name(),
                    descriptor.cron(),
                    target.agentKey(),
                    target.teamId(),
                    request.chatId(),
                    ex
            );
        }
    }

    private Optional<DispatchTarget> resolveTarget(ScheduledQueryDescriptor descriptor) {
        String teamId = normalizeNullable(descriptor.teamId());
        String agentKey = normalizeNullable(descriptor.agentKey());
        if (StringUtils.hasText(agentKey)) {
            if (!StringUtils.hasText(teamId)) {
                return Optional.of(new DispatchTarget(agentKey, null));
            }
            TeamDescriptor team = teamRegistryService.find(teamId).orElse(null);
            if (team == null) {
                log.warn("Skip scheduled query '{}' due to missing teamId={}", descriptor.id(), teamId);
                return Optional.empty();
            }
            boolean existsInTeam = team.agentKeys().stream().anyMatch(agentKey::equals);
            if (!existsInTeam) {
                log.warn(
                        "Skip scheduled query '{}' because agentKey '{}' is not in team '{}'",
                        descriptor.id(),
                        agentKey,
                        team.id()
                );
                return Optional.empty();
            }
            return Optional.of(new DispatchTarget(agentKey, teamId));
        }
        log.warn("Skip scheduled query '{}' due to missing agentKey", descriptor.id());
        return Optional.empty();
    }

    private String normalizeNullable(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim();
    }

    private String normalizeRole(String role) {
        return StringUtils.hasText(role) ? role.trim() : "user";
    }

    private record DispatchTarget(String agentKey, String teamId) {
    }
}
