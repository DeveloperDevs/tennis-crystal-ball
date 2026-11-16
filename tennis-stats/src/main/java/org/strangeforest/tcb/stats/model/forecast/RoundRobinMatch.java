package org.strangeforest.tcb.stats.model.forecast;

import java.time.*;

import static org.strangeforest.tcb.stats.util.PercentageUtil.*;

public class RoundRobinMatch {

	private final LocalDate date;
	private final PlayerForecast player1;
	private final PlayerForecast player2;
	private final Double winProbability1; // raw 0..1, null when completed
	private final Integer winner;
	private final String score; // winner first
	private final int h2hWon1; // head-to-head before this event
	private final int h2hWon2;

	public RoundRobinMatch(LocalDate date, PlayerForecast player1, PlayerForecast player2, Double winProbability1, Integer winner, String score, int h2hWon1, int h2hWon2) {
		this.date = date;
		this.player1 = player1;
		this.player2 = player2;
		this.winProbability1 = winProbability1;
		this.winner = winner;
		this.score = score;
		this.h2hWon1 = h2hWon1;
		this.h2hWon2 = h2hWon2;
	}

	public int getH2hWon1() {
		return h2hWon1;
	}

	public int getH2hWon2() {
		return h2hWon2;
	}

	public LocalDate getDate() {
		return date;
	}

	public PlayerForecast getPlayer1() {
		return player1;
	}

	public PlayerForecast getPlayer2() {
		return player2;
	}

	public boolean isCompleted() {
		return winner != null;
	}

	public Integer getWinner() {
		return winner;
	}

	public PlayerForecast getWinnerPlayer() {
		return winner == null ? null : (winner == 1 ? player1 : player2);
	}

	public PlayerForecast getLoserPlayer() {
		return winner == null ? null : (winner == 1 ? player2 : player1);
	}

	public String getScore() {
		return score;
	}

	public Double getProbability1() {
		return winProbability1 != null ? PCT * winProbability1 : null;
	}

	public Double getProbability2() {
		return winProbability1 != null ? PCT * (1.0 - winProbability1) : null;
	}
}
