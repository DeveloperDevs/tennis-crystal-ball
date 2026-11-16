#!/bin/bash
# Pulls the latest results from ESPN for My Bets and tournament forecasts. Run by the web app every 30 minutes
# (tennis-stats.results-update.cron) and by the Refresh button on My Bets.
#   1. recent completed matches (last 4 days)  -> merged/atp_recent_results.json (the app loads it into bets.espn_result)
#   2. in-progress draws (round-robin events)   -> merged/atp_in_progress.json, then data-load -ip when UTS_DATA_LOAD is set
# Environment: UTS_DATA_DIR (default: the repo's ultimate-tennis-stats-data), UTS_DATA_LOAD (data-load command, optional).
cd "$(dirname "$0")" || exit 2
DATA=${UTS_DATA_DIR:-../../ultimate-tennis-stats-data}
status=0

echo "== Recent results"
python3 espn_recent_results.py --out "$DATA/merged/atp_recent_results.json" || status=1

echo "== In-progress events"
if python3 espn_in_progress.py --out "$DATA/merged/atp_in_progress.json" --archive "$DATA/archive/atp"; then
	if [ -n "$UTS_DATA_LOAD" ]; then
		$UTS_DATA_LOAD -ip -c 1 || status=1
	else
		echo "UTS_DATA_LOAD not set; skipping the in-progress forecast load"
	fi
else
	status=1
fi
exit $status
