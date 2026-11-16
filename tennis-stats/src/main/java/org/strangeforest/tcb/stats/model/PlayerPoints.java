package org.strangeforest.tcb.stats.model;

import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

import org.strangeforest.tcb.util.*;

/**
 * A player's My Bets points: every bet won on the player (a single, or a leg of a winning parlay) earns a flat amount
 * plus what the stake credited to the player wins at the pick's odds (a parlay's stake is split between its legs).
 */
public class PlayerPoints {

	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private final int playerId;
	private final String name;
	private final String countryId;
	private int rank;
	private int wins, losses;
	private double points, flatPoints, oddsPoints, credited;
	private Integer bestOdds;
	private double decimalOddsSum;
	private BigDecimal profit = BigDecimal.ZERO;
	private LocalDate lastWin;
	private String lastWinEvent;

	public PlayerPoints(int playerId, String name, String countryId) {
		this.playerId = playerId;
		this.name = name;
		this.countryId = countryId;
	}

	public void addWin(MyBet bet, double flat, double odds, double stake) {
		wins++;
		credited += stake;
		flatPoints += flat;
		oddsPoints += odds;
		points += flat + odds;
		decimalOddsSum += Wager.decimalOdds(bet.getOdds());
		if (bestOdds == null || Wager.decimalOdds(bet.getOdds()) > Wager.decimalOdds(bestOdds))
			bestOdds = bet.getOdds();
		var settled = bet.getSettled() != null ? bet.getSettled() : bet.getPlaced();
		if (lastWin == null || settled.isAfter(lastWin)) {
			lastWin = settled;
			lastWinEvent = bet.getTournamentText();
		}
	}

	public void addLoss() {
		losses++;
	}

	public void addProfit(BigDecimal amount) {
		profit = profit.add(amount);
	}

	public void setRank(int rank) { this.rank = rank; }

	public int getPlayerId() { return playerId; }
	public String getName() { return name; }
	public String getCountryCode() { return countryId != null ? new Country(countryId).getCode() : null; }
	public String getCountryName() { return countryId != null ? new Country(countryId).getName() : null; }
	public int getRank() { return rank; }
	public int getWins() { return wins; }
	public int getLosses() { return losses; }
	public double getPoints() { return round(points); }
	public double getFlatPoints() { return round(flatPoints); }
	public double getOddsPoints() { return round(oddsPoints); }
	public double getCredited() { return Math.round(credited * 100.0) / 100.0; }
	public String getBestOdds() { return bestOdds != null ? Wager.oddsText(bestOdds) : null; }
	public String getAvgOdds() { return wins > 0 ? Wager.oddsText(Wager.americanOdds(decimalOddsSum / wins)) : null; }
	public Double getWinPct() { return wins + losses > 0 ? round(100.0 * wins / (wins + losses)) : null; }
	public BigDecimal getProfit() { return profit; }
	public String getLastWin() { return lastWin != null ? DATE_FORMAT.format(lastWin) : null; }
	public String getLastWinEvent() { return lastWinEvent; }

	private static double round(double value) {
		return Math.round(value * 10.0) / 10.0;
	}
}
