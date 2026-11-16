---
name: uts-run
description: Launch the Ultimate Tennis Stats web app locally (port 8081) and refresh its data — ESPN import of new events/rankings, delta or full PostgreSQL load, smoke checks. Use when asked to run/start/test the app, update it with the latest tournaments, or reload the database.
---

# Run & refresh Ultimate Tennis Stats locally

Verified on 2026-11-10 (Intel Mac, macOS 13, bash 3.2). Paths are relative to the repo root.

## Environment facts (don't rediscover)

- **JDK 11** for Gradle comes from `org.gradle.java.home` in `~/.gradle/gradle.properties`; the system `java` is 1.8, so call
  `/usr/local/Cellar/openjdk@11/11.0.8/libexec/openjdk.jdk/Contents/Home/bin/java` explicitly.
- **PostgreSQL 16** (Homebrew `brew services`, keg-only): psql is at `/usr/local/opt/postgresql@16/bin/psql`, not on PATH.
  DB `tcb`, user/pass `tcb`/`tcb`, schema `tcb`.
- **Data** is in `ultimate-tennis-stats-data/` (gitignored): `archive/atp` (Sackmann archive) + `merged/` (what the loader reads).
- **Port 8080 is Jenkins.** The app runs on **8081**.

## 1. Launch the web app

`./gradlew bootRun` does NOT work: it inherits the production JVM args from `tennis-stats/build.gradle`
(profiles `digitalocean,jobs,ssl` → port 443 + scheduled loader jobs, GC log in `/tcb/...`, JMX on 9999).
The default profile fails too (the visitor filter needs a GeoIP DB). Use the install dir + `dev` profile with dev's debug settings switched off:

```bash
./gradlew -q :tennis-stats:installDist
cd tennis-stats && /usr/local/Cellar/openjdk@11/11.0.8/libexec/openjdk.jdk/Contents/Home/bin/java -Xmx512m \
  --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED \
  -cp 'build/install/tennis-stats/lib/*' org.strangeforest.tcb.stats.TennisStatsApplication \
  --server.port=8081 --spring.output.ansi.enabled=never --spring.profiles.active=dev \
  --spring.cache.type=caffeine --spring.thymeleaf.cache=true \
  --logging.level.org.springframework.jdbc.core.JdbcTemplate=INFO \
  --logging.level.org.strangeforest.tcb.stats.service=INFO --tennis-stats.enable-tracing=false
```

Run it in the background, wait for `Started TennisStatsApplication` (~3 s). Restart it after a data load
(Caffeine caches results for 30 min).

## 2. Smoke-check it (drive it, don't just launch)

Pages are thin shells; the data comes from JSON endpoints:

```bash
B=http://localhost:8081
curl -s "$B/rankingsTableTable?rowCount=5"                  # ATP ranking (rows[].rank/name/points)
curl -s "$B/rankingsTableTable?rankType=ELO_RANK&rowCount=5" # Elo
curl -s "$B/goatListTable?rowCount=5"
curl -s "$B/tournamentEventsTable?rowCount=6"               # latest events + winners -> confirms data freshness
```

Pages to eyeball: `/`, `/rankingsTable`, `/goatList`, `/tournamentEvents`, `/eloRatings`, `/records`,
`/playerProfile?name=Jannik+Sinner`. Use a simple `for` loop over paths — this bash refuses arrays and nested `$(...)`.

## 3. Weekly update (new tournaments + rankings)

```bash
python3 tools/espn-import/espn_to_sackmann.py --refresh     # rewrites merged/ (ESPN public API)
caffeinate -i ./gradlew :data-load:run --args='-d -lt -c 4 -t 4'
```

- The ESPN import only takes **completed** events; one still in progress (e.g. a Masters mid-week) appears on the next run after it ends.
  Rankings are a top-150 snapshot for the current Monday — run weekly to build history.
- Watch its `CHECK:` lines; fix unmapped events in `tools/espn-import/tournament_overrides.csv`.
- `-d -lt` = delta: players, `atp_rankings_current.csv`, last two seasons of matches, then Elo, corrections,
  materialized views and records. **Not `-ln`** — that is the old atptour.com scraper path and fails.
- Then restart the web app.

## 3b. Tournament forecasts (in-progress events, run any time during an event)

```bash
python3 tools/espn-import/espn_in_progress.py                 # writes merged/atp_in_progress.json (uncached ESPN)
./gradlew -q :data-load:run --args='-ip -c 2'                 # load draw, Elo, Monte Carlo forecast (~20 s)
```
Then restart the app (forecast results are cached). Only round-robin draws (ATP Finals) are supported:
ESPN gives no knockout bracket positions, so KO events are printed as `skip ...` and not forecast.
`-ip` no longer scrapes atptour.com unless `-Dtcb.data.scrape=true`. Events missing from the JSON are marked
completed. Home page shows the biggest in-progress event's forecast (level G > F > L > M > O > A > B).
Checks: `/inProgressEventsTable?current=1&rowCount=20` (JSON), `/inProgressEventForecast?inProgressEventId=<id>`.

## 4. Full reload from scratch

```bash
caffeinate -i ./gradlew :data-load:run --args='-lt -c 4 -t 4'
```

Players + rankings + matches ≈ 10 min, then Elo ~1 min, views ~1 min, records (slowest). If it dies in a step
after matches, resume instead of restarting: `--args='-el -rc -rr -vc -c 4 -t 4'` (Elo, corrections+views, records, vacuum).

## Gotchas

- **Always wrap loads in `caffeinate -i`.** On battery the Mac idle-sleeps and the load silently stalls (lost 3 h once).
  Closing the lid still sleeps it.
- **Deadlock warnings** during rankings (`load_ranking ... deadlock detected`, `*` in the dot output) are expected — the loader retries.
- **`Invalid set: 6-36-3` / NumberFormatException** during matches = typo in archive data; non-fatal.
- Fatal errors show as `Exception in thread "main"` → grep the log for `Caused by` to find the record.
- Name-based lookups (`player-data.xml`, `rankings-pre-atp.sql`, `tournaments/*.xml`, `tournament-event-surfaces.sql`) break when the
  archive renames a player/event. Fix with an alias row at the end of the VALUES list in
  `data-load/src/main/db/player-aliases-missing-players.sql` (old spelling → archive name; `alias` is a PK, no duplicates).
- New country codes → `OVERRIDES` in `tennis-stats/src/main/java/org/strangeforest/tcb/util/Country.java`.
  New level/entry codes → `mapLevel` / `mapEntry` in `data-load/.../MatchLoader.groovy` (entry must fit the DB enum `Q,WC,LL,PR,SE,AL`).
- Legacy atptour.com/Wikipedia scrapers only run with `-Dtcb.data.scrape=true` (they are blocked; leave off).
- Before a long rerun, check stale JVMs from failed runs: `ps aux | grep '[d]ataload.Main'`.
