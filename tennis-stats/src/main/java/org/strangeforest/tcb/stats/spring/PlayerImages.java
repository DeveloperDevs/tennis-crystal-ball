package org.strangeforest.tcb.stats.spring;

import java.io.*;
import java.nio.file.*;
import java.text.*;
import java.util.*;
import java.util.stream.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;

/**
 * Player photos dropped into a local folder (e.g. {@code Alcaraz.png}, {@code Felix Auger-Aliassime.jpg}), served at {@code /images/players/<file>}.
 * The file name (without extension) is matched against a player's full name, last two words or last word, ignoring case, accents and punctuation.
 * The folder is rescanned at most every few seconds, so new images show up without a restart.
 */
@Component("playerImages")
public class PlayerImages {

	public static final String URL_PREFIX = "/images/players/";

	private static final Logger LOGGER = LoggerFactory.getLogger(PlayerImages.class);
	private static final long CHECK_INTERVAL_MILLIS = 5000L;
	private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

	private final Path dir;
	private volatile Map<String, String> images = Map.of();
	private volatile long lastCheck;

	public PlayerImages(@Value("${tennis-stats.player-images-dir:../ultimate-tennis-stats-data/player-images}") String dir) {
		this.dir = Paths.get(dir).toAbsolutePath().normalize();
		LOGGER.info("Player images directory: {}", this.dir);
	}

	public Path getDir() {
		return dir;
	}

	/** Normalized name -> image URL, for the page to match player names against. */
	public Map<String, String> getImages() {
		var now = System.currentTimeMillis();
		if (now - lastCheck >= CHECK_INTERVAL_MILLIS) {
			lastCheck = now;
			rescan();
		}
		return images;
	}

	private synchronized void rescan() {
		if (!Files.isDirectory(dir)) {
			images = Map.of();
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			var found = new TreeMap<String, String>();
			files.filter(Files::isRegularFile).forEach(file -> {
				var name = file.getFileName().toString();
				var dot = name.lastIndexOf('.');
				if (dot <= 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase()))
					return;
				var key = normalize(name.substring(0, dot));
				if (!key.isEmpty()) {
					long version;
					try {
						version = Files.getLastModifiedTime(file).toMillis() / 1000L;
					}
					catch (IOException ex) {
						version = 0L;
					}
					found.put(key, URL_PREFIX + name.replace(" ", "%20") + "?v=" + version);
				}
			});
			images = Collections.unmodifiableMap(found);
		}
		catch (IOException ex) {
			LOGGER.warn("Cannot read player images directory {}: {}", dir, ex.toString());
		}
	}

	public static String normalize(String name) {
		return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z]", "");
	}
}
