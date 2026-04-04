package com.linlay.agentplatform.tool;

import com.linlay.agentplatform.agent.runtime.ExecutionContext;
import com.linlay.agentplatform.model.AgentDelta;
import com.linlay.agentplatform.model.ArtifactEventPayload;
import com.linlay.agentplatform.model.api.QueryRequest;
import com.linlay.agentplatform.service.chat.ArtifactPublishService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishArtifactToolTest {

    @Test
    void shouldPublishParsedArtifacts() {
        ArtifactPublishService service = mock(ArtifactPublishService.class);
        PublishArtifactTool tool = new PublishArtifactTool(service);
        ExecutionContext context = mock(ExecutionContext.class);
        doNothing().when(context).deferToolDelta(any(AgentDelta.class));

        QueryRequest.Reference reference = new QueryRequest.Reference(
                "artifact_1",
                "file",
                "report.txt",
                "text/plain",
                12L,
                "/api/resource?file=chat/artifacts/run/report.txt",
                "sha",
                null,
                Map.of()
        );
        ArtifactPublishService.Publication publication = new ArtifactPublishService.Publication(
                "artifact_1",
                "chat_1",
                "run_1",
                reference,
                new ArtifactEventPayload("file", "report.txt", "text/plain", 12L, reference.url(), "sha")
        );
        when(service.publish(any(), eq(context))).thenReturn(List.of(publication));

        String result = tool.invoke(Map.of(
                "artifacts", List.of(Map.of(
                        "path", "/tmp/report.txt",
                        "name", "Report",
                        "description", " summary "
                ))
        ), context).toString();

        assertThat(result).contains("\"ok\":true").contains("\"artifactId\":\"artifact_1\"");
        verify(service).publish(List.of(new ArtifactPublishService.ArtifactRequest(
                "/tmp/report.txt",
                "Report",
                "summary"
        )), context);
        verify(context).deferToolDelta(any(AgentDelta.class));
    }

    @Test
    void shouldRejectMissingArtifactPath() {
        PublishArtifactTool tool = new PublishArtifactTool(mock(ArtifactPublishService.class));

        assertThatThrownBy(() -> tool.invoke(Map.of(
                "artifacts", List.of(Map.of("name", "Report"))
        ), mock(ExecutionContext.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing argument: artifacts[0].path");
    }

    @Test
    void shouldRejectNonObjectArtifactEntry() {
        PublishArtifactTool tool = new PublishArtifactTool(mock(ArtifactPublishService.class));

        assertThatThrownBy(() -> tool.invoke(Map.of(
                "artifacts", List.of("invalid")
        ), mock(ExecutionContext.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("artifacts[0] must be an object");
    }
}
