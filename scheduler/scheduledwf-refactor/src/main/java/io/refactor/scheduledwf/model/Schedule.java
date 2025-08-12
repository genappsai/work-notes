package io.refactor.scheduledwf.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_schedules")
public class Schedule {
    @Id
    private UUID id;
    private String namespace;
    private String name;
    @Column(name = "workflow_name")
    private String workflowName;
    @Column(name = "workflow_version")
    private Integer workflowVersion;
    @Column(name = "schedule_type")
    private String scheduleType;
    @Column(name = "cron_expression")
    private String cronExpression;
    @Column(name = "run_at")
    private Instant runAt;
    @Column(name = "next_run")
    private Instant nextRun;
    @Column(name = "max_concurrent_runs")
    private Integer maxConcurrentRuns;
    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;
    private String status;
    @Column(columnDefinition = "jsonb")
    private String payload;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public Schedule() {}

    // getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
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
    public Instant getNextRun() { return nextRun; }
    public void setNextRun(Instant nextRun) { this.nextRun = nextRun; }
    public Integer getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(Integer maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
    public Instant getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(Instant lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
