package org.strangeforest.tcb.stats.service;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.annotation.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;
import org.strangeforest.tcb.stats.model.*;
import org.strangeforest.tcb.stats.util.*;

import static org.strangeforest.tcb.stats.model.MyBet.*;
import static org.strangeforest.tcb.stats.util.ParamsUtil.*;
import static org.strangeforest.tcb.stats.util.ResultSetUtil.*;

/**
 * The site owner's bets (My Bets). Kept in their own schema "bets", so database reloads of the tennis data
 * (schema "tcb") never touch them. Match-winner bets on two known players settle themselves from match results.
 */
@Service
public class MyBetsService {

	public static final List<String> BET_TYPES = List.of("Match Winner", "Set Handicap", "Game Handicap", "Total Games", "Total Sets", "Set Betting", "Outright", "Other");
	public static final String MATCH_WINNER = "Match Winner";

	private static final Logger LOGGER = LoggerFactory.getLogger(MyBetsService.class);

	@Autowired private NamedParameterJdbcTemplate jdbcTemplate;
	@Autowired private MatchPredictionService matchPredictionService;

	private static final String CREATE_SCHEMA = //language=SQL
		"CREATE TABLE IF NOT EXISTS bets.my_bet (\n" +
		"  bet_id SERIAL PRIMARY KEY,\n" +
		"  placed DATE NOT NULL,\n" +
		"  tournament TEXT,\n" +
		"  bet_type TEXT NOT NULL,\n" +
		"  player_id INTEGER,\n" +
		"  opponent_id INTEGER,\n" +
		"  selection TEXT,\n" +
		"  odds INTEGER NOT NULL CHECK (odds >= 100 OR odds <= -100),\n" +
		"  stake NUMERIC(12,2) NOT NULL CHECK (stake > 0),\n" +
		"  result TEXT NOT NULL DEFAULT 'PENDING' CHECK (result IN ('PENDING', 'WON', 'LOST', 'VOID')),\n" +
		"  settled DATE,\n" +
		"  auto_settled BOOLEAN NOT NULL DEFAULT FALSE,\n" +
		"  model_probability REAL,\n" +
		"  notes TEXT,\n" +
		"  created TIMESTAMP NOT NULL DEFAULT now()\n" +
		")";

	private static final String CREATE_PARLAYS = //language=SQL
		"CREATE TABLE IF NOT EXISTS bets.my_parlay (\n" +
		"  parlay_id SERIAL PRIMARY KEY,\n" +
		"  placed DATE NOT NULL,\n" +
		"  odds INTEGER NOT NULL CHECK (odds >= 100 OR odds <= -100),\n" +
		"  stake NUMERIC(12,2) NOT NULL CHECK (stake > 0),\n" +
		"  notes TEXT,\n" +
		"  created TIMESTAMP NOT NULL DEFAULT now()\n" +
		");\n" +
		"ALTER TABLE bets.my_bet ADD COLUMN IF NOT EXISTS parlay_id INTEGER REFERENCES bets.my_parlay (parlay_id) ON DELETE SET NULL";

	private static final String PARLAYS_QUERY = //language=SQL
		"SELECT parlay_id, placed, odds, stake, notes FROM bets.my_parlay";

	private static final String INSERT_PARLAY = //language=SQL
		"INSERT INTO bets.my_parlay (placed, odds, stake, notes) VALUES (:placed, :odds, :stake, :notes) RETURNING parlay_id";

	private static final String UPDATE_PARLAY = //language=SQL
		"UPDATE bets.my_parlay SET placed = :placed, odds = :odds, stake = :stake, notes = :notes WHERE parlay_id = :parlayId";

	// Typed tournament name vs event name: either contains the other, or "ATP Finals"-style names for the Tour Finals
	private static final String EVENT_NAME_MATCH =
		"%e.name ILIKE '%' || b.tournament || '%' OR b.tournament ILIKE '%' || %e.name || '%'" +
		" OR (%e.level = 'F' AND b.tournament ILIKE '%finals%' AND b.tournament NOT ILIKE '%next gen%')";

