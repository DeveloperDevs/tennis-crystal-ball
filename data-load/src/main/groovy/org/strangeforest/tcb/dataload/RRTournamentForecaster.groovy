package org.strangeforest.tcb.dataload

import org.strangeforest.tcb.stats.model.core.*

import static org.strangeforest.tcb.dataload.BaseXMLLoader.*

/**
 * Round-robin (ATP Finals style) tournament forecast by Monte Carlo simulation:
 * groups of 4, top 2 of each group to the semi-finals (A1 v B2, B1 v A2), then the final.
 * Group ranking: wins, then head-to-head for two tied players; for three tied players % sets won,
 * then head-to-head if two remain tied, then seed (stand-in for % games won and ranking).
 * Completed matches keep their real results; when the real semi-final line-up is known, it is used.
 */
class RRTournamentForecaster {

	static final int ITERATIONS = 100_000

	TournamentMatchPredictor predictor
	int inProgressEventId
	List matches
	boolean current
	boolean verbose
	Random random = new Random(20261115L)

	List<List<Integer>> groups = []
	Map<Integer, Integer> seeds = [:]
	List rrMatches, sfMatches, fMatches
	int bestOf

	RRTournamentForecaster(TournamentMatchPredictor predictor, int inProgressEventId, List matches, boolean current, boolean verbose = false) {
		this.predictor = predictor
		this.inProgressEventId = inProgressEventId
		this.matches = matches
		this.current = current
		this.verbose = verbose
		rrMatches = matches.findAll { it.round == 'RR' && it.player1_id && it.player2_id }
		sfMatches = matches.findAll { it.round == 'SF' }.sort { it.match_num }
		fMatches = matches.findAll { it.round == 'F' }
		bestOf = (matches[0].best_of ?: 3) as int
		buildGroups()
	}

	private buildGroups() {
		Map<Integer, Integer> parent = [:]
		def find
		find = { Integer x -> def p = parent.get(x, x); p == x ? x : find(p) }
		rrMatches.each { m ->
			[[m.player1_id, m.player1_seed], [m.player2_id, m.player2_seed]].each { id, seed ->
				parent.putIfAbsent(id as Integer, id as Integer)
				if (seed) seeds[id as Integer] = seed as Integer
			}
			parent[find(m.player1_id as Integer)] = find(m.player2_id as Integer)
		}
		groups = parent.keySet().groupBy { find(it) }.values().collect { g -> g.sort { seedOf(it) } }.sort { seedOf(it[0]) }
		if (verbose)
			println "Groups: $groups"
	}

	private int seedOf(Integer playerId) {
		seeds[playerId] ?: 99
	}


	// Forecast

	def forecast() {
		def counts = [SF: [:], F: [:], W: [:]].withDefault { [:] }
		def players = groups.flatten()
		def qualifiersKnown = current && sfMatches.size() == 2 && sfMatches.every { it.player1_id && it.player2_id }
		ITERATIONS.times {
			List<List<Integer>> semis
			if (qualifiersKnown)
				semis = sfMatches.collect { [it.player1_id as Integer, it.player2_id as Integer] }
			else {
				def standings = groups.collect { group -> simulateGroup(group) }
				if (standings.size() != 2 || standings.any { it.size() < 2 })
					return
				semis = [[standings[0][0], standings[1][1]], [standings[1][0], standings[0][1]]]
			}
			def finalists = semis.withIndex().collect { pair, i ->
				pair.each { inc(counts.SF, it) }
				playMatch(pair[0], pair[1], Round.SF, current ? sfMatches[i] : null)
			}
			finalists.each { inc(counts.F, it) }
			inc(counts.W, playMatch(finalists[0], finalists[1], Round.F, current ? fMatches[0] : null))
		}
		def results = []
		['SF', 'F', 'W'].each { result ->
			players.each { playerId ->
				def probability = (counts[result][playerId] ?: 0) / (double) ITERATIONS
				results << [in_progress_event_id: inProgressEventId, player_id: playerId, base_result: current ? 'W' : 'RR', result: result,
				            probability: real(probability), avg_draw_probability: null, no_draw_probability: null]
			}
		}
		if (verbose)
			results.findAll { it.result == 'W' }.sort { -it.probability }.each { println "  ${it.player_id}: ${it.probability}" }
		results
	}

