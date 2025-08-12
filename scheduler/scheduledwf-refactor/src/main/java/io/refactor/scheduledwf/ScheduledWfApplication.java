package io.refactor.scheduledwf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ScheduledWfApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScheduledWfApplication.class, args);
    }
}