	private static final String BETS_QUERY = //language=SQL
		"SELECT b.*, p.name AS player_name, p.country_id AS player_country_id, o.name AS opponent_name, o.country_id AS opponent_country_id,\n" +
		"  ipe.in_progress_event_id, ipe.name AS ip_event_name, ipe.level AS ip_event_level, ipe.completed AS ip_event_completed,\n" +
		"  tev.tournament_event_id, tev.name AS event_name, tev.level AS event_level\n" +
		"FROM bets.my_bet b\n" +
		"LEFT JOIN player_v p ON p.player_id = b.player_id\n" +
		"LEFT JOIN player_v o ON o.player_id = b.opponent_id\n" +
		// The event of the bet: where the two players meet, else by tournament name, around the bet's date
		"LEFT JOIN LATERAL (\n" +
		"  SELECT e.in_progress_event_id, e.name, e.level::TEXT AS level, e.completed FROM in_progress_event e\n" +
		"  WHERE b.placed BETWEEN e.date - 7 AND e.date + 21\n" +
		"  AND ((b.player_id IS NOT NULL AND b.opponent_id IS NOT NULL AND EXISTS (SELECT 1 FROM in_progress_match m WHERE m.in_progress_event_id = e.in_progress_event_id\n" +
		"      AND ((m.player1_id = b.player_id AND m.player2_id = b.opponent_id) OR (m.player1_id = b.opponent_id AND m.player2_id = b.player_id))))\n" +
		"    OR (b.tournament IS NOT NULL AND (" + EVENT_NAME_MATCH.replace("%e", "e") + ")))\n" +
		"  ORDER BY abs(b.placed - e.date) LIMIT 1\n" +
		") ipe ON TRUE\n" +
		"LEFT JOIN LATERAL (\n" +
		"  SELECT te.tournament_event_id, te.name, te.level::TEXT AS level FROM tournament_event te\n" +
		"  WHERE te.date BETWEEN b.placed - 21 AND b.placed + 7\n" +
		"  AND ((b.player_id IS NOT NULL AND b.opponent_id IS NOT NULL AND EXISTS (SELECT 1 FROM match m WHERE m.tournament_event_id = te.tournament_event_id\n" +
		"      AND ((m.winner_id = b.player_id AND m.loser_id = b.opponent_id) OR (m.winner_id = b.opponent_id AND m.loser_id = b.player_id))))\n" +
		"    OR (b.tournament IS NOT NULL AND (" + EVENT_NAME_MATCH.replace("%e", "te") + ")))\n" +
		"  ORDER BY abs(b.placed - te.date) LIMIT 1\n" +
		") tev ON TRUE\n" +
		"ORDER BY b.placed DESC, b.bet_id DESC";

	private static final String EVENTS_AUTOCOMPLETE_QUERY = //language=SQL
		"SELECT name, date, level, in_progress FROM (\n" +
		"  SELECT name, date, level::TEXT AS level, NOT completed AS in_progress FROM in_progress_event WHERE date >= current_date - 60\n" +
		"  UNION ALL\n" +
		"  SELECT name, date, level::TEXT, FALSE FROM tournament_event WHERE date >= current_date - 60 AND level IN ('G', 'F', 'L', 'M', 'O', 'A', 'B')\n" +
		") e\n" +
		"WHERE name ILIKE '%' || :term || '%'\n" +
		"ORDER BY in_progress DESC, date DESC LIMIT 10";

	private static final String INSERT = //language=SQL
		"INSERT INTO bets.my_bet (placed, tournament, bet_type, player_id, opponent_id, selection, odds, stake, result, settled, model_probability, notes)\n" +
		"VALUES (:placed, :tournament, :betType, :playerId, :opponentId, :selection, :odds, :stake, :result, :settled, :modelProbability, :notes)";

	private static final String UPDATE = //language=SQL
		"UPDATE bets.my_bet SET placed = :placed, tournament = :tournament, bet_type = :betType, player_id = :playerId, opponent_id = :opponentId,\n" +
		"  selection = :selection, odds = :odds, stake = :stake, result = :result, settled = :settled, auto_settled = FALSE,\n" +
		"  model_probability = coalesce(:modelProbability, model_probability), notes = :notes\n" +
		"WHERE bet_id = :betId";

	private static final String DELETE = //language=SQL
		"DELETE FROM bets.my_bet WHERE bet_id = :betId";

