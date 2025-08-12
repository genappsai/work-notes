package io.refactor.scheduledwf.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class CreateScheduleRequest {
    @NotBlank
    private String namespace;
    @NotBlank
    private String workflowName;
    private Integer workflowVersion = 1;
    @NotNull
    private String scheduleType; // CRON or ONCE
    private String cronExpression;
    private Instant runAt;
    @Min(1)
    private Integer maxConcurrentRuns = 1;
    private String payload;
    private String createdBy;

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getWorkflowName() { return workflowName; }
    public void setWorkflowName(String workflowName) { this.workflowName = workflowName; }
    public Integer getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(Integer workflowVersion) { this.workflowVersion = workflowVersion; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public Integer getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(Integer maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
