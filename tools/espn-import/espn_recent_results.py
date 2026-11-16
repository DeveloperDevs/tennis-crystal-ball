#!/usr/bin/env python3
"""
Write the completed ATP men's singles matches of the last few days (any event, any round, qualifying included)
to a JSON file that the web app reads to settle My Bets. Players are mapped to Sackmann player ids, which the
database maps to its own ids (map_ext_player).

Usage:
  python3 espn_recent_results.py [--days 4] [--out ultimate-tennis-stats-data/merged/atp_recent_results.json]
"""
import argparse, datetime as dt, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from espn_to_sackmann import SITE, NOTE_RE, PlayerIndex, read_csv, split_tag, sackmann_score, name_key, ymd
from espn_in_progress import fetch_live

SINGLES = ('mens-singles', "Men's Singles")


def outcome_of(score):
	if score == 'W/O':
		return 'W/O'
	for tag in ('RET', 'DEF'):
		if score.endswith(' ' + tag):
			return tag
	return None


def day_matches(day, pidx):
	sb = fetch_live(f'{SITE}/scoreboard?dates={ymd(day)}')
	out = []
	for e in sb.get('events', []):
		for g in e.get('groupings', []):
			if g['grouping'].get('slug', g['grouping'].get('displayName')) not in SINGLES:
				continue
			for c in g['competitions']:
				if not c['status']['type'].get('completed'):
					continue
				comps = c['competitors']
				note = (c.get('notes') or [{}])[0].get('text', '')
				m = NOTE_RE.match(note)
				winner = next((x for x in comps if x.get('winner')), None)
				if not winner and m:  # the note is always winner-first
					winner = next((x for x in comps if name_key(x['athlete']['displayName']) == name_key(m['w'])), None)
				loser = next((x for x in comps if x is not winner), None)
				if not winner or not loser or not m:
					continue
				score = sackmann_score(m['score'])
				w_id = pidx.resolve(winner['id'], winner['athlete']['displayName'], m['wioc'])
				l_id = pidx.resolve(loser['id'], loser['athlete']['displayName'], m['lioc'])
				out.append({
					'espn_id': c['id'], 'date': c['date'][:10], 'tournament': e.get('name'),
					'round': c.get('round', {}).get('displayName', ''),
					'winner_ext_id': int(w_id) if w_id and str(w_id).isdigit() else None, 'winner_name': winner['athlete']['displayName'],
					'loser_ext_id': int(l_id) if l_id and str(l_id).isdigit() else None, 'loser_name': loser['athlete']['displayName'],
					'score': score, 'outcome': outcome_of(score),
				})
	return out


def main(args):
	today = dt.date.today()
	tools = os.path.dirname(os.path.abspath(__file__))
	merged = os.path.dirname(args.out)
	players = read_csv(os.path.join(merged, 'atp_players.csv'))
	recent = read_csv(os.path.join(merged, f'atp_matches_{today.year}.csv'))
	ov_path = os.path.join(tools, 'player_overrides.csv')
	overrides = {r['espn_id']: r['player_id'] for r in read_csv(ov_path)} if os.path.exists(ov_path) else {}
	pidx = PlayerIndex(players, recent, overrides)

	matches = {}
	for back in range(args.days, -1, -1):
		day = today - dt.timedelta(days=back)
		try:
			for m in day_matches(day, pidx):
				matches[m['espn_id']] = m
		except Exception as ex:  # one bad day must not lose the rest
			print(f'  {day}: {ex}', file=sys.stderr)
	results = sorted(matches.values(), key=lambda m: (m['date'], m['espn_id']))
	tmp = args.out + '.tmp'
	with open(tmp, 'w') as f:
		json.dump({'generated': dt.datetime.now().isoformat(timespec='seconds'), 'matches': results}, f, indent=1)
	os.replace(tmp, args.out)
	unmapped = sum(1 for m in results if not m['winner_ext_id'] or not m['loser_ext_id'])
	print(f'{len(results)} completed match(es) from the last {args.days} day(s) written to {args.out}' + (f' ({unmapped} with an unknown player)' if unmapped else ''))


if __name__ == '__main__':
	data_dir = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'ultimate-tennis-stats-data'))
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument('--days', type=int, default=4, help='days back from today to include')
	ap.add_argument('--out', default=os.path.join(data_dir, 'merged', 'atp_recent_results.json'))
	a = ap.parse_args()
	a.out = os.path.abspath(os.path.expanduser(a.out))
	main(a)
