# Updating data for 2025–2026+

The original UTS loader reads Jeff Sackmann's `tennis_atp` CSVs. That repository was removed from GitHub
in 2026, and the atptour.com scrapers UTS used to fill in recent weeks are blocked (Cloudflare, plus
robots.txt disallows `/scores/archive/*`). This fork uses:

| Source | Covers | Notes |
|---|---|---|
| [tennis-sackmann-archive](https://github.com/Aneeshers/tennis-sackmann-archive) | 1968 → Roland Garros 2026, rankings → 2026-06-08 | Full match stats. CC BY-NC-SA 4.0 (non-commercial). |
| ESPN public JSON API (`espn_to_sackmann.py`) | Anything after the archive | Results, rounds, seeds, scores only (see limits) |

## Pipeline

```
archive/atp/*.csv ──┐
                    ├─► espn_to_sackmann.py ─► ultimate-tennis-stats-data/merged/ ─► data-load (LoadATPTennis)
ESPN API ───────────┘
```

```bash
# Run from the repo root. ultimate-tennis-stats-data/ lives inside the repo and is gitignored.
# 1. Get the archive (once)
git clone --depth 1 --filter=blob:none --sparse https://github.com/Aneeshers/tennis-sackmann-archive \
    ultimate-tennis-stats-data/archive
git -C ultimate-tennis-stats-data/archive sparse-checkout set atp

# 2. Build the merged data dir (re-run any time; --refresh re-downloads recent events + rankings)
python3 tools/espn-import/espn_to_sackmann.py --refresh

# 3. Load into PostgreSQL (data-load defaults to the merged dir; override with TCB_DATA_DIR)
```

The script prints `CHECK:` lines for events it could not map from last season. Fix them in
`tournament_overrides.csv` (keyed by ESPN event name). Players ESPN knows but the archive does not get
id `900000 + espn_id`; map them to a real id in `player_overrides.csv` if they turn up later.

## How ESPN events are mapped

Each ESPN event of the previous season is paired with the Sackmann tournament in the same week with the
largest player overlap. The pairing is one-to-one, best overlap first. That gives `tourney_id`, name,
surface and level for the current season's events. Events missing last season fall back to a name match
across the previous 12 seasons, then to a guess.

## Limits of the ESPN data

- No serve/return stats, match duration, or entry type (Q/WC/LL) → those columns are empty.
- No per-match ATP rank (ESPN's `curatedRank` is the **seed**) → `winner_rank`/`loser_rank` empty.
- Rankings: only a current top-150 snapshot is available, appended for the current Monday each run.
  Weekly rankings between 2026-06-08 and the first snapshot are missing. Run weekly to keep history.
- No Davis Cup ties, and Challenger/qualifying matches are not imported.
- Only completed events are imported; in-progress events are picked up on the next run after they end.

## Tournament forecasts

`espn_in_progress.py` writes the draws of in-progress events (and those starting within 3 days) to
`merged/atp_in_progress.json`; `data-load -ip` loads them and computes the forecast. Round-robin events
(ATP Finals) only: groups are the connected group-stage pairings, and the forecast is a 100,000-run
Monte Carlo of the group stage, semi-finals and final using the app's match prediction model. Knockout
events are skipped because ESPN does not publish bracket positions.

## Loader flags added in this fork

- `-Dtcb.data.last-season=2026`: last season file to load (default: current year)
- `-Dtcb.data.scrape=true`: re-enable the legacy atptour.com/Wikipedia scrapers (likely broken)
