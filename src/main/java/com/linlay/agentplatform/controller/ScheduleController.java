package com.linlay.agentplatform.controller;

import com.linlay.agentplatform.model.api.ApiResponse;
import com.linlay.agentplatform.catalog.schedule.ScheduledQueryOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScheduleController {

    private final ScheduledQueryOrchestrator orchestrator;

    public ScheduleController(ScheduledQueryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/schedules/refresh")
    public ApiResponse<String> refresh() {
        orchestrator.refreshAndReconcile();
        return ApiResponse.success("schedules refreshed");
    }
}
