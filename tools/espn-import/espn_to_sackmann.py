#!/usr/bin/env python3
"""
Fill the gap between the archived Sackmann ATP CSVs and today using ESPN's public tennis JSON API.

Produces a "merged" data directory the UTS data loader can read (point TCB_DATA_DIR at it):
  - every archive file, symlinked
  - atp_matches_<season>.csv   = archive rows + ESPN main-draw singles matches for events the archive lacks
  - atp_players.csv            = archive players + new players seen only in ESPN data
  - atp_rankings_current.csv   = archive rankings + ESPN ranking snapshot for the current week

Limits of ESPN data vs Sackmann: no serve/return stats, no match duration, no rank points per match,
no Davis Cup ties, and only a current rankings snapshot (top 150), not weekly history.

Usage:
  python3 espn_to_sackmann.py [--archive ultimate-tennis-stats-data/archive/atp] \
                              [--out ultimate-tennis-stats-data/merged] [--season 2026] [--refresh]
  (defaults are relative to the repo root, wherever it lives)
"""
import argparse, csv, datetime as dt, json, os, re, sys, time, unicodedata, urllib.request
from collections import Counter, defaultdict

CORE = 'https://sports.core.api.espn.com/v2/sports/tennis/leagues/atp'
SITE = 'https://site.api.espn.com/apis/site/v2/sports/tennis/atp'
UA = 'uts-data-loader/1.0 (personal tennis statistics project)'
NEW_PLAYER_ID_BASE = 900000  # Sackmann ids are < 300000; ESPN-only players get 900000 + espn id

MATCH_COLUMNS = ('tourney_id,tourney_name,surface,draw_size,tourney_level,tourney_date,match_num,'
	'winner_id,winner_seed,winner_entry,winner_name,winner_hand,winner_ht,winner_ioc,winner_age,'
	'loser_id,loser_seed,loser_entry,loser_name,loser_hand,loser_ht,loser_ioc,loser_age,'
	'score,best_of,round,minutes,w_ace,w_df,w_svpt,w_1stIn,w_1stWon,w_2ndWon,w_SvGms,w_bpSaved,w_bpFaced,'
	'l_ace,l_df,l_svpt,l_1stIn,l_1stWon,l_2ndWon,l_SvGms,l_bpSaved,l_bpFaced,'
	'winner_rank,winner_rank_points,loser_rank,loser_rank_points').split(',')
ROUND_ORDER = {'Final': 'F', 'Semifinal': 'SF', 'Quarterfinal': 'QF', 'Round Robin': 'RR', 'Bronze Medal Match': 'BR'}


# ---------- HTTP with disk cache ----------

class Fetcher:
	def __init__(self, cache_dir, refresh_after):
		self.cache_dir, self.refresh_after = cache_dir, refresh_after
		os.makedirs(cache_dir, exist_ok=True)

	def get(self, url, volatile=False):
		path = os.path.join(self.cache_dir, re.sub(r'[^A-Za-z0-9]+', '_', url)[-180:] + '.json')
		if os.path.exists(path) and not (volatile and self.refresh_after is not None and dt.date.fromtimestamp(os.path.getmtime(path)) < self.refresh_after):
			with open(path) as f:
				return json.load(f)
		for attempt in range(4):
			try:
				req = urllib.request.Request(url, headers={'User-Agent': UA})
				with urllib.request.urlopen(req, timeout=60) as r:
					data = json.load(r)
				break
			except Exception as e:
				if attempt == 3:
					raise
				print(f'  retry {url}: {e}', file=sys.stderr)
				time.sleep(2 ** attempt)
		with open(path, 'w') as f:
			json.dump(data, f)
		time.sleep(0.3)  # be polite
		return data


# ---------- helpers ----------

def norm(name):
	s = unicodedata.normalize('NFKD', name).encode('ascii', 'ignore').decode().lower()
	return re.sub(r'[^a-z ]', '', s.replace('-', ' ')).split()

def name_key(name):
	# order- and punctuation-insensitive: 'Zhang Zhizhen' == 'Zhizhen Zhang', 'J.J. Wolf' == 'J J Wolf'
	return ''.join(sorted(norm(name)))

def ymd(d):
	return d.strftime('%Y%m%d')

def parse_date(s):
	return dt.datetime.strptime(s[:10], '%Y-%m-%d').date()

def monday(d):
	return d - dt.timedelta(days=d.weekday())

def read_csv(path):
	with open(path, newline='', encoding='utf-8') as f:
		return list(csv.DictReader(f))


# ---------- ESPN parsing ----------

