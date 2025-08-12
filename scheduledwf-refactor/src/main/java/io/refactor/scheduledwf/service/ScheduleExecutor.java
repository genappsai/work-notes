package io.refactor.scheduledwf.service;

import io.refactor.scheduledwf.model.Schedule;
import io.refactor.scheduledwf.model.ScheduleHistory;
import io.refactor.scheduledwf.repository.ScheduleHistoryRepository;
import io.refactor.scheduledwf.repository.ScheduleRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScheduleExecutor {
    private final ScheduleRepository repo;
    private final ScheduleHistoryRepository historyRepo;
    private final ConductorClient conductor;

    public ScheduleExecutor(ScheduleRepository repo, ScheduleHistoryRepository historyRepo, ConductorClient conductor) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.conductor = conductor;
    }

    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:30000}")
    @SchedulerLock(name = "schedule-executor", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void pollAndExecute() {
        Instant now = Instant.now();
        int page = 0;
        int size = Integer.parseInt(System.getProperty("scheduler.page-size", "50"));
        List<Schedule> due;
        do {
            due = repo.findDueSchedules(now, PageRequest.of(page, size));
            for (Schedule s : due) {
                try {
                    if (!canTrigger(s)) {
                        continue;
                    }
                    String resp = conductor.startWorkflow(s.getWorkflowName(), s.getWorkflowVersion(), Map.of("scheduledBy", s.getId().toString()));
                    ScheduleHistory h = new ScheduleHistory();
                    h.setId(UUID.randomUUID());
                    h.setScheduleId(s.getId());
                    h.setRunAt(now);
                    h.setStatus("SUCCESS");
                    h.setResponse(resp);
                    h.setCreatedAt(Instant.now());
                    historyRepo.save(h);
                    if ("CRON".equalsIgnoreCase(s.getScheduleType())) {
                        var cron = org.springframework.scheduling.support.CronExpression.parse(s.getCronExpression());
                        var next = cron.next(java.time.ZonedDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
                        s.setNextRun(next.toInstant());
                        s.setLastTriggeredAt(now);
                        repo.save(s);
                    } else {
                        s.setStatus("DISABLED");
                        s.setLastTriggeredAt(now);
                        repo.save(s);
                    }
                } catch (Exception ex) {
                    ScheduleHistory h = new ScheduleHistory();
                    h.setId(UUID.randomUUID());
                    h.setScheduleId(s.getId());
                    h.setRunAt(now);
                    h.setStatus("FAILED");
                    h.setErrorMessage(ex.getMessage());
                    h.setCreatedAt(Instant.now());
                    historyRepo.save(h);
                }
            }
            page++;
        } while (!due.isEmpty());
    }

    private boolean canTrigger(Schedule s) {
        int active = conductor.countActiveRuns(s.getNamespace(), s.getWorkflowName());
        return active < (s.getMaxConcurrentRuns() == null ? 1 : s.getMaxConcurrentRuns());
    }
}
