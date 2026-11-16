package org.strangeforest.tcb.stats.model.forecast;

import java.util.*;

/** One round-robin group: its players' tournament forecast and its matches (forecast, or result once played). */
public class RoundRobinGroup {

	private final String name;
	private final List<Entry> entries = new ArrayList<>();
	private final List<RoundRobinMatch> matches = new ArrayList<>();

	public RoundRobinGroup(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public List<Entry> getEntries() {
		return entries;
	}

	public List<RoundRobinMatch> getMatches() {
		return matches;
	}

	public void addEntry(PlayerForecast player, PlayerForecast current) {
		entries.add(new Entry(player, current));
	}

	public void addMatch(RoundRobinMatch match) {
		matches.add(match);
	}

	public int getWins(int playerId) {
		return (int)matches.stream().filter(match -> match.isCompleted() && match.getWinnerPlayer().getId() == playerId).count();
	}

	public int getLosses(int playerId) {
		return (int)matches.stream().filter(match -> match.isCompleted() && match.getLoserPlayer().getId() == playerId).count();
	}

	/** Group player: {@code player} carries name, seed, rank and Elo; {@code current} the current forecast (null once eliminated). */
	public static class Entry {

		private final PlayerForecast player;
		private final PlayerForecast current;

		Entry(PlayerForecast player, PlayerForecast current) {
			this.player = player;
			this.current = current;
		}

		public PlayerForecast getPlayer() {
			return player;
		}

		public Double getProbability(String result) {
			if (current == null)
				return 0.0;
			var probability = current.getProbability(result);
			return probability != null ? probability : 0.0;
		}
	}
}
