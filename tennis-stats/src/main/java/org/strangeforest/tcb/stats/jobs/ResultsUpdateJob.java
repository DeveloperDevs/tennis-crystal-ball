package org.strangeforest.tcb.stats.jobs;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.annotation.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;
import org.strangeforest.tcb.stats.service.*;

import com.fasterxml.jackson.databind.*;

/**
 * Pulls the latest results from ESPN (tools/espn-import/update-results.sh), loads the recent completed matches into
 * bets.espn_result, settles My Bets and refreshes in-progress event forecasts. Runs on a schedule
 * (tennis-stats.results-update.cron, off unless set) and on demand (My Bets Refresh button); one run at a time.
 */
@Component
public class ResultsUpdateJob {

	private static final Logger LOGGER = LoggerFactory.getLogger(ResultsUpdateJob.class);

	@Autowired private NamedParameterJdbcTemplate jdbcTemplate;
	@Autowired private MyBetsService myBetsService;
	@Autowired private DataService dataService;

	@Value("${tennis-stats.results-update.command:../tools/espn-import/update-results.sh}")
	private String command;

	@Value("${tennis-stats.results-update.file:../ultimate-tennis-stats-data/merged/atp_recent_results.json}")
	private String resultsFile;

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		var thread = new Thread(r, "ResultsUpdate");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Status lastStatus;
	private volatile ZonedDateTime runningSince;

	private static final String CREATE_TABLE = //language=SQL
		"CREATE TABLE IF NOT EXISTS bets.espn_result (\n" +
		"  espn_id TEXT PRIMARY KEY,\n" +
		"  date DATE NOT NULL,\n" +
		"  tournament TEXT,\n" +
		"  round TEXT,\n" +
		"  winner_id INTEGER,\n" +
		"  loser_id INTEGER,\n" +
		"  winner_name TEXT,\n" +
		"  loser_name TEXT,\n" +
		"  score TEXT,\n" +
		"  outcome TEXT,\n" +
		"  loaded TIMESTAMP NOT NULL DEFAULT now()\n" +
		");\n" +
		"CREATE INDEX IF NOT EXISTS espn_result_players ON bets.espn_result (winner_id, loser_id)";

	private static final String UPSERT_RESULT = //language=SQL
		"INSERT INTO bets.espn_result (espn_id, date, tournament, round, winner_id, loser_id, winner_name, loser_name, score, outcome)\n" +
		"VALUES (:espnId, :date, :tournament, :round, map_ext_player(:winnerExtId), map_ext_player(:loserExtId), :winnerName, :loserName, :score, :outcome)\n" +
		"ON CONFLICT (espn_id) DO UPDATE SET date = EXCLUDED.date, tournament = EXCLUDED.tournament, round = EXCLUDED.round,\n" +
		"  winner_id = EXCLUDED.winner_id, loser_id = EXCLUDED.loser_id, winner_name = EXCLUDED.winner_name, loser_name = EXCLUDED.loser_name,\n" +
		"  score = EXCLUDED.score, outcome = EXCLUDED.outcome, loaded = now()";

	@PostConstruct
	public void createTable() {
		try {
			jdbcTemplate.getJdbcTemplate().execute(CREATE_TABLE);
		}
		catch (Exception ex) {
			LOGGER.error("Cannot create bets.espn_result", ex);
		}
	}

	@Scheduled(cron = "${tennis-stats.results-update.cron:-}")
	public void scheduledUpdate() {
		start("scheduled");
	}

	/** Starts an update unless one is running; returns whether it started. */
	public boolean start(String trigger) {
		if (!running.compareAndSet(false, true))
			return false;
		runningSince = ZonedDateTime.now();
		executor.submit(() -> {
			try {
				lastStatus = update(trigger);
			}
			catch (Throwable ex) {
				LOGGER.error("Results update failed", ex);
				lastStatus = new Status(trigger, runningSince, ZonedDateTime.now(), false, 0, 0, ex.getMessage());
			}
			finally {
				running.set(false);
			}
		});
		return true;
	}

	public Map<String, Object> getStatus() {
		var status = new LinkedHashMap<String, Object>();
		status.put("running", running.get());
		if (running.get())
			status.put("runningSince", runningSince.toString());
		var last = lastStatus;
		if (last != null) {
			status.put("trigger", last.trigger);
			status.put("finished", last.finished.toString());
			status.put("ok", last.ok);
			status.put("results", last.results);
			status.put("settled", last.settled);
			if (last.message != null)
				status.put("message", last.message);
		}
		return status;
	}

	private Status update(String trigger) throws IOException {
		var started = ZonedDateTime.now();
		var exitCode = CommandExecutor.execute("ResultsUpdate", command);
		var results = loadResults();
		var settled = myBetsService.settlePendingBets();
		dataService.evictGlobal("InProgressEvents");
		dataService.clearCaches("InProgressEvent.*");
		var ok = exitCode == 0;
		LOGGER.info("Results update ({}): {} result(s) loaded, {} bet(s) settled{}", trigger, results, settled, ok ? "" : ", script exit code " + exitCode);
		return new Status(trigger, started, ZonedDateTime.now(), ok, results, settled, ok ? null : "Some ESPN steps failed (exit code " + exitCode + "); see the log");
	}

	private int loadResults() throws IOException {
		var file = new File(resultsFile);
		if (!file.exists())
			return 0;
		var root = new ObjectMapper().readTree(file);
		var count = 0;
		for (var m : root.path("matches")) {
			jdbcTemplate.update(UPSERT_RESULT, new MapSqlParameterSource()
				.addValue("espnId", m.path("espn_id").asText())
				.addValue("date", LocalDate.parse(m.path("date").asText()))
				.addValue("tournament", text(m, "tournament"))
				.addValue("round", text(m, "round"))
				.addValue("winnerExtId", m.path("winner_ext_id").isInt() ? m.path("winner_ext_id").asInt() : null)
				.addValue("loserExtId", m.path("loser_ext_id").isInt() ? m.path("loser_ext_id").asInt() : null)
				.addValue("winnerName", text(m, "winner_name"))
				.addValue("loserName", text(m, "loser_name"))
				.addValue("score", text(m, "score"))
				.addValue("outcome", text(m, "outcome")));
			count++;
		}
		return count;
	}

	private static String text(JsonNode node, String field) {
		var value = node.path(field);
		return value.isNull() || value.isMissingNode() ? null : value.asText();
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}

	private static final class Status {
		final String trigger; final ZonedDateTime started, finished; final boolean ok; final int results, settled; final String message;
		Status(String trigger, ZonedDateTime started, ZonedDateTime finished, boolean ok, int results, int settled, String message) {
			this.trigger = trigger; this.started = started; this.finished = finished; this.ok = ok; this.results = results; this.settled = settled; this.message = message;
		}
	}
}
