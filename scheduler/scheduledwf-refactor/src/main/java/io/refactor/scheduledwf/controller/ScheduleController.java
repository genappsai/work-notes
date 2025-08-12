package io.refactor.scheduledwf.controller;

import io.refactor.scheduledwf.dto.CreateScheduleRequest;
import io.refactor.scheduledwf.model.Schedule;
import io.refactor.scheduledwf.repository.ScheduleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleRepository repo;

    public ScheduleController(ScheduleRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public ResponseEntity<?> createSchedule(@RequestBody @Valid CreateScheduleRequest req) {
        Schedule s = new Schedule();
        s.setId(UUID.randomUUID());
        s.setNamespace(req.getNamespace());
        s.setName(req.getNamespace() + ":" + req.getWorkflowName());
        s.setWorkflowName(req.getWorkflowName());
        s.setWorkflowVersion(req.getWorkflowVersion());
        s.setScheduleType(req.getScheduleType());
        s.setCronExpression(req.getCronExpression());
        s.setRunAt(req.getRunAt());
        s.setMaxConcurrentRuns(req.getMaxConcurrentRuns());
        s.setStatus("ACTIVE");
        s.setPayload(req.getPayload());
        s.setCreatedBy(req.getCreatedBy() == null ? "system" : req.getCreatedBy());
        s.setCreatedAt(Instant.now());

        // compute initial nextRun
        s.setNextRun(computeInitialNextRun(s));
        repo.save(s);
        return ResponseEntity.ok(s);
    }

    private Instant computeInitialNextRun(Schedule s) {
        Instant now = Instant.now();
        if ("CRON".equalsIgnoreCase(s.getScheduleType())) {
            if (s.getCronExpression() == null) throw new IllegalArgumentException("cronExpression required");
            try {
                var cron = org.springframework.scheduling.support.CronExpression.parse(s.getCronExpression());
                var next = cron.next(java.time.ZonedDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
                return next.toInstant();
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid cron expression: " + e.getMessage());
            }
        } else {
            if (s.getRunAt() == null) throw new IllegalArgumentException("runAt required for ONCE");
            if (s.getRunAt().isBefore(now)) throw new IllegalArgumentException("runAt must be in the future");
            return s.getRunAt();
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        var p = repo.findAll(PageRequest.of(page, size));
        return ResponseEntity.ok(p);
    }
}
