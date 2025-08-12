# scheduledwf-refactor

This repo is a simplified, single-module refactor of jas34/scheduledwf.

Features:
- Single Spring Boot app (scheduling REST API + poller)
- Postgres + Flyway migrations
- ShedLock for multi-pod safety
- Cron & one-off schedules
- maxConcurrentRuns enforcement (uses Conductor stub; implement conductor query for production)
- Namespace support

Run:
- Configure Postgres in `src/main/resources/application.yml`
- Build: `mvn -DskipTests package`
- Run: `java -jar target/scheduledwf-refactor-0.1.0.jar`

Next steps:
- Implement Conductor query to count active runs
- Add auth/namespace enforcement
- Add tests and CI
