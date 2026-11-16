package org.strangeforest.tcb.stats.model;

import java.math.*;
import java.time.*;

/** Something with money on it in My Bets: a single bet or a parlay. */
public interface Wager {

	Integer getId();
	LocalDate getPlaced();
	String getResult();
	BigDecimal getStake();
	BigDecimal getToWin();
	BigDecimal getProfit();
	boolean isParlay();

	default boolean isPending() {
		return MyBet.PENDING.equals(getResult());
	}


	// American odds

	static double decimalOdds(int americanOdds) {
		return americanOdds > 0 ? 1.0 + americanOdds / 100.0 : 1.0 + 100.0 / -americanOdds;
	}

	static int americanOdds(double decimalOdds) {
		return (int)Math.round(decimalOdds >= 2.0 ? (decimalOdds - 1.0) * 100.0 : -100.0 / (decimalOdds - 1.0));
	}

	static String oddsText(int americanOdds) {
		return americanOdds > 0 ? "+" + americanOdds : String.valueOf(americanOdds);
	}
}
