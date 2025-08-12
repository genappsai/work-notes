package io.refactor.scheduledwf.repository;

import io.refactor.scheduledwf.model.Schedule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    @Query("SELECT s FROM Schedule s WHERE s.status = 'ACTIVE' AND s.nextRun <= :now ORDER BY s.nextRun ASC")
    List<Schedule> findDueSchedules(@Param("now") Instant now, Pageable pageable);
}
