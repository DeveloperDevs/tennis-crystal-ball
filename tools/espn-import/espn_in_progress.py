#!/usr/bin/env python3
"""
Write the draws of ATP events that are in progress (or start within a few days) to a JSON file that the
data loader's in-progress step (data-load -ip) turns into tournament forecasts.

Only round-robin events (ATP Finals) are written. ESPN lists knockout matches without bracket positions,
so a knockout draw cannot be rebuilt from it; those events are reported and skipped.

Usage:
  python3 espn_in_progress.py [--out ultimate-tennis-stats-data/merged/atp_in_progress.json] [--days-ahead 3]
"""
import argparse, datetime as dt, json, os, sys, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from espn_to_sackmann import (SITE, UA, NOTE_RE, Fetcher, PlayerIndex, read_csv, season_events, learn_tournaments,
	historical_tournaments, match_historical, guess_tournament, split_tag, sackmann_score, name_key, ymd, parse_date)

RR_ROUNDS = {'Group Stage', 'Round Robin'}
KO_ROUNDS = {'Semifinal': 'SF', 'Final': 'F'}


def fetch_live(url):
	"""Uncached GET: in-progress draws change during the day."""
	req = urllib.request.Request(url, headers={'User-Agent': UA})
	with urllib.request.urlopen(req, timeout=60) as r:
		return json.load(r)


def player(comp, pidx, players):
	a = comp.get('athlete') or {}
	name = a.get('displayName')
	if not name or name.upper() == 'TBD':
		return None
	seed = (comp.get('curatedRank') or {}).get('current')
	pid = pidx.resolve(comp['id'], name, None)
	p = players.get(pid) if pid else None
	db_name = f"{p['name_first']} {p['name_last']}".strip() if p else name  # the name the DB knows (find_player)
	return {'id': pid, 'name': db_name, 'espn_name': name, 'seed': seed if seed and seed <= 32 else None}


def groups_of(rr_matches):
	"""Round-robin groups = connected components of the group-stage pairings."""
	parent = {}
	def find(x):
		while parent.setdefault(x, x) != x:
			x = parent[x]
		return x
	for m in rr_matches:
		if m['p1'] and m['p2']:
			parent[find(m['p1']['name'])] = find(m['p2']['name'])
	comps = {}
	for x in list(parent):
		comps.setdefault(find(x), []).append(x)
	return list(comps.values())


def build_event(ev, espn_event, meta, pidx, players):
	singles = next((g for g in espn_event.get('groupings', []) if g['grouping'].get('slug') == 'mens-singles'), None)
	if not singles:
		return None, 'no singles draw'
	rr, ko = [], []
	for c in singles['competitions']:
		rnd = c.get('round', {}).get('displayName', '')
		if rnd.startswith('Qualifying'):
			continue
		comps = sorted(c['competitors'], key=lambda x: x.get('order', 0))
		p1, p2 = (player(comps[0], pidx, players), player(comps[1], pidx, players)) if len(comps) == 2 else (None, None)
		m = {'espn_id': c['id'], 'date': c['date'][:10], 'p1': p1, 'p2': p2, 'winner': None, 'score': None,
			'status': c['status']['type']['state']}
		if c['status']['type'].get('completed') and p1 and p2:
			win = next((i for i, x in enumerate(comps, 1) if x.get('winner')), None)
			note = NOTE_RE.match((c.get('notes') or [{}])[0].get('text', ''))
			if not win and note:  # the note is always winner-first
				win = 1 if name_key(comps[0]['athlete']['displayName']) == name_key(note['w']) else 2
			m['winner'] = win
			m['score'] = sackmann_score(note['score']) if note else None
		if rnd in RR_ROUNDS:
			m['round'] = 'RR'
			rr.append(m)
		elif rnd in KO_ROUNDS:
			m['round'] = KO_ROUNDS[rnd]
			ko.append(m)
		else:
			return None, f'knockout draw (round {rnd!r}); ESPN has no bracket positions'
	if not rr:
		return None, 'knockout draw; ESPN has no bracket positions'

	# Order groups by their best seed, players inside a group by seed
	seeds = {p['name']: p['seed'] or 99 for m in rr for p in (m['p1'], m['p2']) if p}
	groups = sorted((sorted(g, key=lambda n: seeds.get(n, 99)) for g in groups_of(rr)), key=lambda g: seeds.get(g[0], 99))
	group_of = {n: i for i, g in enumerate(groups) for n in g}
	rr.sort(key=lambda m: (group_of.get(m['p1']['name'] if m['p1'] else '', 9), m['date'], int(m['espn_id'])))
	ko.sort(key=lambda m: (['SF', 'F'].index(m['round']), int(m['espn_id'])))
	matches = rr + ko
	for i, m in enumerate(matches, 1):
		m['match_num'] = i
	return {
		'espn_id': ev['id'], 'espn_name': ev['name'], 'ext_tournament_id': meta['ext_id'], 'name': meta['tourney_name'],
		'level': meta['tourney_level'], 'surface': meta['surface'][:1] if meta['surface'] else None,
		'draw_type': 'RR', 'draw_size': len(seeds), 'date': min(m['date'] for m in matches),
		'best_of': 5 if meta['tourney_level'] == 'G' else 3,
		'groups': groups, 'matches': matches,
	}, None


