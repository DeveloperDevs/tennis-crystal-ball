package org.strangeforest.tcb.stats.service;

import java.math.*;
import java.util.*;

import org.strangeforest.tcb.stats.model.*;

import static org.strangeforest.tcb.stats.model.MyBet.*;


/** Totals over the My Bets list; a parlay counts as one bet and its legs not at all. */
public class MyBetsSummary {

	private final int bets, won, lost, voided, pending;
	private final BigDecimal staked;      // settled (won or lost) bets only
	private final BigDecimal profit;
	private final BigDecimal pendingStake;
	private final BigDecimal pendingToWin;

	public MyBetsSummary(List<? extends Wager> list) {
		int w = 0, l = 0, v = 0, p = 0;
		var staked = BigDecimal.ZERO;
		var profit = BigDecimal.ZERO;
		var pendingStake = BigDecimal.ZERO;
		var pendingToWin = BigDecimal.ZERO;
		for (var bet : list) {
			switch (bet.getResult()) {
				case WON: w++; staked = staked.add(bet.getStake()); break;
				case LOST: l++; staked = staked.add(bet.getStake()); break;
				case VOID: v++; break;
				default: p++; pendingStake = pendingStake.add(bet.getStake()); pendingToWin = pendingToWin.add(bet.getToWin()); break;
			}
			profit = profit.add(bet.getProfit());
		}
		this.bets = list.size();
		this.won = w; this.lost = l; this.voided = v; this.pending = p;
		this.staked = staked; this.profit = profit; this.pendingStake = pendingStake; this.pendingToWin = pendingToWin;
	}

	public int getBets() { return bets; }
	public int getWon() { return won; }
	public int getLost() { return lost; }
	public int getVoided() { return voided; }
	public int getPending() { return pending; }
	public BigDecimal getStaked() { return staked; }
	public BigDecimal getProfit() { return profit; }
	public BigDecimal getPendingStake() { return pendingStake; }
	public BigDecimal getPendingToWin() { return pendingToWin; }

	public Double getWinPct() {
		return won + lost > 0 ? 100.0 * won / (won + lost) : null;
	}

	/** Profit as % of the amount staked on settled bets. */
	public Double getRoi() {
		return staked.signum() > 0 ? 100.0 * profit.doubleValue() / staked.doubleValue() : null;
	}
}