def season_events(fx, season):
	"""All ATP events of a season: id, name, start/end dates, major flag."""
	idx = fx.get(f'{CORE}/seasons/{season}/types/2/events?limit=500', volatile=True)
	events = []
	for item in idx.get('items', []):
		e = fx.get(item['$ref'].replace('http://', 'https://').replace('.pvt/', '.com/'))
		events.append({'id': e['id'], 'name': e['name'], 'start': parse_date(e['date']),
			'end': parse_date(e.get('endDate') or e['date']),
			'major': bool(e.get('major')) or bool(re.search(r'Australian Open|Roland Garros|French Open|Wimbledon|US Open', e['name']))})
	return sorted(events, key=lambda e: e['start'])

NOTE_RE = re.compile(r'^(?:\((?P<wtag>[^)]*)\)\s*)?(?P<w>.+?)\s*\((?P<wioc>[A-Z]{3})\)\s+(?:bt|leads|def\.?)\s+'
	r'(?:\((?P<ltag>[^)]*)\)\s*)?(?P<l>.+?)\s*\((?P<lioc>[A-Z]{3})\)\s*(?P<score>.*)$')

def split_tag(tag):
	"""'3' -> (3, ''), 'WC' -> ('', 'WC'), '1/Q' -> (1, 'Q')"""
	seed, entry = '', ''
	for part in re.split(r'[/,\s]+', tag or ''):
		if part.isdigit():
			seed = part
		elif part:
			entry = part.upper()
	return seed, entry

def sackmann_score(raw):
	"""'6-7 (7-9) 7-6 (18-16) 6-3 2-0 ret' -> '6-7(7) 7-6(16) 6-3 2-0 RET'"""
	raw = raw.strip()
	if re.fullmatch(r'(?i)w/?o', raw):
		return 'W/O'
	raw = re.sub(r'\((\d+)-(\d+)\)', lambda m: f'({min(int(m[1]), int(m[2]))})', raw)
	raw = re.sub(r'\s+\(', '(', raw)
	raw = re.sub(r'(?i)\s*\bret(ired)?\.?$', ' RET', raw)
	raw = re.sub(r'(?i)\s*\b(def|default)\.?$', ' DEF', raw)
	return raw.strip()

def event_matches(fx, event):
	"""Completed men's singles main-draw matches of an ESPN event."""
	# endDate is often a day past the final (UTC offset), so step back until the scoreboard includes the event
	found = None
	for back in range(0, 4):
		day = event['end'] - dt.timedelta(days=back)
		if day < event['start']:
			break
		sb = fx.get(f"{SITE}/scoreboard?dates={ymd(day)}", volatile=day >= dt.date.today() - dt.timedelta(days=3))
		found = next((e for e in sb.get('events', []) if e['id'] == event['id']), None)
		if found:
			break
	out = []
	for e in [found] if found else []:
		for g in e.get('groupings', []):
			if g['grouping'].get('slug', g['grouping']['displayName']) not in ('mens-singles', "Men's Singles"):
				continue
			for c in g['competitions']:
				rnd = c.get('round', {}).get('displayName', '')
				if rnd.startswith('Qualifying') or not c['status']['type'].get('completed'):
					continue
				comps = c['competitors']
				note = (c.get('notes') or [{}])[0].get('text', '')
				m = NOTE_RE.match(note)
				winner = next((x for x in comps if x.get('winner')), None)
				if not winner and m:  # winner flag is sometimes missing; the note is always winner-first
					winner = next((x for x in comps if name_key(x['athlete']['displayName']) == name_key(m['w'])), None)
				loser = next((x for x in comps if x is not winner), None)
				if not winner or not loser or not m:
					print(f"  skip unparsable match in {event['name']}: {note!r}", file=sys.stderr)
					continue
				ws, we = split_tag(m['wtag'])
				ls, le = split_tag(m['ltag'])
				out.append({'id': c['id'], 'date': parse_date(c['date']), 'round_name': rnd,
					'best_of': c.get('format', {}).get('regulation', {}).get('periods', 3),
					'score': sackmann_score(m['score']),
					# NB: ESPN 'curatedRank' is the seed, not the ATP ranking, so per-match rank is left empty
					'w': {'espn_id': winner['id'], 'name': winner['athlete']['displayName'], 'ioc': m['wioc'], 'seed': ws, 'entry': we},
					'l': {'espn_id': loser['id'], 'name': loser['athlete']['displayName'], 'ioc': m['lioc'], 'seed': ls, 'entry': le}})
	return out


def main_draw_start(matches):
	return min(m["date"] for m in matches)  # Sackmann tourney_date = first main-draw day


