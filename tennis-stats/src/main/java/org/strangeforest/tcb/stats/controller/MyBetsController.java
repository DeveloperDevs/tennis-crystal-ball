package org.strangeforest.tcb.stats.controller;

import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import javax.servlet.http.*;

import org.springframework.beans.factory.annotation.*;
import org.springframework.http.*;
import org.springframework.stereotype.*;
import org.springframework.ui.*;
import org.springframework.web.bind.annotation.*;
import org.strangeforest.tcb.stats.jobs.*;
import org.strangeforest.tcb.stats.model.*;
import org.strangeforest.tcb.stats.service.*;

/**
 * My Bets: anyone can view the list; adding, editing and deleting needs the owner's login (HTTP Basic).
 * The login comes from tennis-stats.my-bets.username / password. With no password set, editing is allowed
 * only from this machine (local development).
 */
@Controller
public class MyBetsController extends PageController {

	private static final String REALM = "Basic realm=\"My Bets\"";

	@Autowired private MyBetsService myBetsService;
	@Autowired private ResultsUpdateJob resultsUpdateJob;

	@Value("${tennis-stats.my-bets.username:Admin}")
	private String username;

	@Value("${tennis-stats.my-bets.password:}")
	private String password;

	@GetMapping("/myBets")
	public String myBets(Model model) {
		var wagers = myBetsService.getWagers();
		model.addAttribute("wagers", wagers);
		model.addAttribute("summary", MyBetsService.summarize(wagers));
		model.addAttribute("playerPoints", myBetsService.getPlayerPoints(wagers));
		model.addAttribute("flatPoints", myBetsService.getFlatPoints());
		model.addAttribute("betTypes", MyBetsService.BET_TYPES);
		return "myBets";
	}

	/** Asks the browser for the login; the browser then sends it with every /myBets/... request. */
	@GetMapping("/myBets/login")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> login(HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		return ResponseEntity.ok(Map.of("editor", true));
	}

	/** Whether the current request carries a valid login, without asking for one. */
	@GetMapping("/myBets/status")
	@ResponseBody
	public Map<String, Object> status(HttpServletRequest request) {
		return Map.of("editor", canEdit(request));
	}

	@PostMapping(path = "/myBets/save", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> save(@RequestBody MyBet bet, HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		try {
			myBetsService.saveBet(bet);
			return ResponseEntity.ok(Map.of("ok", true));
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
		}
	}

	@PostMapping("/myBets/delete")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> delete(@RequestParam("betId") int betId, HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		myBetsService.deleteBet(betId);
		return ResponseEntity.ok(Map.of("ok", true));
	}


	@GetMapping("/myBets/events")
	@ResponseBody
	public List<Map<String, Object>> events(@RequestParam(name = "term", defaultValue = "") String term) {
		return myBetsService.autocompleteEvents(term);
	}

	/** Pulls the latest ESPN results now and settles bets (runs in the background; poll /myBets/refresh/status). */
	@PostMapping("/myBets/refresh")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		var started = resultsUpdateJob.start("manual");
		var status = new LinkedHashMap<>(resultsUpdateJob.getStatus());
		status.put("started", started);
		return ResponseEntity.ok(status);
	}

	@GetMapping("/myBets/refresh/status")
	@ResponseBody
	public Map<String, Object> refreshStatus() {
		return resultsUpdateJob.getStatus();
	}

	public static class ParlayRequest {
		public Integer id;
		public List<Integer> betIds;
		public int odds;
		public java.math.BigDecimal stake;
		public java.time.LocalDate placed;
		public String notes;
	}

	@PostMapping(path = "/myBets/parlay/save", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> saveParlay(@RequestBody ParlayRequest parlay, HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		try {
			myBetsService.saveParlay(parlay.id, parlay.betIds, parlay.odds, parlay.stake, parlay.placed, parlay.notes);
			return ResponseEntity.ok(Map.of("ok", true));
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
		}
	}

	@PostMapping("/myBets/parlay/ungroup")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> ungroupParlay(@RequestParam("parlayId") int parlayId, HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		myBetsService.ungroupParlay(parlayId);
		return ResponseEntity.ok(Map.of("ok", true));
	}

	@PostMapping("/myBets/parlay/delete")
	@ResponseBody
	public ResponseEntity<Map<String, Object>> deleteParlay(@RequestParam("parlayId") int parlayId, HttpServletRequest request) {
		if (!canEdit(request))
			return unauthorized();
		myBetsService.deleteParlay(parlayId);
		return ResponseEntity.ok(Map.of("ok", true));
	}


	// Login

	private boolean canEdit(HttpServletRequest request) {
		if (password == null || password.isEmpty())
			return isLocal(request);
		var header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6))
			return false;
		String credentials;
		try {
			credentials = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		var expected = username + ':' + password;
		return MessageDigest.isEqual(credentials.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isLocal(HttpServletRequest request) {
		try {
			return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

	private static <T> ResponseEntity<T> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, REALM).build();
	}
}
