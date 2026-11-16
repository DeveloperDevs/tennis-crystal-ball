package org.strangeforest.tcb.stats.spring;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;

import static java.util.stream.Collectors.*;

/**
 * Menu features switched off for a simpler local UI ("hidden", not removed: pages stay reachable by URL).
 * Reads one feature id per line from a plain text file ('#' starts a comment); edits apply on the next page load.
 * Templates ask {@code ${@hiddenFeatures.isHidden('goatList')}} or {@code ${@hiddenFeatures.allHidden('a', 'b')}}.
 */
@Component("hiddenFeatures")
public class HiddenFeatures {

	private static final Logger LOGGER = LoggerFactory.getLogger(HiddenFeatures.class);
	private static final long CHECK_INTERVAL_MILLIS = 1000L;

	private final Path file;
	private volatile Set<String> hidden = Set.of();
	private volatile long lastModified = Long.MIN_VALUE;
	private volatile long lastCheck;

	public HiddenFeatures(@Value("${tennis-stats.hidden-features-file:../ultimate-tennis-stats-data/hidden-features.txt}") String file) {
		this.file = Paths.get(file).toAbsolutePath().normalize();
		LOGGER.info("Hidden features file: {}", this.file);
	}

	public boolean isHidden(String feature) {
		return current().contains(feature);
	}

	public boolean allHidden(String... features) {
		var current = current();
		return Arrays.stream(features).allMatch(current::contains);
	}

	private Set<String> current() {
		var now = System.currentTimeMillis();
		if (now - lastCheck >= CHECK_INTERVAL_MILLIS) {
			lastCheck = now;
			reloadIfChanged();
		}
		return hidden;
	}

	private synchronized void reloadIfChanged() {
		try {
			var modified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
			if (modified == lastModified)
				return;
			lastModified = modified;
			hidden = modified < 0L ? Set.of() : Files.readAllLines(file).stream()
				.map(line -> line.replaceFirst("#.*", "").trim())
				.filter(line -> !line.isEmpty())
				.collect(toUnmodifiableSet());
			LOGGER.info("Hidden features: {}", hidden.isEmpty() ? "none" : new TreeSet<>(hidden));
		}
		catch (IOException ex) {
			LOGGER.warn("Cannot read hidden features file {}: {}", file, ex.getMessage());
		}
	}
}