	// The two players' meeting that settles the bet, by source: ESPN recent results (real match dates) first, then
	// in-progress events, then loaded matches (dated by tournament start, so the window looks back 14 days, latest first)
	private static final String RESULT_QUERY = //language=SQL
		"SELECT winner_id, outcome FROM (\n" +
		"  SELECT winner_id, outcome, date, 0 AS src FROM bets.espn_result\n" +
		"  WHERE ((winner_id = :playerId AND loser_id = :opponentId) OR (winner_id = :opponentId AND loser_id = :playerId))\n" +
		"  AND date >= :placed - INTERVAL '1 day'\n" +
		"  UNION ALL\n" +
		"  SELECT CASE winner WHEN 1 THEN player1_id ELSE player2_id END, outcome::TEXT, date, 1 FROM in_progress_match\n" +
		"  WHERE winner IS NOT NULL\n" +
		"  AND ((player1_id = :playerId AND player2_id = :opponentId) OR (player1_id = :opponentId AND player2_id = :playerId))\n" +
		"  AND date >= :placed - INTERVAL '1 day'\n" +
		"  UNION ALL\n" +
		"  SELECT winner_id, outcome::TEXT, date, 2 FROM match\n" +
		"  WHERE ((winner_id = :playerId AND loser_id = :opponentId) OR (winner_id = :opponentId AND loser_id = :playerId))\n" +
		"  AND date >= :placed - INTERVAL '14 days'\n" +
		") m\n" +
		"ORDER BY src, CASE WHEN src = 2 THEN -extract(EPOCH FROM date) ELSE extract(EPOCH FROM date) END LIMIT 1";

	private static final String AUTO_SETTLE = //language=SQL
		"UPDATE bets.my_bet SET result = :result, settled = :settled, auto_settled = TRUE WHERE bet_id = :betId AND result = 'PENDING'";

