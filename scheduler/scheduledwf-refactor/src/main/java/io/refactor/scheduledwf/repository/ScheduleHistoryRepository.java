package io.refactor.scheduledwf.repository;

import io.refactor.scheduledwf.model.ScheduleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScheduleHistoryRepository extends JpaRepository<ScheduleHistory, UUID> {
}
