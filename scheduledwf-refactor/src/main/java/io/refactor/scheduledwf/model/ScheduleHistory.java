package io.refactor.scheduledwf.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "schedule_history")
public class ScheduleHistory {
    @Id
    private UUID id;
    @Column(name = "schedule_id")
    private UUID scheduleId;
    @Column(name = "run_at")
    private Instant runAt;
    private String status;
    @Column(columnDefinition = "jsonb")
    private String response;
    @Column(name = "error_message")
    private String errorMessage;
    @Column(name = "created_at")
    private Instant createdAt;

    public ScheduleHistory() {}

    // getters/setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getScheduleId() { return scheduleId; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