	@PostConstruct
	public void createSchema() {
		var jdbc = jdbcTemplate.getJdbcTemplate();
		try {
			if (jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = 'bets'", Integer.class) == 0)
				jdbc.execute("CREATE SCHEMA bets");
			jdbc.execute(CREATE_SCHEMA);
			jdbc.execute(CREATE_PARLAYS);
			// Bet types were renamed to title case ("Match winner" -> "Match Winner"); bring stored bets in line
			for (var type : BET_TYPES)
				jdbc.update("UPDATE bets.my_bet SET bet_type = ? WHERE lower(bet_type) = lower(?) AND bet_type <> ?", type, type, type);
		}
		catch (Exception ex) {
			LOGGER.error("Cannot create the My Bets table; as a database superuser run: CREATE SCHEMA bets AUTHORIZATION tcb", ex);
		}
	}

	/** Single bets and parlays (with their legs), newest first. */
	public List<Wager> getWagers() {
		settlePendingBets();
		var bets = jdbcTemplate.query(BETS_QUERY, (rs, rowNum) -> mapBet(rs));
		Map<Integer, MyParlay> parlays = new LinkedHashMap<>();
		jdbcTemplate.query(PARLAYS_QUERY, rs -> {
			var parlay = new MyParlay();
			parlay.setId(rs.getInt("parlay_id"));
			parlay.setPlaced(getLocalDate(rs, "placed"));
			parlay.setOdds(rs.getInt("odds"));
			parlay.setStake(rs.getBigDecimal("stake"));
			parlay.setNotes(rs.getString("notes"));
			parlays.put(parlay.getId(), parlay);
		});
		List<Wager> wagers = new ArrayList<>();
		for (var bet : bets) {
			var parlay = bet.getParlayId() != null ? parlays.get(bet.getParlayId()) : null;
			if (parlay != null)
				parlay.addLeg(bet);
			else
				wagers.add(bet);
		}
		for (var parlay : parlays.values()) {
			parlay.getLegs().sort(Comparator.comparing(MyBet::getPlaced).thenComparing(MyBet::getId));
			wagers.add(parlay);
		}
		wagers.sort(Comparator.comparing(Wager::getPlaced).thenComparing(Wager::isParlay).thenComparing(Wager::getId).reversed());
		return wagers;
	}

	/** Groups bets into a parlay (new when parlayId is null), or updates a parlay's odds, stake, date and notes. */
	@Transactional
	public void saveParlay(Integer parlayId, List<Integer> betIds, int odds, BigDecimal stake, LocalDate placed, String notes) {
		if (odds > -100 && odds < 100)
			throw new IllegalArgumentException("American odds must be +100 or more, or -100 or less");
		if (stake == null || stake.signum() <= 0)
			throw new IllegalArgumentException("Stake must be positive");
		if (placed == null)
			throw new IllegalArgumentException("Date is required");
		var params = new MapSqlParameterSource().addValue("parlayId", parlayId).addValue("placed", placed).addValue("odds", odds)
			.addValue("stake", stake).addValue("notes", trimToNull(notes));
		if (parlayId == null) {
			var ids = betIds == null ? List.<Integer>of() : new ArrayList<>(new LinkedHashSet<>(betIds));
			if (ids.size() < 2)
				throw new IllegalArgumentException("A parlay needs at least 2 bets");
			var free = jdbcTemplate.queryForObject("SELECT count(*) FROM bets.my_bet WHERE bet_id IN (:ids) AND parlay_id IS NULL", params("ids", ids), Integer.class);
			if (free == null || free != ids.size())
				throw new IllegalArgumentException("Some of the bets are missing or already in a parlay");
			parlayId = jdbcTemplate.queryForObject(INSERT_PARLAY, params, Integer.class);
			jdbcTemplate.update("UPDATE bets.my_bet SET parlay_id = :parlayId WHERE bet_id IN (:ids)", new MapSqlParameterSource().addValue("parlayId", parlayId).addValue("ids", ids));
		}
		else if (jdbcTemplate.update(UPDATE_PARLAY, params) == 0)
			throw new NotFoundException("Parlay", parlayId);
	}

	/** Turns a parlay back into single bets. */
	@Transactional
	public void ungroupParlay(int parlayId) {
		jdbcTemplate.update("UPDATE bets.my_bet SET parlay_id = NULL WHERE parlay_id = :parlayId", params("parlayId", parlayId));
		jdbcTemplate.update("DELETE FROM bets.my_parlay WHERE parlay_id = :parlayId", params("parlayId", parlayId));
	}

	/** Deletes a parlay together with its legs. */
	@Transactional
	public void deleteParlay(int parlayId) {
		jdbcTemplate.update("DELETE FROM bets.my_bet WHERE parlay_id = :parlayId", params("parlayId", parlayId));
		jdbcTemplate.update("DELETE FROM bets.my_parlay WHERE parlay_id = :parlayId", params("parlayId", parlayId));
	}

	@Transactional
	public void saveBet(MyBet bet) {
		validate(bet);
		if (!bet.isPending() && bet.getSettled() == null)
			bet.setSettled(LocalDate.now());
		if (bet.isPending())
			bet.setSettled(null);
		bet.setModelProbability(modelProbability(bet));
		var params = new MapSqlParameterSource()
			.addValue("betId", bet.getId())
			.addValue("placed", bet.getPlaced())
			.addValue("tournament", trimToNull(bet.getTournament()))
			.addValue("betType", bet.getBetType())
			.addValue("playerId", bet.getPlayerId())
			.addValue("opponentId", bet.getOpponentId())
			.addValue("selection", trimToNull(bet.getSelection()))
			.addValue("odds", bet.getOdds())
			.addValue("stake", bet.getStake())
			.addValue("result", bet.getResult())
			.addValue("settled", bet.getSettled())
			.addValue("modelProbability", bet.getModelProbability())
			.addValue("notes", trimToNull(bet.getNotes()));
		if (bet.getId() == null)
			jdbcTemplate.update(INSERT, params);
		else if (jdbcTemplate.update(UPDATE, params) == 0)
			throw new NotFoundException("Bet", bet.getId());
	}

	/** Deletes a bet; a parlay left with a single leg becomes a single bet again. */
	@Transactional
	public void deleteBet(int betId) {
		var parlayIds = jdbcTemplate.queryForList("SELECT parlay_id FROM bets.my_bet WHERE bet_id = :betId AND parlay_id IS NOT NULL", params("betId", betId), Integer.class);
		jdbcTemplate.update(DELETE, params("betId", betId));
		for (var parlayId : parlayIds) {
			var legs = jdbcTemplate.queryForObject("SELECT count(*) FROM bets.my_bet WHERE parlay_id = :parlayId", params("parlayId", parlayId), Integer.class);
			if (legs == null || legs < 2)
				ungroupParlay(parlayId);
		}
	}

	/** Current and recent events for the Tournament field, running ones first. */
	public List<Map<String, Object>> autocompleteEvents(String term) {
		return jdbcTemplate.query(EVENTS_AUTOCOMPLETE_QUERY, params("term", term.trim()), (rs, rowNum) -> {
			var name = rs.getString("name");
			var date = getLocalDate(rs, "date");
			var inProgress = rs.getBoolean("in_progress");
			return Map.<String, Object>of("value", name, "label", name + " " + date.getYear() + (inProgress ? " (in progress)" : ""), "level", rs.getString("level"));
		});
	}

	/** Points for a winning pick: a flat amount plus what the stake credited to the player wins at the pick's odds. */
	@Value("${tennis-stats.my-bets.points.flat:10}")
	private double flatPoints;

	public double getFlatPoints() { return flatPoints; }

	/**
	 * Players picked in settled bets, ranked by points. A single credits its whole stake to its player; a parlay splits
	 * its stake evenly between its (non-void) legs, and its legs score only once the whole parlay is won. A winning pick
	 * earns the flat points plus what its credited stake wins at the pick's own odds. Also sets each winning bet's points.
	 */
	public List<PlayerPoints> getPlayerPoints(List<Wager> wagers) {
		Map<Integer, PlayerPoints> players = new HashMap<>();
		for (var wager : wagers) {
			if (wager.isParlay()) {
				var parlay = (MyParlay)wager;
				var parlayWon = WON.equals(parlay.getResult());
				var legs = parlay.getLegs().stream().filter(leg -> !VOID.equals(leg.getResult())).count();
				var share = legs > 0 ? parlay.getStake().doubleValue() / legs : 0.0;
				for (var leg : parlay.getLegs())
					countPick(players, leg, parlayWon, share, null);
			}
			else {
				var bet = (MyBet)wager;
				countPick(players, bet, true, bet.getStake().doubleValue(), bet.getProfit());
			}
		}
		var ranked = new ArrayList<>(players.values());
		ranked.removeIf(p -> p.getWins() + p.getLosses() == 0);
		ranked.sort(Comparator.comparingDouble(PlayerPoints::getPoints).reversed().thenComparing(Comparator.comparingInt(PlayerPoints::getWins).reversed()).thenComparing(PlayerPoints::getName));
		for (int i = 0; i < ranked.size(); i++)
			ranked.get(i).setRank(i + 1);
		return ranked;
	}

	private void countPick(Map<Integer, PlayerPoints> players, MyBet bet, boolean scores, double stake, BigDecimal profit) {
		if (bet.getPlayerId() == null)
			return;
		var result = bet.getResult();
		if (!WON.equals(result) && !LOST.equals(result))
			return;
		var player = players.computeIfAbsent(bet.getPlayerId(), id -> new PlayerPoints(id, bet.getPlayerName(), bet.getPlayerCountryId()));
		if (WON.equals(result)) {
			if (!scores)
				return; // a winning leg of a parlay that has not won (yet)
			var oddsPoints = stake * (Wager.decimalOdds(bet.getOdds()) - 1.0);
			player.addWin(bet, flatPoints, oddsPoints, stake);
			bet.setPoints(Math.round((flatPoints + oddsPoints) * 10.0) / 10.0);
			bet.setPointsStake(stake);
		}
		else
			player.addLoss();
		if (profit != null)
			player.addProfit(profit);
	}

	public static MyBetsSummary summarize(List<? extends Wager> bets) {
		return new MyBetsSummary(bets);
	}


	// Settlement

	/** Settles pending match-winner bets on two known players once their match is in the results. */
	/** Settles pending match-winner bets on two known players once their match result is in; returns how many settled. */
	public int settlePendingBets() {
		List<MyBet> pending = jdbcTemplate.query("SELECT * FROM bets.my_bet WHERE result = 'PENDING' AND bet_type = '" + MATCH_WINNER + "' AND player_id IS NOT NULL AND opponent_id IS NOT NULL",
			(rs, rowNum) -> mapBet(rs));
		var settled = new int[1];
		for (var bet : pending) {
			var params = new MapSqlParameterSource().addValue("playerId", bet.getPlayerId()).addValue("opponentId", bet.getOpponentId()).addValue("placed", bet.getPlaced());
			jdbcTemplate.query(RESULT_QUERY, params, rs -> {
				var outcome = rs.getString("outcome");
				String result;
				if ("W/O".equals(outcome) || "ABD".equals(outcome))
					result = VOID; // walkovers and abandoned matches are void at most sportsbooks
				else
					result = rs.getInt("winner_id") == bet.getPlayerId() ? WON : LOST;
				settled[0] += jdbcTemplate.update(AUTO_SETTLE, new MapSqlParameterSource().addValue("betId", bet.getId()).addValue("result", result).addValue("settled", LocalDate.now()));
			});
		}
		return settled[0];
	}

	private Double modelProbability(MyBet bet) {
		if (!MATCH_WINNER.equals(bet.getBetType()) || bet.getPlayerId() == null || bet.getOpponentId() == null)
			return null;
		try {
			return matchPredictionService.predictMatch(bet.getPlayerId(), bet.getOpponentId(), bet.getPlaced(), false, null, null, null, null).getWinProbability1();
		}
		catch (Exception ex) {
			LOGGER.warn("Cannot predict match for bet", ex);
			return null;
		}
	}


	// Util

	private static void validate(MyBet bet) {
		if (bet.getPlaced() == null)
			throw new IllegalArgumentException("Date is required");
		if (!BET_TYPES.contains(bet.getBetType()))
			throw new IllegalArgumentException("Unknown bet type: " + bet.getBetType());
		if (bet.getOdds() > -100 && bet.getOdds() < 100)
			throw new IllegalArgumentException("American odds must be +100 or more, or -100 or less");
		if (bet.getStake() == null || bet.getStake().compareTo(BigDecimal.ZERO) <= 0)
			throw new IllegalArgumentException("Stake must be positive");
		if (!List.of(PENDING, WON, LOST, VOID).contains(bet.getResult()))
			throw new IllegalArgumentException("Unknown result: " + bet.getResult());
		if (bet.getPlayerId() == null && (bet.getSelection() == null || bet.getSelection().isBlank()))
			throw new IllegalArgumentException("Pick a player or describe the selection");
		if (bet.getPlayerId() != null && bet.getPlayerId().equals(bet.getOpponentId()))
			throw new IllegalArgumentException("Player and opponent must differ");
	}

	private static String trimToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static MyBet mapBet(ResultSet rs) throws SQLException {
		var bet = new MyBet();
		bet.setId(rs.getInt("bet_id"));
		bet.setPlaced(getLocalDate(rs, "placed"));
		bet.setTournament(rs.getString("tournament"));
		bet.setBetType(rs.getString("bet_type"));
		bet.setPlayerId(getInteger(rs, "player_id"));
		bet.setOpponentId(getInteger(rs, "opponent_id"));
		bet.setSelection(rs.getString("selection"));
		bet.setOdds(rs.getInt("odds"));
		bet.setStake(rs.getBigDecimal("stake"));
		bet.setResult(rs.getString("result"));
		bet.setSettled(getLocalDate(rs, "settled"));
		bet.setAutoSettled(rs.getBoolean("auto_settled"));
		var model = rs.getFloat("model_probability");
		bet.setModelProbability(rs.wasNull() ? null : (double)model);
		bet.setNotes(rs.getString("notes"));
		bet.setParlayId(getInteger(rs, "parlay_id"));
		if (hasColumn(rs, "player_name")) {
			bet.setPlayerName(rs.getString("player_name"));
			bet.setPlayerCountryId(rs.getString("player_country_id"));
			bet.setOpponentName(rs.getString("opponent_name"));
			bet.setOpponentCountryId(rs.getString("opponent_country_id"));
			var inProgressEventId = getInteger(rs, "in_progress_event_id");
			var tournamentEventId = getInteger(rs, "tournament_event_id");
			// A running event links to its forecast; a finished one to its results (falling back to its forecast page)
			if (inProgressEventId != null && (!rs.getBoolean("ip_event_completed") || tournamentEventId == null)) {
				bet.setEvent(rs.getString("ip_event_name"), rs.getString("ip_event_level"), "/inProgressEventForecast?inProgressEventId=" + inProgressEventId);
			}
			else if (tournamentEventId != null)
				bet.setEvent(rs.getString("event_name"), rs.getString("event_level"), "/tournamentEvent?tournamentEventId=" + tournamentEventId);
		}
		return bet;
	}

	private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
		var md = rs.getMetaData();
		for (int i = 1; i <= md.getColumnCount(); i++) {
			if (column.equalsIgnoreCase(md.getColumnLabel(i)))
				return true;
		}
		return false;
	}
}