	private static inc(Map counts, Integer playerId) {
		counts[playerId] = (counts[playerId] ?: 0) + 1
	}

	/** Returns the group's players in finishing order. */
	private List<Integer> simulateGroup(List<Integer> group) {
		Map<Integer, int[]> stats = group.collectEntries { [(it): new int[3]] } // wins, sets won, sets lost
		Map<String, Integer> h2h = [:]
		rrMatches.findAll { it.player1_id in group }.each { m ->
			def p1 = m.player1_id as Integer, p2 = m.player2_id as Integer
			int s1, s2
			if (current && m.winner) {
				s1 = (m.p1_sets ?: (m.winner == 1 ? setsToWin() : 0)) as int
				s2 = (m.p2_sets ?: (m.winner == 2 ? setsToWin() : 0)) as int
				if (m.winner == 1 && s1 <= s2) s1 = s2 + 1  // retirement: winner still takes the win
				if (m.winner == 2 && s2 <= s1) s2 = s1 + 1
			}
			else
				(s1, s2) = simulateSets(predictor.getWinProbability(p1, p2, Round.RR, true))
			def w = s1 > s2 ? p1 : p2
			stats[w][0]++
			stats[p1][1] += s1; stats[p1][2] += s2
			stats[p2][1] += s2; stats[p2][2] += s1
			h2h[key(p1, p2)] = w
		}
		def order = []
		group.groupBy { stats[it][0] }.sort { -it.key }.each { wins, tied ->
			order.addAll(breakTie(tied, stats, h2h))
		}
		order
	}

	private List<Integer> breakTie(List<Integer> tied, Map<Integer, int[]> stats, Map<String, Integer> h2h) {
		if (tied.size() == 1)
			return tied
		if (tied.size() == 2) {
			def w = h2h[key(tied[0], tied[1])]
			return w == tied[1] ? [tied[1], tied[0]] : tied
		}
		def order = []
		tied.groupBy { p -> def s = stats[p]; s[1] / (double) Math.max(1, s[1] + s[2]) }.sort { -it.key }.each { pct, sub ->
			if (sub.size() == 2)
				order.addAll(breakTie(sub, stats, h2h))
			else
				order.addAll(sub.sort { seedOf(it) })
		}
		order
	}

	private static String key(Integer a, Integer b) {
		a < b ? "$a-$b" : "$b-$a"
	}

	private Integer playMatch(Integer p1, Integer p2, Round round, Map actual) {
		if (actual?.winner && actual.player1_id && actual.player2_id)
			return (actual.winner == 1 ? actual.player1_id : actual.player2_id) as Integer
		random.nextDouble() < predictor.getWinProbability(p1, p2, round, true) ? p1 : p2
	}

	private int setsToWin() {
		bestOf.intdiv(2) + 1
	}

	private Map<Double, Double> setProbabilities = [:]

	/** Simulates a match as independent sets with the set win probability matching the match win probability. */
	private List<Integer> simulateSets(double matchProbability) {
		def p = setProbabilities.computeIfAbsent(matchProbability) { setProbability(it) }
		int s1 = 0, s2 = 0, need = setsToWin()
		while (s1 < need && s2 < need) {
			if (random.nextDouble() < p) s1++ else s2++
		}
		[s1, s2]
	}

	private double setProbability(double matchProbability) {
		double lo = 0.0, hi = 1.0
		50.times {
			def mid = (lo + hi) / 2
			if (matchFromSet(mid) < matchProbability) lo = mid else hi = mid
		}
		(lo + hi) / 2
	}

	private double matchFromSet(double s) {
		bestOf == 5 ? s**3 * (10 - 15 * s + 6 * s * s) : s * s * (3 - 2 * s)
	}
}