def main(args):
	today = dt.date.today()
	season = today.year
	tools = os.path.dirname(os.path.abspath(__file__))
	merged = os.path.dirname(args.out)
	fx = Fetcher(os.path.join(merged, '.espn-cache'), today)
	players_rows = read_csv(os.path.join(merged, 'atp_players.csv'))
	players = {p['player_id']: p for p in players_rows}
	recent = read_csv(os.path.join(merged, f'atp_matches_{season}.csv'))
	prev = read_csv(os.path.join(args.archive, f'atp_matches_{season - 1}.csv'))
	ov_path = os.path.join(tools, 'player_overrides.csv')
	overrides = {r['espn_id']: r['player_id'] for r in read_csv(ov_path)} if os.path.exists(ov_path) else {}
	pidx = PlayerIndex(players_rows, prev + recent, overrides)
	tmap = learn_tournaments(fx, season - 1, prev)
	hist = historical_tournaments(args.archive, season)
	tov_path = os.path.join(tools, 'tournament_overrides.csv')
	tov = {r['espn_name']: r for r in read_csv(tov_path)} if os.path.exists(tov_path) else {}

	events = []
	for ev in season_events(fx, season):
		if not (ev['start'] <= today + dt.timedelta(days=args.days_ahead) and ev['end'] >= today):
			continue
		meta = dict(tmap.get(ev['name']) or match_historical(ev['name'], hist) or guess_tournament(ev, 0))
		meta.update({k: v for k, v in tov.get(ev['name'], {}).items() if k != 'espn_name' and v})
		tid = meta['tourney_id']
		meta['ext_id'] = str(int(tid)) if tid.isdigit() else tid  # the DB maps '0605' as '605'
		espn_event = None
		day = max(ev['start'], today)
		while not espn_event and day <= min(ev['end'], today + dt.timedelta(days=args.days_ahead + 1)):
			sb = fetch_live(f'{SITE}/scoreboard?dates={ymd(day)}')
			espn_event = next((e for e in sb.get('events', []) if e['id'] == ev['id']), None)
			day += dt.timedelta(days=1)
		if not espn_event:
			print(f"  {ev['name']}: not on the ESPN scoreboard yet")
			continue
		# NB: the event-level state is 'post' whenever the day's matches are all done, not only after the final,
		# so it is no sign that the event is over; the event's end date (filtered above) decides that.
		event, why = build_event(ev, espn_event, meta, pidx, players)
		if not event:
			print(f"  skip {ev['name']}: {why}")
			continue
		done = sum(1 for m in event['matches'] if m['winner'])
		print(f"  {event['name']} ({ev['name']}): {len(event['groups'])} groups, {len(event['matches'])} matches, {done} completed")
		for g in event['groups']:
			print('    group: ' + ', '.join(g))
		events.append(event)

	with open(args.out, 'w') as f:
		json.dump({'generated': dt.datetime.now().isoformat(timespec='seconds'), 'events': events}, f, indent=1)
	print(f'{len(events)} in-progress event(s) written to {args.out}')


if __name__ == '__main__':
	data_dir = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'ultimate-tennis-stats-data'))
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument('--archive', default=os.path.join(data_dir, 'archive', 'atp'))
	ap.add_argument('--out', default=os.path.join(data_dir, 'merged', 'atp_in_progress.json'))
	ap.add_argument('--days-ahead', type=int, default=3, help='also include events starting within this many days')
	a = ap.parse_args()
	a.out = os.path.abspath(os.path.expanduser(a.out))
	main(a)
