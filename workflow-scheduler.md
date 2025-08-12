[200~Nice — let’s design a production-ready, multi-pod safe scheduler that supports both of your use cases:

1. **Per-workflow cron schedules** (periodic recurring runs defined at workflow creation)
2. **One-off future schedules** (run a workflow at a specific time once)

I'll give: the data model, REST API surface, control flow, DB schemas, a robust Spring Boot implementation outline using **ShedLock** + Postgres (so multiple pods are safe), key code snippets (controller, service, poller), and production notes (timezone, idempotency, retries, metrics). You can copy/paste the code into your project and I can expand it into a full repo if you want.

# Overview (how it works)

* When a user creates a scheduled trigger for a workflow we persist a **Schedule** row (cron or one-off) in Postgres.
* A small scheduled **poller** (runs every N seconds) obtains a ShedLock (DB-backed lock) so **only one pod** processes due schedules at a time.
* The poller selects due schedules (next\_run <= now, status = ACTIVE), triggers the Conductor Start Workflow API (with retries/backoff), then:

  * For **cron** schedules: compute and update `next_run` based on cron expression.
  * For **one-off** schedules: mark schedule as COMPLETED (or keep history).
* We record execution attempts in a **schedule\_history** table for visibility and retries/alerts.

# DB Schema (Postgres)

```sql
CREATE TABLE schedule (
  id              uuid PRIMARY KEY,
  name            varchar(255) NOT NULL,
  workflow_name   varchar(255) NOT NULL,
  workflow_version int DEFAULT 1,
  type            varchar(10) NOT NULL, -- 'CRON' or 'ONCE'
  cron_expr       varchar(255),          -- for CRON
  next_run        timestamptz NOT NULL,
  enabled         boolean NOT NULL DEFAULT true,
  payload         jsonb,                 -- input for the workflow
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE schedule_history (
  id            uuid PRIMARY KEY,
  schedule_id   uuid REFERENCES schedule(id),
  run_at        timestamptz NOT NULL,
  status        varchar(20) NOT NULL, -- SUCCESS / FAILED / SKIPPED
  response      jsonb,
  error_message text,
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- ShedLock table required by ShedLock (if not using migration scripts)
CREATE TABLE shedlock(
  name VARCHAR(64) NOT NULL PRIMARY KEY,
  lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
  locked_at TIMESTAMP WITH TIME ZONE NOT NULL,
  locked_by VARCHAR(255) NOT NULL
);
```

# REST API (minimal)

* `POST /schedules` — create a schedule
  Body example (cron):

  ```json
  {
    "name":"daily-run",
    "type":"CRON",
    "cronExpr":"0 0 9 * * ?",
    "workflowName":"my_workflow",
    "workflowVersion":1,
    "payload": {"foo":"bar"},
    "startAt": "2025-08-13T09:00:00Z"  // optional initial next_run override
  }
  ```

  One-off:

  ```json
  {
    "name":"run-once",
    "type":"ONCE",
    "runAt":"2025-08-14T15:30:00Z",
    "workflowName":"my_workflow",
    "payload": {}
  }
  ```
* `GET /schedules` — list schedules
* `PUT /schedules/{id}/pause` and `/resume`
* `DELETE /schedules/{id}` — remove
* `GET /schedules/{id}/history` — executions

# Key Implementation Details (Spring Boot)

Dependencies (pom.xml):

* Spring Boot Web, Data JPA, JDBC, PostgreSQL driver
* `net.javacrumbs.shedlock:shedlock-spring` and `shedlock-provider-jdbc-template`
* (Optional) `spring-boot-starter-actuator` for health/metrics

Important classes / flow snippets below.

---

## 1) Entity (Schedule)

```java
// lombok annotations omitted for clarity
@Entity
@Table(name="schedule")
public class Schedule {
    @Id
    private UUID id;
    private String name;
    private String workflowName;
    private Integer workflowVersion;
    private String type; // "CRON" or "ONCE"
    private String cronExpr;
    private Instant nextRun;
    private boolean enabled;
    @Column(columnDefinition = "jsonb")
    private String payloadJson; // store as JSON string
    private Instant createdAt;
    private Instant updatedAt;
    // getters/setters...
}
```

## 2) Repository (Spring Data JPA)

```java
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    @Query("select s from Schedule s where s.enabled = true and s.nextRun <= :now")
    List<Schedule> findDueSchedules(@Param("now") Instant now, Pageable pageable);
}
```

(Use paging to limit batch size.)

## 3) Poller (ShedLock protected)

* This is the critical piece that runs only on one pod at a time.

```java
@Service
public class SchedulePoller {
    private final ScheduleRepository scheduleRepo;
    private final ConductorClient conductorClient; // wrapper for calling Conductor
    private final ScheduleHistoryRepository historyRepo;
    private final int pageSize = 50;

    public SchedulePoller(...){...}

    // Poll every 30 seconds (or configurable)
    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:30000}")
    @SchedulerLock(name = "schedule-poller", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void pollAndTrigger() {
        Instant now = Instant.now();
        Pageable page = PageRequest.of(0, pageSize);
        List<Schedule> due;
        do {
            due = scheduleRepo.findDueSchedules(now, page);
            for (Schedule s : due) {
                processSchedule(s);
            }
            // fetch next page if needed (page++)
        } while (!due.isEmpty());
    }

    private void processSchedule(Schedule s) {
        Instant runAt = s.getNextRun();
        // Optional: double-check and mark an in-memory flag or DB to avoid races
        try {
            // Call Conductor
            ConductorResponse resp = conductorClient.startWorkflow(
                 s.getWorkflowName(), s.getWorkflowVersion(), s.getPayloadJson()
            );
            // record success
            historyRepo.save(...);

            if ("CRON".equals(s.getType())) {
                Instant next = computeNextRun(s.getCronExpr(), runAt);
                s.setNextRun(next);
                s.setUpdatedAt(Instant.now());
                scheduleRepo.save(s);
            } else { // ONCE
                // mark disabled or delete
                s.setEnabled(false);
                scheduleRepo.save(s);
            }
        } catch (Exception ex) {
            // record failure, consider retry policy: leave next_run + add backoff?
            historyRepo.save(...);
            // optionally update next_run to now + retryDelay or leave it for next poll
        }
    }

    private Instant computeNextRun(String cronExpr, Instant after) {
        // Use org.springframework.scheduling.support.CronExpression
        CronExpression cron = CronExpression.parse(cronExpr);
        ZonedDateTime afterZ = ZonedDateTime.ofInstant(after, ZoneOffset.UTC);
        Optional<ZonedDateTime> next = cron.next(afterZ);
        return next.map(ZonedDateTime::toInstant)
                   .orElseThrow(() -> new IllegalStateException("No next run for cron: "+cronExpr));
    }
}
```

Notes:

* `SchedulerLock` annotation ensures only one instance executes `pollAndTrigger`.
* Use `Pageable` and batches to avoid reading too many rows at once.
* For failures you can either (a) leave `next_run` unchanged so poller retries next cycle, or (b) update `next_run` with a retry window and failed attempt counter. Choose based on desired retry semantics.

## 4) Conductor client wrapper (network + retries)

* Wrap Conductor Start Workflow HTTP call and implement exponential backoff and circuit-breaker (optional).
* Use Spring `RestTemplate` or `WebClient`. Return response info for `schedule_history`.

```java
public class ConductorClient {
    private final RestTemplate rest;
    private final String conductorBase;

    public ConductorClient(String conductorBase){ ... }

    public ConductorResponse startWorkflow(String name, int version, String payloadJson) {
        String url = conductorBase + "/workflow"; // or /api/workflow depending on version
        Map<String,Object> body = Map.of(
            "name", name,
            "version", version,
            "input", objectMapper.readValue(payloadJson, Map.class)
        );
        // Add retry loop with backoff here
        ResponseEntity<String> resp = rest.postForEntity(url, body, String.class);
        // parse id etc and return
    }
}
```

# Handling the Two Use Cases — specifics

### Use case 1 — Create cron schedule at workflow creation

* API `POST /schedules` with `type=CRON` and `cronExpr`.
* When inserting, compute initial `next_run`:

  * If `startAt` provided and `startAt > now` set `next_run = startAt`.
  * Otherwise compute next using `CronExpression.next(now)` and set `next_run`.
* Poller will pick it up when `next_run <= now`.

### Use case 2 — Schedule one-time future run

* API `POST /schedules` with `type=ONCE` and `runAt` (ISO timestamp).
* Validate `runAt > now`, store `next_run = runAt`.
* Poller triggers when time arrives, then marks schedule `enabled=false` (or move to completed state/history).

# Concurrency, correctness, and failure modes

* **Single trigger guarantee**: achieved by shedlock + selecting due rows and performing trigger inside same lock window. There is still a tiny race if two pollers read same rows before lock; the ShedLock ensures only one poller runs, so safe.
* **DB transaction**: optionally wrap `processSchedule` in a DB transaction using SELECT FOR UPDATE to ensure the record isn't processed twice in abnormal cases (e.g., separate script). But with the single poller lock that's less necessary.
* **Idempotency**: workflows should be idempotent or include dedupe keys (like schedule id + runAt) to avoid duplicate effects if Conductor reexecutes.
* **Clock & timezone**: store `next_run` in UTC (timestamptz). Accept schedule creation timestamps with timezone; normalize to UTC. When computing cron next, pick timezone—cron expressions are ambiguous; allow optional `timezone` field per schedule. Use `CronExpression` with a `ZoneId`.
* **DST**: be explicit about timezone on cron schedules to avoid surprises.
* **Retries**: for transient Conductor failures, you can implement retry policy in poller or let schedule history and alerts handle manual retry. For cron schedules, do not advance `next_run` on failure (or compute next and keep a failed history entry), depending on desired semantics.

# Operational & K8s notes

* Keep poller frequency reasonable (30s–60s). Cron schedules with second-level precision may require different tooling (Quartz).
* `lockAtMostFor` must be > maximum expected time to process batch to avoid overlapping runs.
* Run at least two replicas and rely on ShedLock to coordinate.
* Add metrics: `schedules_processed_total`, `schedules_failed_total`, `lock_acquired` etc.
* Put `shedlock` table migrations in Flyway/Liquibase.

# Example: Create Cron & One-off Requests

Cron create example:

```bash
curl -X POST -H "Content-Type: application/json" http://scheduler:8080/schedules -d '{
  "name":"daily-sync",
  "type":"CRON",
  "cronExpr":"0 0 9 * * ?",
  "workflowName":"syncWorkflow",
  "payload":{"env":"prod"}
}'
```

One-off create:

```bash
curl -X POST -H "Content-Type: application/json" http://scheduler:8080/schedules -d '{
  "name":"run-report-aug20",
  "type":"ONCE",
  "runAt":"2025-08-20T15:00:00Z",
  "workflowName":"reportWorkflow",
  "payload":{}
}'
```

# Extra enhancements you may want

* UI for listing schedules, pausing/resuming, last-run status.
* Cron expression validation on creation (use `CronExpression.parse()`).
* Allow `maxConcurrentRuns` per schedule (avoid overlap when earlier run still executing).
* Support for schedule ownership/namespace (multitenancy).
* Use `Quartz` if you require fine-grained scheduling features (misfire handling, clustered scheduling with more features) — Quartz can also be run with JDBCJobStore and cluster mode instead of ShedLock + poller.

---

If you want, I can now:

* Generate the **full Spring Boot project skeleton** with the classes above, DTOs, controllers, Flyway migration SQLs for `schedule` and `shedlock`, and a ready Dockerfile and Kubernetes manifests; **or**
* Implement a single file copy-pasteable example focusing on the Poller + Schedule creation endpoints; **or**
* Convert the poller to **Quartz JDBC clustered** approach instead (if you prefer Quartz features).

Which one should I build next?


Got it — you basically want to turn the simple ShedLock scheduler into a **multi-tenant, self-service workflow scheduling service** that supports:

1. **Cron scheduling** (recurring)
2. **One-time scheduling** (future date/time)
3. **Validation** of cron expressions on creation
4. **maxConcurrentRuns** enforcement (prevent overlapping runs for the same schedule)
5. **Namespace / ownership** (so tenants don’t collide on schedules)
6. **Still works across multiple pods** (distributed lock)

Here’s a clean redesign plan + implementation outline.

---

## **1. Data Model**

We’ll store schedules in a DB table.
This is the **single source of truth** for all pods.

```sql
CREATE TABLE workflow_schedules (
    id UUID PRIMARY KEY,
    namespace VARCHAR(100) NOT NULL,
    workflow_name VARCHAR(200) NOT NULL,
    workflow_version INT NOT NULL,
    schedule_type VARCHAR(20) NOT NULL, -- CRON or ONCE
    cron_expression VARCHAR(100),       -- only if type=CRON
    run_at TIMESTAMP WITH TIME ZONE,    -- only if type=ONCE
    max_concurrent_runs INT DEFAULT 1,
    last_triggered_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, PAUSED, DISABLED
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
```

---

## **2. Workflow Scheduling API**

### **Create Schedule**

* Validates cron expressions if `type=CRON` using `CronExpression.parse(cron)`.
* Validates `run_at` if `type=ONCE` is in the future.
* Checks `maxConcurrentRuns` ≥ 1.
* Assigns namespace from the authenticated user’s tenant.

Example DTO:

```java
public record CreateScheduleRequest(
    String namespace,
    String workflowName,
    int workflowVersion,
    ScheduleType scheduleType,
    String cronExpression, // nullable
    Instant runAt,         // nullable
    int maxConcurrentRuns
) {}
```

---

## **3. Scheduler Execution Logic**

We’ll have **one** scheduler thread in the cluster (thanks to ShedLock) scanning the DB every minute for:

* **CRON schedules** due now (based on `last_triggered_at` + cron next execution time).
* **ONCE schedules** with `run_at <= now()` and not yet executed.

For **maxConcurrentRuns**:

* Before triggering, check how many active runs exist for `(namespace, workflow_name)`.
* If that count ≥ maxConcurrentRuns, skip until next interval.

---

## **4. Code Outline**

### **Cron validation on creation**

```java
import org.springframework.scheduling.support.CronExpression;

public void validateSchedule(CreateScheduleRequest req) {
    if (req.scheduleType() == ScheduleType.CRON) {
        try {
            CronExpression.parse(req.cronExpression());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cron expression: " + req.cronExpression());
        }
    } else if (req.scheduleType() == ScheduleType.ONCE) {
        if (req.runAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("runAt must be in the future");
        }
    }
    if (req.maxConcurrentRuns() < 1) {
        throw new IllegalArgumentException("maxConcurrentRuns must be >= 1");
    }
}
```

---

### **Scheduled job with ShedLock**

```java
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

@Service
public class DistributedScheduleExecutor {

    private final ScheduleRepository repo;
    private final ConductorClient conductor;

    public DistributedScheduleExecutor(ScheduleRepository repo, ConductorClient conductor) {
        this.repo = repo;
        this.conductor = conductor;
    }

    @Scheduled(fixedDelay = 60000) // every 60s
    @SchedulerLock(name = "schedule-executor", lockAtLeastFor = "PT50S", lockAtMostFor = "PT1M")
    public void executeDueSchedules() {
        Instant now = Instant.now();

        List<WorkflowSchedule> due = repo.findDueSchedules(now);
        for (WorkflowSchedule sched : due) {
            if (canTrigger(sched)) {
                conductor.startWorkflow(sched);
                repo.updateLastTriggered(sched.getId(), now);
            }
        }
    }

    private boolean canTrigger(WorkflowSchedule sched) {
        // Check maxConcurrentRuns
        int activeRuns = conductor.countActiveRuns(sched.getNamespace(), sched.getWorkflowName());
        return activeRuns < sched.getMaxConcurrentRuns();
    }
}
```

---

### **Repository Query for Due Schedules**

```sql
-- For CRON schedules:
SELECT * FROM workflow_schedules
WHERE status = 'ACTIVE'
  AND schedule_type = 'CRON'
  AND next_run_time <= now();

-- For ONCE schedules:
SELECT * FROM workflow_schedules
WHERE status = 'ACTIVE'
  AND schedule_type = 'ONCE'
  AND run_at <= now()
  AND last_triggered_at IS NULL;
```

(`next_run_time` can be precomputed on each trigger, or calculated in Java via `CronExpression.next()`.)

---

### **Multi-tenancy / Namespace Isolation**

* All queries include `WHERE namespace = :namespace` unless you’re an admin.
* Lock key in ShedLock can include namespace if you want per-namespace scheduling (`name = "schedule-executor-" + namespace`), but for most cases one cluster-wide scheduler is fine.

---

## **5. Benefits**

* **Distributed safe** — ShedLock prevents duplicate triggers across pods.
* **Multi-tenant** — Namespace ensures isolation.
* **No overlaps** — `maxConcurrentRuns` prevents concurrency overflow.
* **Self-service** — New schedules can be added via API, without restarting service.
* **Future-proof** — Can later replace DB with Redis, DynamoDB, etc.

---

If you want, I can turn this into:

* A **full Spring Boot service** with REST APIs (`/schedules`)
* A **flyway migration** for the table
* A working **K8s manifest** with multiple pods
* An example **Postman collection** for creating/testing schedules

Do you want me to go ahead and produce that complete runnable service? That way both cron and one-time scheduling will be ready to deploy.