# ---------- tournament metadata (learned from the previous season) ----------

def tourney_groups(rows):
	groups = defaultdict(list)
	for r in rows:
		if r['tourney_level'] != 'D':
			groups[r['tourney_id']].append(r)
	return groups

def learn_tournaments(fx, prev_season, prev_rows):
	"""Pair each ESPN event of the previous season with the Sackmann tournament having most player overlap in the same weeks."""
	groups = tourney_groups(prev_rows)
	pairs = []
	for ev in season_events(fx, prev_season):
		matches = event_matches(fx, ev)
		if not matches:
			continue
		start = main_draw_start(matches)
		names = {name_key(p[s]['name']) for p in matches for s in 'wl'}
		for tid, rs in groups.items():
			d = dt.datetime.strptime(rs[0]['tourney_date'], '%Y%m%d').date()
			if abs((d - start).days) > 7:
				continue
			players = {name_key(r[s + '_name']) for r in rs for s in ('winner', 'loser')}
			score = len(names & players) / len(names | players)
			if score >= 0.3:
				pairs.append((score, ev['name'], tid))
	mapping, used = {}, set()
	for score, name, tid in sorted(pairs, reverse=True):  # greedy one-to-one assignment, best overlap first
		if name in mapping or tid in used:
			continue
		used.add(tid)
		r = groups[tid][0]
		mapping[name] = {k: r[k] for k in ('tourney_id', 'tourney_name', 'surface', 'tourney_level', 'draw_size')}
		mapping[name]['tourney_id'] = r['tourney_id'].split('-', 1)[1]
	return mapping

def historical_tournaments(archive, season, years=12):
	"""Latest metadata per Sackmann tourney_name across recent seasons, for events missing last season."""
	hist = {}
	for y in range(season - years, season - 1):
		path = os.path.join(archive, f'atp_matches_{y}.csv')
		if os.path.exists(path):
			for r in read_csv(path):
				if r['tourney_level'] in ('A', 'M', 'G', 'F'):
					meta = {k: r[k] for k in ('tourney_id', 'tourney_name', 'surface', 'tourney_level', 'draw_size')}
					meta['tourney_id'] = r['tourney_id'].split('-', 1)[1]
					hist[r['tourney_name']] = meta
	return hist

def match_historical(ev_name, hist):
	tokens = set(norm(ev_name))
	hits = [meta for tname, meta in hist.items() if norm(tname) and set(norm(tname)) <= tokens]
	return max(hits, key=lambda m: len(m['tourney_name'])) if hits else None

def guess_tournament(ev, draw_size):
	name = ev['name']
	level = 'G' if ev['major'] else 'F' if re.search(r'Finals', name) and 'Next' not in name else \
		'M' if re.search(r'Masters|1000', name) else 'A'
	return {'tourney_id': 'E' + ev['id'].split('-')[0], 'tourney_name': name, 'surface': 'Hard',
		'tourney_level': level, 'draw_size': str(draw_size)}


# ---------- players ----------

class PlayerIndex:
	def __init__(self, players, recent_rows, overrides):
		self.players = {p['player_id']: p for p in players}
		self.by_name = defaultdict(set)
		for p in players:
			self.by_name[name_key(f"{p['name_first']} {p['name_last']}")].add(p['player_id'])
		self.active = Counter(r[s + '_id'] for r in recent_rows for s in ('winner', 'loser'))
		self.by_last_ioc = defaultdict(set)
		for pid in self.active:
			p = self.players.get(pid)
			if p:
				self.by_last_ioc[(norm(p['name_last'])[-1] if norm(p['name_last']) else '', p['ioc'])].add(pid)
		self.overrides = overrides
		self.new_players = {}
		self.cache = {}

	def resolve(self, espn_id, name, ioc):
		if espn_id in self.overrides:
			return self.overrides[espn_id]
		if espn_id in self.cache:
			return self.cache[espn_id]
		pid = self._resolve(espn_id, name, ioc)
		if ioc:
			self.cache[espn_id] = pid
		return pid

	def _resolve(self, espn_id, name, ioc):
		cands = self.by_name.get(name_key(name), set())
		if len(cands) > 1:
			cands = {c for c in cands if self.players[c]['ioc'] == ioc} or cands
			cands = {max(cands, key=lambda c: self.active.get(c, 0))}
		if not cands:
			n = norm(name)
			cands = self.by_last_ioc.get((n[-1] if n else '', ioc), set()) if ioc else set()
			if len(cands) != 1:
				cands = set()
		if cands:
			return next(iter(cands))
		if not ioc:
			return None
		pid = str(NEW_PLAYER_ID_BASE + int(espn_id))
		if pid not in self.new_players:
			parts = name.split(' ', 1)
			self.new_players[pid] = {'player_id': pid, 'name_first': parts[0], 'name_last': parts[1] if len(parts) > 1 else '',
				'hand': 'U', 'dob': '', 'ioc': ioc, 'height': '', 'wikidata_id': ''}
			print(f'  new player (not in atp_players.csv): {name} ({ioc}) -> {pid}', file=sys.stderr)
		return pid

	def info(self, pid, on_date):
		p = self.players.get(pid) or self.new_players.get(pid, {})
		age = ''
		if p.get('dob') and len(p['dob']) == 8:
			born = dt.datetime.strptime(p['dob'], '%Y%m%d').date()
			age = f'{(on_date - born).days / 365.25:.1f}'
		return {'hand': p.get('hand', 'U') or 'U', 'ht': p.get('height', ''), 'age': age}


