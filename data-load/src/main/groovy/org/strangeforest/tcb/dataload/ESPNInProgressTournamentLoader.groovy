package org.strangeforest.tcb.dataload

import java.time.*

import org.springframework.jdbc.core.namedparam.*
import org.strangeforest.tcb.stats.model.core.*
import org.strangeforest.tcb.stats.model.elo.*
import org.strangeforest.tcb.stats.service.*

import com.google.common.base.*
import groovy.json.*
import groovy.sql.*

import static org.strangeforest.tcb.dataload.BaseXMLLoader.*
import static org.strangeforest.tcb.dataload.LoaderUtil.*

/**
 * Loads in-progress events from the JSON written by tools/espn-import/espn_in_progress.py
 * (replacing the atptour.com draw scraper) and forecasts them.
 * Only round-robin draws are supported, as ESPN does not expose knockout bracket positions.
 */
class ESPNInProgressTournamentLoader extends ATPTourInProgressTournamentLoader {

	static final String FILE_NAME = 'atp_in_progress.json'

	static final String LAST_EVENT_SQL = //language=SQL
		'SELECT e.surface, e.indoor FROM tournament_event e\n' +
		'WHERE e.tournament_id = map_ext_tournament(:extId)\n' +
		'ORDER BY e.date DESC LIMIT 1'

	ESPNInProgressTournamentLoader(Sql sql) {
		super(sql)
	}

	static List readEvents(String baseDir) {
		def file = new File(baseDir, FILE_NAME)
		if (!file.exists()) {
			println "No $file; run tools/espn-import/espn_in_progress.py first"
			return null
		}
		new JsonSlurper().parse(file).events as List
	}

	boolean loadAndForecast(Map event, boolean verbose = false) {
		try {
			def stopwatch = Stopwatch.createStarted()
			def extId = event.ext_tournament_id as String
			def last = sql.firstRow([extId: extId], LAST_EVENT_SQL)
			def surface = event.surface ?: last?.surface
			def indoor = last?.indoor ?: false
			def startDate = date(event.date)

			saveEvent([ext_tournament_id: extId, date: startDate, name: event.name, tournament_level: event.level,
			           surface: surface, indoor: indoor, draw_type: event.draw_type, draw_size: smallint(event.draw_size)])

			def matches = new TreeMap()
			event.matches.each { m ->
				def match = [:]
				match.ext_tournament_id = extId
				match.match_num = smallint(m.match_num)
				match.date = m.date ? date(m.date) : startDate
				match.surface = surface
				match.indoor = indoor
				match.round = m.round
				match.best_of = smallint(event.best_of)
				match.player1_name = m.p1?.name
				match.player1_seed = smallint(m.p1?.seed)
				match.player2_name = m.p2?.name
				match.player2_seed = smallint(m.p2?.seed)
				if (m.winner)
					setScore(match, m.winner as int, m.score as String)
				matches[match.match_num] = match
			}
			def count = saveMatches([ext_tournament_id: extId, surface: surface], matches)
			sql.commit()
			if (count > 0) {
				println "${event.name}: ${matches.size()} matches loaded in $stopwatch"
				forecastRRTournament(extId, verbose)
				sql.commit()
			}
			else
				println "${event.name}: matches not changed"
			true
		}
		catch (Exception ex) {
			System.err.println "Error loading and forecasting in-progress tournament: ${event.name}"
			ex.printStackTrace()
			sql.rollback()
			false
		}
	}

	private setScore(Map match, int winner, String score) {
		match.winner = smallint(winner)
		def s = MatchScoreParser.parse(score)
		if (!s)
			return
		def conn = sql.connection
		def (w, l) = winner == 1 ? ['p1_', 'p2_'] : ['p2_', 'p1_']
		match[w + 'sets'] = s.w_sets; match[l + 'sets'] = s.l_sets
		match[w + 'games'] = s.w_games; match[l + 'games'] = s.l_games
		match[w + 'tbs'] = s.w_tbs; match[l + 'tbs'] = s.l_tbs
		match[w + 'set_games'] = shortArray(conn, s.w_set_games); match[l + 'set_games'] = shortArray(conn, s.l_set_games)
		match[w + 'set_tb_pt'] = shortArray(conn, s.w_set_tb_pt); match[l + 'set_tb_pt'] = shortArray(conn, s.l_set_tb_pt)
		match.score = s.toString()
		match.outcome = s.outcome
	}

	def forecastRRTournament(String extId, boolean verbose) {
		def stopwatch = Stopwatch.createStarted()
		// Chronological order, so in-tournament Elo ratings carry from one match to the next
		def matches = fetchMatches(extId).sort { a, b -> a.date <=> b.date ?: a.match_num <=> b.match_num }
		def first = matches[0]
		def inProgressEventId = first.in_progress_event_id
		def predictionService = new MatchPredictionService(new NamedParameterJdbcTemplate(SqlPool.dataSource()))
		def predictor = new TournamentMatchPredictor(predictionService, LocalDate.now(), first.tournament_id, inProgressEventId,
			Surface.decode(first.surface), first.indoor, TournamentLevel.decode(first.level), first.best_of)

		def elo = new KOTournamentForecaster(predictor, inProgressEventId, [], null, true, false, false)
		elo.matches = matches
		elo.calculateEloRatings(new EloSurfaceFactors(SqlPool.dataSource(), LocalDate.now().year - 1))
		saveEloRatings(matches)

		deleteResults(inProgressEventId)
		def results = new RRTournamentForecaster(predictor, inProgressEventId, matches, false, verbose).forecast() +
		              new RRTournamentForecaster(predictor, inProgressEventId, matches, true, verbose).forecast()
		saveResults(results)
		println "Tournament forecast: ${results.size()} results loaded in $stopwatch"
	}
}
