package org.strangeforest.tcb.stats.model;

import java.math.*;
import java.time.*;
import java.util.*;

import static org.strangeforest.tcb.stats.model.MyBet.*;

/**
 * A parlay of My Bets: one stake on several legs (single bets), paid at the parlay odds if every leg wins.
 * A lost leg loses the parlay; a void leg drops out and the payout is recalculated without it.
 */
public class MyParlay implements Wager {

	private Integer id;
	private LocalDate placed;
	private int odds;
	private BigDecimal stake;
	private String notes;
	private final List<MyBet> legs = new ArrayList<>();

	@Override public Integer getId() { return id; }
	public void setId(Integer id) { this.id = id; }

	@Override public LocalDate getPlaced() { return placed; }
	public void setPlaced(LocalDate placed) { this.placed = placed; }

	public int getOdds() { return odds; }
	public void setOdds(int odds) { this.odds = odds; }

	@Override public BigDecimal getStake() { return stake; }
	public void setStake(BigDecimal stake) { this.stake = stake; }

	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }

	public List<MyBet> getLegs() { return legs; }

	public void addLeg(MyBet leg) { legs.add(leg); }

	@Override public boolean isParlay() { return true; }


	// Derived

	@Override public String getResult() {
		if (legs.isEmpty())
			return PENDING;
		if (legs.stream().anyMatch(leg -> LOST.equals(leg.getResult())))
			return LOST;
		if (legs.stream().allMatch(leg -> VOID.equals(leg.getResult())))
			return VOID;
		if (legs.stream().anyMatch(leg -> PENDING.equals(leg.getResult())))
			return PENDING;
		return WON;
	}

	public LocalDate getSettled() {
		return isPending() ? null : legs.stream().map(MyBet::getSettled).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
	}

	/** Parlay decimal odds, with void legs taken out (keeping any boost the sportsbook gave over the plain product). */
	private double effectiveDecimalOdds() {
		var decimal = Wager.decimalOdds(odds);
		var all = 1.0;
		var live = 1.0;
		for (var leg : legs) {
			var legDecimal = Wager.decimalOdds(leg.getOdds());
			all *= legDecimal;
			if (!VOID.equals(leg.getResult()))
				live *= legDecimal;
		}
		return all > 0.0 && live < all ? decimal * live / all : decimal;
	}

	public boolean isOddsReduced() {
		return legs.stream().anyMatch(leg -> VOID.equals(leg.getResult())) && !VOID.equals(getResult());
	}

	@Override public BigDecimal getToWin() {
		if (stake == null)
			return BigDecimal.ZERO;
		return stake.multiply(BigDecimal.valueOf(effectiveDecimalOdds() - 1.0)).setScale(2, RoundingMode.HALF_UP);
	}

	@Override public BigDecimal getProfit() {
		var result = getResult();
		if (WON.equals(result))
			return getToWin();
		else if (LOST.equals(result))
			return stake.negate();
		else
			return BigDecimal.ZERO;
	}

	/** The parlay odds from the legs' odds (what a sportsbook pays with no boost). */
	public int getComputedOdds() {
		var decimal = 1.0;
		for (var leg : legs)
			decimal *= Wager.decimalOdds(leg.getOdds());
		return Wager.americanOdds(decimal);
	}

	public String getComputedOddsText() {
		return Wager.oddsText(getComputedOdds());
	}

	public String getOddsText() {
		return Wager.oddsText(odds);
	}

	public double getImpliedProbability() {
		return 100.0 / Wager.decimalOdds(odds);
	}

	/** Product of the legs' model probabilities, when every leg has one. */
	public Double getModelPercentage() {
		var probability = 1.0;
		for (var leg : legs) {
			if (leg.getModelProbability() == null)
				return null;
			probability *= leg.getModelProbability();
		}
		return legs.isEmpty() ? null : 100.0 * probability;
	}

	public Double getEdge() {
		var model = getModelPercentage();
		return model != null ? model - getImpliedProbability() : null;
	}

	public int getWonLegs() {
		return (int)legs.stream().filter(leg -> WON.equals(leg.getResult())).count();
	}
}