# ---------- main ----------

def round_code(name, draw_size):
	if name in ROUND_ORDER:
		return ROUND_ORDER[name]
	m = re.match(r'Round (\d+)', name)
	if m:
		draw = 1
		while draw < int(draw_size):  # 96 -> 128, 48 -> 64, 28 -> 32 (byes)
			draw *= 2
		return f'R{draw >> (int(m[1]) - 1)}'
	return name

def build(args):
	season = args.season
	fx = Fetcher(os.path.join(args.out, '.espn-cache'), dt.date.today() if args.refresh else None)
	archive = os.path.abspath(os.path.expanduser(args.archive))
	cur_rows = read_csv(os.path.join(archive, f'atp_matches_{season}.csv'))
	prev_rows = read_csv(os.path.join(archive, f'atp_matches_{season - 1}.csv'))
	players = read_csv(os.path.join(archive, 'atp_players.csv'))
	overrides_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'player_overrides.csv')
	overrides = {r['espn_id']: r['player_id'] for r in read_csv(overrides_path)} if os.path.exists(overrides_path) else {}
	pidx = PlayerIndex(players, prev_rows + cur_rows, overrides)

	print(f'Learning tournament metadata from {season - 1} ...')
	tmap = learn_tournaments(fx, season - 1, prev_rows)
	print(f'  mapped {len(tmap)} ESPN events to Sackmann tournaments')
	hist = historical_tournaments(archive, season)
	tov_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tournament_overrides.csv')
	tov = {r['espn_name']: r for r in read_csv(tov_path)} if os.path.exists(tov_path) else {}

	have = {r['tourney_id'].split('-', 1)[1] for r in cur_rows}
	last_archive_date = max(dt.datetime.strptime(r['tourney_date'], '%Y%m%d').date() for r in cur_rows if r['tourney_level'] != 'D')
	new_rows, report = [], []
	for ev in season_events(fx, season):
		if ev['end'] > dt.date.today() or ev['start'] <= last_archive_date:
			continue
		matches = event_matches(fx, ev)
		if not matches:
			continue
		rounds = Counter(m['round_name'] for m in matches)
		r1 = rounds.get('Round 1', 0) or len(matches) // 2
		source = 'last season'
		meta = tmap.get(ev['name'])
		if not meta:
			meta, source = match_historical(ev['name'], hist), 'history'
		if not meta:
			meta, source = guess_tournament(ev, 0), 'GUESSED'
		meta = dict(meta)
		if ev['name'] in tov:
			meta.update({k: v for k, v in tov[ev['name']].items() if k != 'espn_name' and v})
			source = 'override'
		if meta['tourney_id'] in have:
			print(f"  CHECK: '{ev['name']}' maps to {meta['tourney_name']} which is already loaded; skipped (fix in tournament_overrides.csv)", file=sys.stderr)
			continue
		have.add(meta['tourney_id'])
		if source != 'last season':
			draw = 1
			while draw < 2 * r1:
				draw *= 2
			meta['draw_size'] = str(draw)
			if source != 'override':
				print(f"  CHECK: '{ev['name']}' not in last season -> {source}: {meta['tourney_name']} level={meta['tourney_level']} surface={meta['surface']} (fix in tournament_overrides.csv)", file=sys.stderr)
		# Bracket size from the rounds actually played (handles draw size changes year to year)
		n_rounds = max([int(x.split()[1]) for x in rounds if re.fullmatch(r'Round \d+', x)] or [0])
		bracket = 2 ** (n_rounds + 3) if n_rounds else int(meta['draw_size'] or 32)
		learned = 1
		while learned < int(meta['draw_size'] or 0):
			learned *= 2
		if learned != bracket:
			meta['draw_size'] = str(len(matches) + 1)
		tdate = ymd(main_draw_start(matches))
		order = {r: i for i, r in enumerate(['Round 1', 'Round 2', 'Round 3', 'Round 4', 'Round Robin', 'Quarterfinal', 'Semifinal', 'Bronze Medal Match', 'Final'])}
		matches.sort(key=lambda m: (order.get(m['round_name'], 0), m['date'], m['id']))
		for i, m in enumerate(matches, 1):
			row = dict.fromkeys(MATCH_COLUMNS, '')
			row.update({'tourney_id': f"{season}-{meta['tourney_id']}", 'tourney_name': meta['tourney_name'],
				'surface': meta['surface'], 'draw_size': meta['draw_size'], 'tourney_level': meta['tourney_level'],
				'tourney_date': tdate, 'match_num': str(i), 'score': m['score'],
				'best_of': '5' if meta['tourney_level'] == 'G' else '3',  # ESPN reports 5 periods for Masters too
				'round': round_code(m['round_name'], bracket)})
			for side, prefix, short in (('w', 'winner', 'winner'), ('l', 'loser', 'loser')):
				p = m[side]
				pid = pidx.resolve(p['espn_id'], p['name'], p['ioc'])
				info = pidx.info(pid, m['date'])
				row.update({f'{prefix}_id': pid, f'{prefix}_seed': p['seed'], f'{prefix}_entry': p['entry'],
					f'{prefix}_name': p['name'], f'{prefix}_hand': info['hand'], f'{prefix}_ht': info['ht'],
					f'{prefix}_ioc': p['ioc'], f'{prefix}_age': info['age']})
			new_rows.append(row)
		report.append(f"{tdate} {meta['tourney_name']:<20} {meta['tourney_level']} {meta['surface']:<5} {len(matches):>4}  <- {ev['name']} [{source}]")

	os.makedirs(args.out, exist_ok=True)
	for f in os.listdir(archive):
		dst = os.path.join(args.out, f)
		if not f.endswith('.csv'):
			continue
		# Relative links, so the data dir keeps working if the repo is moved
		target = os.path.relpath(os.path.join(archive, f), args.out)
		if os.path.islink(dst) and os.readlink(dst) != target:
			os.remove(dst)  # stale or absolute link from an earlier layout
		if not os.path.lexists(dst):
			os.symlink(target, dst)

	def write(name, columns, rows):
		dst = os.path.join(args.out, name)
		if os.path.islink(dst):
			os.remove(dst)
		with open(dst, 'w', newline='', encoding='utf-8') as f:
			w = csv.DictWriter(f, fieldnames=columns, extrasaction='ignore')
			w.writeheader()
			w.writerows(rows)

	write(f'atp_matches_{season}.csv', MATCH_COLUMNS, cur_rows + new_rows)
	write('atp_players.csv', list(players[0].keys()), players + list(pidx.new_players.values()))

	# Current-week ranking snapshot
	rk_rows = read_csv(os.path.join(archive, 'atp_rankings_current.csv'))
	rk = fx.get(f'{SITE}/rankings', volatile=True)['rankings'][0]
	rdate = ymd(monday(dt.date.today()))
	if rdate > max(r['ranking_date'] for r in rk_rows):
		for r in rk['ranks']:
			a = r['athlete']
			pid = pidx.resolve(a['id'], a['displayName'], None)
			if not pid:
				print(f"  ranking: unmatched player {a['displayName']} (#{r['current']}), skipped", file=sys.stderr)
				continue
			rk_rows.append({'ranking_date': rdate, 'rank': str(r['current']), 'player': pid, 'points': str(int(r.get('points') or 0))})
	write('atp_rankings_current.csv', list(rk_rows[0].keys()), rk_rows)

	print('\nAdded from ESPN:')
	print('\n'.join(report) or '  (nothing new)')
	print(f'\n{len(new_rows)} matches, {len(pidx.new_players)} new players, rankings snapshot {rdate}')
	print(f'Merged data directory: {args.out}')


if __name__ == '__main__':
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	data_dir = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'ultimate-tennis-stats-data'))
	ap.add_argument('--archive', default=os.path.join(data_dir, 'archive', 'atp'))
	ap.add_argument('--out', default=os.path.join(data_dir, 'merged'))
	ap.add_argument('--season', type=int, default=dt.date.today().year)
	ap.add_argument('--refresh', action='store_true', help='re-download season index, recent events and rankings')
	a = ap.parse_args()
	a.out = os.path.abspath(os.path.expanduser(a.out))
	build(a)
