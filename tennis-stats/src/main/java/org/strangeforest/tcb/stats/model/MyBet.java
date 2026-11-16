package org.strangeforest.tcb.stats.model;

import java.math.*;
import java.time.*;

import org.strangeforest.tcb.util.*;

/** A bet the site owner placed (My Bets). Odds are American (+150 / -120). */
public class MyBet implements Wager {

	public static final String PENDING = "PENDING", WON = "WON", LOST = "LOST", VOID = "VOID";

	private Integer id;
	private LocalDate placed;
	private String tournament;
	private String betType;
	private Integer playerId;
	private String playerName;
	private String playerCountryId;
	private Integer opponentId;
	private String opponentName;
	private String opponentCountryId;
	private String selection;
	private int odds;
	private BigDecimal stake;
	private String result;
	private LocalDate settled;
	private Double modelProbability; // 0..1, the site's prediction for the pick when the bet was saved
	private String notes;
	private boolean autoSettled;
	private Integer parlayId;
	private String eventName;
	private String eventLevel;
	private String eventUrl;
	private Double pointsStake; // the stake credited to the player for points (a parlay's stake is split between its legs)
	private Double points; // My Bets points this bet earned its player (see PlayerPoints)

	@Override public Integer getId() { return id; }
	public void setId(Integer id) { this.id = id; }

	@Override public LocalDate getPlaced() { return placed; }
	public void setPlaced(LocalDate placed) { this.placed = placed; }

	public String getTournament() { return tournament; }
	public void setTournament(String tournament) { this.tournament = tournament; }

	public String getBetType() { return betType; }
	public void setBetType(String betType) { this.betType = betType; }

	public Integer getPlayerId() { return playerId; }
	public void setPlayerId(Integer playerId) { this.playerId = playerId; }

	public String getPlayerName() { return playerName; }
	public void setPlayerName(String playerName) { this.playerName = playerName; }

	public String getPlayerCountryId() { return playerCountryId; }
	public void setPlayerCountryId(String playerCountryId) { this.playerCountryId = playerCountryId; }

	public Integer getOpponentId() { return opponentId; }
	public void setOpponentId(Integer opponentId) { this.opponentId = opponentId; }

	public String getOpponentName() { return opponentName; }
	public void setOpponentName(String opponentName) { this.opponentName = opponentName; }

	public String getOpponentCountryId() { return opponentCountryId; }
	public void setOpponentCountryId(String opponentCountryId) { this.opponentCountryId = opponentCountryId; }

	public String getSelection() { return selection; }
	public void setSelection(String selection) { this.selection = selection; }

	public int getOdds() { return odds; }
	public void setOdds(int odds) { this.odds = odds; }

	@Override public BigDecimal getStake() { return stake; }
	public void setStake(BigDecimal stake) { this.stake = stake; }

	@Override public String getResult() { return result; }
	public void setResult(String result) { this.result = result; }

	public LocalDate getSettled() { return settled; }
	public void setSettled(LocalDate settled) { this.settled = settled; }

	public Double getModelProbability() { return modelProbability; }
	public void setModelProbability(Double modelProbability) { this.modelProbability = modelProbability; }

	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }

	public boolean isAutoSettled() { return autoSettled; }
	public void setAutoSettled(boolean autoSettled) { this.autoSettled = autoSettled; }

	/** The parlay this bet is a leg of; a leg's own stake is kept (for ungrouping) but not counted. */
	public Integer getParlayId() { return parlayId; }
	public void setParlayId(Integer parlayId) { this.parlayId = parlayId; }

	@Override public boolean isParlay() { return false; }

	/** The tournament event this bet was detected to be in (from the players' match, else the typed name). */
	public void setEvent(String name, String level, String url) {
		this.eventName = name;
		this.eventLevel = level;
		this.eventUrl = url;
	}

	public String getEventName() { return eventName; }
	public String getEventLevel() { return eventLevel; }
	public String getEventUrl() { return eventUrl; }

	public String getTournamentText() {
		return tournament != null && !tournament.isBlank() ? tournament : eventName;
	}

	public boolean isLeg() { return parlayId != null; }

	public Double getPoints() { return points; }
	public void setPoints(Double points) { this.points = points; }

	public Double getPointsStake() { return pointsStake; }
	public void setPointsStake(Double pointsStake) { this.pointsStake = pointsStake; }


	// Derived

	public Country getPlayerCountry() {
		return playerCountryId != null ? new Country(playerCountryId) : null;
	}

	public Country getOpponentCountry() {
		return opponentCountryId != null ? new Country(opponentCountryId) : null;
	}

	/** Profit if the bet wins. */
	@Override public BigDecimal getToWin() {
		if (stake == null)
			return BigDecimal.ZERO;
		var multiplier = odds > 0 ? BigDecimal.valueOf(odds).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
		                          : BigDecimal.valueOf(100).divide(BigDecimal.valueOf(-odds), 6, RoundingMode.HALF_UP);
		return stake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
	}

	@Override public BigDecimal getProfit() {
		if (WON.equals(result))
			return getToWin();
		else if (LOST.equals(result))
			return stake.negate();
		else
			return BigDecimal.ZERO;
	}

	/** Win probability implied by the odds, in %. */
	public double getImpliedProbability() {
		return 100.0 * (odds > 0 ? 100.0 / (odds + 100.0) : -odds / (-odds + 100.0));
	}

	public Double getModelPercentage() {
		return modelProbability != null ? 100.0 * modelProbability : null;
	}

	/** Model probability minus implied probability, in percentage points. */
	public Double getEdge() {
		return modelProbability != null ? getModelPercentage() - getImpliedProbability() : null;
	}

	public String getOddsText() {
		return Wager.oddsText(odds);
	}

	public String getPickText() {
		if (selection != null && !selection.isBlank())
			return selection;
		return playerName;
	}
}
