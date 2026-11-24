import json
import os
import shlex
import re
import time
import datetime
import urllib.error
import urllib.request
import urllib.parse
import http.cookiejar
import collections
import threading


def decluttarr_event(line, dry_run=None):
    """Only forward action records, never arbitrary logs (which may contain secrets)."""
    match = re.search(r"\| INFO\s+\| Job '([a-z_]+)' triggered (.+)$", line)
    if not match:
        return None
    job, action = match.groups()
    if not (action.startswith(('removal:', 'queue removal (torrent kept):',
                               'obsolete-tagging:', 'a search for '))):
        return None
    mode = ('DRY RUN — no changes' if dry_run is True else
            'action reported' if dry_run is False else 'action reported — mode unknown')
    reasons = {'remove_metadata_missing': 'Metadata unavailable',
               'remove_stalled': 'Stalled download',
               'remove_failed_downloads': 'Failed download'}
    return ('Decluttarr — ' + mode + '\n' + reasons.get(job, job) + '\n' + action[:1500]
            + '\n\nLog report, not confirmation of success or a replacement download.')


def decluttarr_notifications():
    """Tail bounded batches with a persistent cursor; skip history on first start."""
    path = '/decluttarr-logs/logs.txt'
    cursor_path = '/state/decluttarr-notifications.json'
    try:
        with open(cursor_path) as handle:
            cursor = json.load(handle)
    except (OSError, ValueError):
        cursor = None
    while True:
        try:
            with open(path, 'rb') as handle:
                stat = os.fstat(handle.fileno())
                identity = [stat.st_dev, stat.st_ino]
                if cursor is None:
                    cursor = {'identity': identity, 'offset': stat.st_size}
                elif cursor.get('identity') != identity or cursor['offset'] > stat.st_size:
                    cursor = {'identity': identity, 'offset': 0}
                handle.seek(cursor['offset'])
                messages = []
                dry_run = None
                try:
                    with open('/decluttarr-config.yaml') as config:
                        mode = re.search(r'(?m)^\s+test_run:\s*(true|false)\s*$', config.read())
                        if mode:
                            dry_run = mode[1] == 'true'
                except OSError:
                    pass
                for _ in range(1000):
                    position = handle.tell()
                    line = handle.readline(65536)
                    if not line or not line.endswith(b'\n'):
                        handle.seek(position)
                        break
                    event = decluttarr_event(line.decode('utf-8', errors='replace'), dry_run)
                    if event:
                        messages.append(event)
                next_cursor = {'identity': identity, 'offset': handle.tell()}
                for user in ALLOWED:
                    if messages:
                        send(user, '\n\n'.join(messages))
                temporary = cursor_path + '.tmp'
                with open(temporary, 'w') as handle:
                    json.dump(next_cursor, handle)
                os.replace(temporary, cursor_path)
                cursor = next_cursor
        except FileNotFoundError:
            pass  # Logs are optional until the mounts are deployed.
        except Exception:
            # Never print errors/URLs that could contain API keys or bot tokens.
            print('Decluttarr notifications unavailable; retrying', flush=True)
        time.sleep(30 * 60)


def secret(name):
    with open('/secrets/' + name) as handle:
        return handle.read().strip()


TOKEN = secret('telegram-bot-token')
with open('/secrets/telegram-access.json') as handle:
    ALLOWED = set(json.load(handle)['allowed_user_ids'])


def request(url, body=None, headers=None, timeout=40):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers={
        'Content-Type': 'application/json', **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)


def telegram(method, body):
    result = request('https://api.telegram.org/bot' + TOKEN + '/' + method, body)
    if not result.get('ok'):
        raise RuntimeError('Telegram request failed')
    return result['result']






def save(state):
    temporary = '/state/bridge.json.tmp'
    with open(temporary, 'w') as handle:
        json.dump(state, handle)
    os.replace(temporary, '/state/bridge.json')




def send(chat, text):
    for start in range(0, len(text), 4000):
        telegram('sendMessage', {'chat_id': chat, 'text': text[start:start + 4000]})


def send_status(chat, text, command):
    if len(text) > 4000:
        send(chat, text)
        text = 'Refresh this status:'
    telegram('sendMessage', {
        'chat_id': chat, 'text': text,
        'reply_markup': {'inline_keyboard': [[{
            'text': '↻ Refresh', 'callback_data': command,
        }]]},
    })


def direct_tool(name, arguments=None):
    result = request('http://127.0.0.1:4097/mcp', {
        'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
        'params': {'name': name, 'arguments': arguments or {}},
    }, {'Authorization': 'Bearer ' + secret('nas-tools-token')}, timeout=45)
    body = result.get('result', {})
    if result.get('error') or body.get('isError'):
        raise RuntimeError('Status service failed')
    for part in body.get('content', []):
        if part.get('type') == 'text':
            return json.loads(part['text'])
    raise RuntimeError('Missing status result')


def size(value):
    value = float(value or 0)
    for unit in ['B', 'KiB', 'MiB', 'GiB', 'TiB']:
        if value < 1024 or unit == 'TiB':
            return f'{value:.1f} {unit}'
        value /= 1024


COMMANDS = [
    ('shows', 'Show downloads and import issues (Sonarr)'),
    ('films', 'Film downloads and import issues (Radarr)'),
    ('qbittorrent', 'Torrent states, speeds and blocked downloads'),
    ('transcoding', 'Transcoding workers and queues (Tdarr)'),
    ('nas', 'NAS status'), ('disk', 'Disk activity: /disk activity'),
    ('seerr', 'Media requests and search'), ('music', 'Music search and downloads'),
    ('home', 'Home Assistant status'), ('refresh', 'Refresh Telegram command menu'),
    ('help', 'Show commands and examples'),
]


def refresh_commands():
    for user in ALLOWED:
        telegram('setMyCommands', {
            'scope': {'type': 'chat', 'chat_id': user},
            'commands': [{'command': name, 'description': description}
                         for name, description in COMMANDS],
        })


def qbittorrent_status():
    with open('/secrets/qbittorrent.json') as handle:
        config = json.load(handle)
    base = config['url'].rstrip('/')
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    credentials = urllib.parse.urlencode({
        'username': config['username'], 'password': config['password'],
    }).encode()
    with opener.open(urllib.request.Request(base + '/api/v2/auth/login',
                     data=credentials, headers={'Referer': base}), timeout=20) as response:
        if response.read().strip() not in (b'Ok.', b''):
            raise RuntimeError('qBittorrent authentication failed')

    def get(path):
        with opener.open(urllib.request.Request(base + '/api/v2/' + path,
                         headers={'Referer': base}), timeout=20) as response:
            return json.load(response)

    info, torrents = get('transfer/info'), get('torrents/info')
    counts = collections.Counter(t.get('state', 'unknown') for t in torrents)
    lines = ['qBittorrent status — ' + datetime.datetime.now(datetime.timezone.utc).strftime('%H:%M UTC'),
             'Connection: ' + str(info.get('connection_status', 'unknown')),
             f"Download: {size(info.get('dl_info_speed'))}/s; upload: {size(info.get('up_info_speed'))}/s",
             f'{len(torrents)} torrents; ' + ', '.join(f'{k}: {v}' for k, v in sorted(counts.items()))]
    attention = {'metaDL', 'forcedMetaDL', 'stalledDL', 'error', 'missingFiles'}
    downloading = {'downloading', 'forcedDL'}
    rows = sorted(torrents, key=lambda t: (0 if t.get('state') in downloading else
                  1 if t.get('state') in attention else 2,
                  t.get('progress', 0) >= 1, t.get('name', '')))
    for row in rows[:15]:
        state = row.get('state', 'unknown')
        label = {'metaDL': 'FETCHING METADATA', 'forcedMetaDL': 'FETCHING METADATA (forced)',
                 'stalledDL': 'STALLED', 'queuedDL': 'QUEUED'}.get(state, state)
        lines.append(f"• {row.get('name', 'Unknown')[:180]} — {label}; "
                     f"{row.get('progress', 0) * 100:.1f}%; {size(row.get('dlspeed'))}/s; "
                     f"{row.get('num_seeds', 0)} connected seeds")
        added = row.get('added_on', 0)
        if state in attention and added:
            lines.append(f'  Added {max(0, int((time.time() - added) / 60))} min ago '
                         '(not necessarily time stuck).')
    if len(rows) > 15:
        lines.append('Showing 15 torrents: downloading first, then metadata/stalled/errors, then others.')
    if not rows:
        lines.append('No torrents.')
    return '\n'.join(lines)


def radarr_status():
    def get(path):
        return request('http://192.168.1.152:7878/api/v3/' + path,
                       headers={'X-Api-Key': secret('radarr-api-key')}, timeout=25)
    queue = get('queue?page=1&pageSize=15&includeUnknownMovieItems=true&includeMovie=true')
    lines = [f"Films (Radarr): {queue.get('totalRecords', 0)} queue entries"]
    for row in queue.get('records', []):
        title = (row.get('movie') or {}).get('title') or row.get('title') or 'Unknown film'
        total, remaining = row.get('size') or 0, row.get('sizeleft')
        progress = (f' — {max(0, min(100, 100 * (total - remaining) / total)):.1f}%'
                    if total > 0 and isinstance(remaining, (int, float)) else '')
        lines.append(f"• {title} — {row.get('status', 'unknown')}{progress}")
        for status in row.get('statusMessages') or []:
            if status.get('title'):
                lines.append('  ' + str(status['title'])[:250])
            lines.extend('  ' + str(m)[:250] for m in (status.get('messages') or [])[:3])
    if not queue.get('records'):
        lines.append('No queued downloads or import issues reported.')
    if queue.get('totalRecords', 0) > 15:
        lines.append('Showing the first 15 queue entries.')
    health = get('health')
    lines.append('\nHealth: ' + ('alerts' if health else 'no alerts reported'))
    lines.extend('• ' + str(h.get('message', 'Unknown issue'))[:400] for h in health[:5])
    return '\n'.join(lines)


def direct_status(command):
    if command == '/nas':
        data = direct_tool('nas_status')
        memory, disk = data['memory'], data['volume1']
        days, hours = divmod(data['uptime_seconds'] // 3600, 24)
        return ('NAS status\n'
                f"• CPU: {data['cpu_usage_percent']}% (0.5-second sample)\n"
                f"• Load: {' / '.join(str(n) for n in data['load_average'])}\n"
                f'• Uptime: {days} days, {hours} hours\n'
                f"• RAM available: {size(memory['available_bytes'])} / {size(memory['total_bytes'])}\n"
                f"• Swap used: {size(memory['swap_used_bytes'])} / {size(memory['swap_total_bytes'])}\n"
                f"• Volume1: {disk['used_percent']}% used; {size(disk['available_bytes'])} available\n\n"
                'Disk space only—not disk I/O, SMART or RAID health. Swap occupancy alone does not prove current memory pressure.')
    if command == '/tdarr':
        data = direct_tool('tdarr_status')
        lines = ['Tdarr status']
        for node in data.get('nodes', []):
            queue = node.get('node_queue_lengths') or {}
            lines.append(f"• {node.get('name')}: {node.get('active_worker_count', 0)} active workers; "
                         f"paused: {'yes' if node.get('paused') else 'no'}; "
                         f"node queue: {sum(v for v in queue.values() if isinstance(v, (int, float)))}")
            for worker in node.get('workers', []):
                label = worker.get('fileName') or worker.get('file') or worker.get('status') or 'Active worker'
                progress = worker.get('percentage', worker.get('progress'))
                lines.append('  ' + str(label) + (f' — {progress}%' if progress is not None else ''))
        if not data.get('nodes'):
            lines.append('No nodes reported.')
        lines.append('\nNode queues are not necessarily the complete server backlog.')
        return '\n'.join(lines)
    if command == '/seerr':
        data = direct_tool('seerr_status')
        counts = data.get('counts', {})
        lines = [f"Seerr: {counts.get('total', 0)} requests "
                 f"({counts.get('movie', 0)} films, {counts.get('tv', 0)} shows)"]
        if counts.get('pending'):
            lines.append(f"Pending approval: {counts['pending']}")
        rows = data.get('recent', [])
        for heading, predicate in [
            ('Processing / needs attention', lambda r: r.get('media_status') in [2, 3, 4] or r.get('request_status') in [1, 3, 4]),
            ('Other recent requests', lambda r: not (r.get('media_status') in [2, 3, 4] or r.get('request_status') in [1, 3, 4])),
        ]:
            selected = [r for r in rows if predicate(r)]
            if selected:
                lines.append('\n' + heading)
            for row in selected:
                title = row.get('title') or 'Title unavailable'
                year = f" ({row['year']})" if row.get('year') else ''
                status = row.get('media_status_label', 'unknown')
                if row.get('request_status') in [1, 3, 4]:
                    status = row.get('request_status_label', status) + '; media ' + status
                lines.append(f'• {title}{year} — {status}')
        lines.append('\nLatest ten requests; Seerr processing is not download progress.')
        return '\n'.join(lines)
    if command == '/downloads':
        data = direct_tool('music_status')
        lines = [f"Downtify: {data.get('queue_state', 'unknown')}; {data.get('active', 0)} active"]
        counts = data.get('counts', {})
        if counts:
            lines.append(' • '.join(f'{key}: {value}' for key, value in counts.items()))
        for job in data.get('jobs', []):
            artists = ', '.join(job.get('artists') or [])
            title = job.get('title') or 'Title unavailable'
            progress = job.get('progress')
            lines.append(f"• {title}" + (f' — {artists}' if artists else '') +
                         f" — {job.get('status', 'unknown')}" +
                         (f' ({progress}%)' if progress is not None else ''))
        if not data.get('jobs'):
            lines.append('No download jobs reported.')
        return '\n'.join(lines)
    raise ValueError('Unknown status command')


SEERR_HELP = ('Seerr commands\n'
              '/seerr status\n'
              '/seerr search "Film Name"\n'
              '/seerr request "Film Name" 2016\n\n'
              'Year is optional. Requests submit immediately only if the title resolves uniquely; ambiguous titles return choices. Film requests only; manage show requests and seasons in Seerr.')


def seerr_command(text):
    try:
        parts = shlex.split(text)
    except ValueError:
        return 'Unmatched quotation marks.\n\n' + SEERR_HELP
    if len(parts) == 1 or (len(parts) == 2 and parts[1].lower() == 'status'):
        return direct_status('/seerr')
    action = parts[1].lower()
    if action in ['help', 'start']:
        return SEERR_HELP
    if action not in ['search', 'request'] or len(parts) < 3:
        return SEERR_HELP
    year = None
    words = parts[2:]
    if action == 'request' and len(words) >= 2 and words[-1].lower() in ['tv', 'movie'] and words[-2] in ['-', '—']:
        if words[-1].lower() == 'tv':
            return ('That is a TV show. /seerr request currently submits films only.\n'
                    'For an existing show, search missing episodes with:\n'
                    '/shows search "Slow Horses" season 6\n'
                    'This can grab downloads. Remove/blocklist suspicious releases first.')
        words = words[:-2]
    # Parse a trailing year only when there is also a separate title argument.
    if action == 'request' and len(words) > 1 and re.fullmatch(r'\(?\d{4}\)?', words[-1]):
        token = words[-1]
        if token.isdigit() or (token.startswith('(') and token.endswith(')')):
            year = int(words.pop().strip('()'))
    title = ' '.join(words).strip()
    if action == 'request' and year is None:
        # Also accept a year inside a quoted full title: "Film Name (2014)".
        match = re.fullmatch(r'(.+?)\s+\((\d{4})\)', title)
        if match:
            title, year = match.group(1).strip(), int(match.group(2))
    if not title or len(title) > 200:
        return 'Use a title between 1 and 200 characters.\n\n' + SEERR_HELP
    if action == 'search':
        result = direct_tool('seerr_search', {'query': title})
        rows = result.get('results', [])
        if not rows:
            return 'No matching films or shows found.'
        lines = ['Seerr matches']
        for row in rows:
            date = str(row.get('date') or '')[:4]
            lines.append('• ' + str(row.get('title') or 'Title unavailable') +
                         (f' ({date})' if date else '') + ' — ' + row.get('type', 'unknown'))
        lines.append('\nFilms: /seerr request "Exact Title" year\n'
                     'TV requests: use Seerr. Existing TV season search: /shows search "Show Title" season 6 (can grab downloads).')
        return '\n'.join(lines)
    result = direct_tool('seerr_request_title', {
        'title': title, 'year': year, 'media_type': 'movie', 'submit': True,
    })
    if result.get('needs_clarification'):
        lines = ['No request submitted. Please choose the exact film title and year:']
        films = [row for row in result.get('choices', []) if row.get('type') == 'movie']
        if not films:
            if any(row.get('type') == 'tv' for row in result.get('choices', [])):
                return ('This matches a TV show, not a film. No request submitted.\n'
                        'To search again for an existing monitored season, use:\n'
                        '/shows search "' + title + '" season 6\n'
                        'Replace 6 with the season you want. This can grab downloads; blocklist suspicious releases first.')
            return ('No request submitted: no matching films were returned.\n'
                    'Try /seerr search "' + title + '"\n'
                    'Then /seerr request "Exact Title" year')
        for row in films:
            date = str(row.get('date') or '')[:4]
            lines.append('• ' + str(row.get('title')) + (f' ({date})' if date else ''))
        lines.append('\n/seerr request "Exact Title" year\nIf title and year still match multiple films, use Seerr directly to select the exact one.')
        return '\n'.join(lines)
    if result.get('submitted'):
        status = {1: 'pending approval', 2: 'approved', 3: 'declined', 4: 'failed', 5: 'completed'}.get(result.get('request_status'), 'accepted')
        return (f"Seerr request: {result.get('title', title)}\nStatus: {status}\n"
                f"Request ID: {result.get('request_id')}\nSubmission does not mean the download is complete.")
    return str(result.get('reason') or result.get('note') or 'No new request submitted.')




DIRECT_HELP = ('Commands\n\n/shows status — episode downloads and import issues\n/shows search "Show Title" season 6 — search and grab missing episodes\n'
    '/films [status] — film downloads and import issues\n/qbittorrent [status] — torrent states and speeds\n'
    '/refresh — update Telegram command menu\n'
    '/nas [status]\n/disk activity\n/transcoding [status]\n/seerr help\n'
    '/music downloads — Downtify music queue and progress\n/music search "Title or artist"\n'
    '/music request "Song title" "Artist"\n/music releases "Artist"\n'
    '/home status ["Area or device name"]\n\n'
    'Requests submit only on a unique validated match. Ambiguous matches require clarification. '
    'Album downloads and direct home controls are not supported.')


def sonarr_api(path, body=None):
    with open('/secrets/sonarr-api-key') as handle:
        key = handle.read().strip()
    req = urllib.request.Request('http://192.168.1.152:8989/api/v3/' + path,
        headers={'X-Api-Key': key, 'Content-Type': 'application/json'},
        data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=25) as response:
        return json.load(response)


def sonarr_command(parts):
    if len(parts) == 1 or (len(parts) == 2 and parts[1].lower() == 'status'):
        return sonarr_status()
    usage = 'Use /shows status or /shows search "Exact Show Title" season 6. Search can automatically grab releases.'
    if len(parts) < 5 or parts[1].lower() != 'search' or parts[-2].lower() != 'season' or not parts[-1].isdigit():
        return usage
    title, season = ' '.join(parts[2:-2]).strip(), int(parts[-1])
    rows = [r for r in sonarr_api('series') if r.get('title', '').casefold() == title.casefold()]
    if len(rows) != 1:
        return 'No unique exact show title in Sonarr. Check the title in Sonarr; nothing searched.'
    show = rows[0]
    selected = [s for s in show.get('seasons', []) if s.get('seasonNumber') == season]
    if season < 1 or not show.get('monitored') or not selected or not selected[0].get('monitored'):
        return 'That show/season is absent or not monitored. Nothing searched; monitoring settings were not changed.'
    episodes = sonarr_api('episode?seriesId=' + str(show['id']))
    missing = [e['id'] for e in episodes if e.get('seasonNumber') == season and e.get('monitored')
               and not e.get('hasFile') and e.get('airDate') and e['airDate'] <= datetime.datetime.now(datetime.timezone.utc).date().isoformat()]
    if not missing:
        return 'No aired, monitored missing episodes in that season. No search started.'
    queue = sonarr_api('queue?page=1&pageSize=1000&includeUnknownSeriesItems=true')
    ids = set(missing)
    if any(r.get('episodeId') in ids or (r.get('seriesId') == show['id'] and r.get('seasonNumber') == season) for r in queue.get('records', [])):
        return 'This season already has queue entries. Check /shows status and resolve/blocklist suspicious or blocked downloads before searching again.'
    try:
        sonarr_api('command', {'name': 'EpisodeSearch', 'episodeIds': missing})
    except Exception:
        return 'Search submission outcome uncertain. Check Sonarr tasks/queue before retrying.'
    return f"Search submitted for {show['title']} season {season}: {len(missing)} missing episodes. Sonarr may automatically grab eligible releases. Use /shows status for progress."


def sonarr_status():
    get = sonarr_api
    queue = get('queue?page=1&pageSize=15&includeUnknownSeriesItems=true&includeSeries=true&includeEpisode=true')
    health = get('health')
    lines = [f"Sonarr: {queue.get('totalRecords', 0)} queue entries"]
    for row in queue.get('records', []):
        series = row.get('series') or {}
        episode = row.get('episode') or {}
        label = series.get('title') or row.get('title') or 'Unknown series'
        if episode.get('episodeNumber') is not None:
            label += f" — S{episode.get('seasonNumber', 0):02d}E{episode['episodeNumber']:02d}"
        total, remaining = row.get('size') or 0, row.get('sizeleft')
        progress = ''
        if total > 0 and isinstance(remaining, (int, float)):
            progress = f' — {max(0, min(100, 100 * (total - remaining) / total)):.1f}%'
        lines.append('• ' + label + ' — ' + str(row.get('status') or 'unknown') + progress)
        for status in row.get('statusMessages') or []:
            if status.get('title'):
                lines.append('  ' + str(status['title'])[:250])
            for message in (status.get('messages') or [])[:3]:
                lines.append('  ' + str(message)[:250])
    if not queue.get('records'):
        lines.append('No queued downloads or import issues reported.')
    if queue.get('totalRecords', 0) > 15:
        lines.append('Showing the first 15 queue entries.')
    if health:
        lines.append('\nHealth alerts')
        for item in health[:5]:
            lines.append('• ' + str(item.get('type', 'alert')) + ': ' + str(item.get('message', 'Unknown issue'))[:400])
    lines.append('\nSonarr download/import status—not Tdarr transcoding progress.')
    return '\n'.join(lines)


def extra_command(text):
    try:
        parts = shlex.split(text)
    except ValueError:
        return 'Unmatched quotation marks.\n\n' + DIRECT_HELP
    command = parts[0].split('@')[0].lower()
    command = {'/shows': '/sonarr', '/sonar': '/sonarr',
               '/films': '/radarr', '/transcoding': '/tdarr'}.get(command, command)
    action = parts[1].lower() if len(parts) > 1 else 'status'
    if command == '/refresh' and len(parts) == 1:
        refresh_commands()
        return 'Telegram command menu refreshed.\n\n' + DIRECT_HELP
    if command in ['/qbittorrent', '/radarr']:
        if action != 'status' or len(parts) > 2:
            return DIRECT_HELP
        return qbittorrent_status() if command == '/qbittorrent' else radarr_status()
    if action == 'help':
        return DIRECT_HELP
    if command == '/sonarr':
        return sonarr_command(parts)
    if command == '/disk':
        if action != 'activity' or len(parts) != 2:
            return 'Use /disk activity for a two-second physical-disk sample.'
        data = direct_tool('disk_activity')
        lines = [f"Disk activity — {data.get('sample_seconds', 2)}-second sample"]
        for disk in data.get('disks', []):
            lines.append(f"• {disk['device']}: read {size(disk['read_bytes_per_second'])}/s; "
                         f"write {size(disk['write_bytes_per_second'])}/s; busy {disk['busy_percent']}%")
        if not data.get('disks'):
            lines.append('No supported physical-disk counters were returned.')
        lines.append('\nPhysical disks only. Process/service attribution is not enabled. Busy time alone does not prove saturation; device-to-bay mapping is not verified.')
        return '\n'.join(lines)
    if command in ['/nas', '/tdarr', '/downloads']:
        return direct_status(command) if action == 'status' and len(parts) <= 2 else DIRECT_HELP
    if command == '/home':
        if action != 'status' or len(parts) > 3:
            return DIRECT_HELP
        data = direct_tool('home_status', {'query': parts[2] if len(parts) == 3 else ''})
        rows = data.get('entities', [])
        if not rows:
            return 'No matching Home Assistant entities.'
        lines = ['Home Assistant status']
        for row in rows[:25]:
            lines.append('• ' + row['name'] +
                         (f" [{row['area']}]" if row.get('area') else '') +
                         ' — ' + str(row.get('state', 'unknown')))
        if len(rows) > 25:
            lines.append(f'{len(rows)} matches; showing 25. Specify an area/device to narrow the result.')
        return '\n'.join(lines)
    if command == '/music':
        if action in ['status', 'downloads'] and len(parts) <= 2:
            return direct_status('/downloads')
        if action == 'search' and len(parts) == 3:
            rows = direct_tool('music_search', {'query': parts[2]}).get('results', [])
            return 'Song matches\n' + ('\n'.join('• ' + str(r.get('name')) + ' — ' +
                ', '.join(r.get('artists') or []) +
                (f" ({r['album_name']})" if r.get('album_name') else '') for r in rows) or 'No matches.')
        if action == 'releases' and len(parts) == 3:
            data = direct_tool('music_artist_releases', {'artist_name': parts[2], 'limit': 10})
            if data.get('needs_clarification'):
                return 'Artist needs clarification; nothing downloaded.\n' + '\n'.join(
                    '• ' + str(r.get('name')) + ' — ' + (', '.join(r.get('example_albums') or []) or 'No example albums returned')
                    for r in data.get('choices', [])) + '\nSelect the exact artist in Downtify.'
            return (str(data.get('artist', parts[2])) + ' — latest catalog releases\n' + '\n'.join(
                '• ' + str(r.get('name')) + ' — ' + str(r.get('release_date') or r.get('year') or 'Date unknown') +
                ' (' + str(r.get('release_type') or 'release') + ')' for r in data.get('items', [])) +
                '\nCatalog dates, not a comparison against your local library.')
        if action == 'request' and len(parts) == 4:
            data = direct_tool('music_request_song', {'title': parts[2], 'artist': parts[3], 'submit': True})
            if data.get('needs_clarification'):
                return 'No download submitted. Choose a recording/version:\n' + '\n'.join(
                    '• ' + str(r.get('name')) + ' — ' + ', '.join(r.get('artists') or []) +
                    (f" ({r['album_name']})" if r.get('album_name') else '')
                    for r in data.get('choices', [])) + '\nSelect the exact recording in Downtify.'
            if data.get('submitted'):
                return 'Submitted to Downtify: ' + str(data.get('title', parts[2])) + '\nUse /music downloads for progress. Not necessarily completed yet.'
            return str(data.get('reason') or data.get('note') or 'No new download submitted.')
    return DIRECT_HELP












def main():
    os.umask(0o077)
    try:
        with open('/state/bridge.json') as handle:
            state = json.load(handle)
    except FileNotFoundError:
        state = {'offset': 0}
    state.pop('session', None)
    state.pop('last_input_at', None)
    save(state)
    print('Private commands-only Telegram bot started', flush=True)
    threading.Thread(target=decluttarr_notifications, daemon=True).start()
    try:
        refresh_commands()
    except Exception:
        print('Command menu refresh failed; use /refresh to retry', flush=True)
    while True:
        try:
            updates = telegram('getUpdates', {
                'offset': state['offset'], 'timeout': 25,
                'allowed_updates': ['message', 'callback_query'],
            })
            for update in updates:
                # Persist admission first: never replay a request after a crash.
                state['offset'] = update['update_id'] + 1
                save(state)
                callback = update.get('callback_query')
                if callback:
                    sender = callback.get('from', {})
                    message = callback.get('message', {})
                    chat = message.get('chat', {})
                    command = callback.get('data')
                    if (sender.get('id') in ALLOWED and chat.get('type') == 'private'
                            and chat.get('id') == sender.get('id')
                            and command in ['/shows', '/films', '/qbittorrent', '/transcoding']):
                        telegram('answerCallbackQuery', {'callback_query_id': callback['id']})
                        try:
                            text = extra_command(command)
                            if len(text) <= 4000:
                                telegram('editMessageText', {
                                    'chat_id': chat['id'], 'message_id': message['message_id'],
                                    'text': text,
                                    'reply_markup': {'inline_keyboard': [[{
                                        'text': '↻ Refresh', 'callback_data': command,
                                    }]]},
                                })
                            else:
                                send_status(chat['id'], text, command)
                        except Exception:
                            print('Status refresh unchanged or unavailable', flush=True)
                    continue
                message = update.get('message', {})
                chat = message.get('chat', {})
                sender = message.get('from', {})
                if (sender.get('id') not in ALLOWED or chat.get('type') != 'private'
                        or chat.get('id') != sender.get('id')):
                    continue
                text = message.get('text', '').strip()
                if not text:
                    continue
                try:
                    command = text.split()[0].split('@')[0].lower()
                    if command == '/seer':
                        command = '/seerr'
                    if command in ['/nas', '/disk', '/tdarr', '/seerr', '/downloads', '/music', '/home', '/sonarr',
                                   '/sonar', '/shows', '/films', '/radarr', '/transcoding', '/qbittorrent', '/refresh']:
                        try:
                            result = seerr_command(text) if command == '/seerr' else extra_command(text)
                            canonical = {'/sonarr': '/shows', '/sonar': '/shows',
                                         '/radarr': '/films', '/tdarr': '/transcoding'}.get(command, command)
                            words = text.split()
                            if (canonical in ['/shows', '/films', '/qbittorrent', '/transcoding']
                                    and (len(words) == 1 or (len(words) == 2 and words[1].lower() == 'status'))):
                                send_status(chat['id'], result, canonical)
                            else:
                                send(chat['id'], result)
                        except Exception:
                            print('Direct status request failed', flush=True)
                            if command in ['/seerr', '/music'] and len(text.split()) > 1 and text.split()[1].lower() == 'request':
                                send(chat['id'], 'Could not confirm the request outcome. Check /seerr status or /music downloads before retrying to avoid duplicates.')
                            else:
                                send(chat['id'], 'Could not read that service’s status. Please try again or check the service.')
                    elif command in ['/start', '/help']:
                        send(chat['id'], DIRECT_HELP)
                    elif text == '/new':
                        send(chat['id'], 'No reset is needed. Use /help for commands.')
                    elif len(text) > 8000:
                        send(chat['id'], 'Please keep messages below 8,000 characters.')
                    else:
                        send(chat['id'], 'Send /help for available commands.')
                except Exception:
                    # Never log exception strings: URLs may contain the bot token.
                    print('Conversation request failed', flush=True)
                    send(chat['id'], 'The command failed. Check service status before repeating a request.')
        except Exception:
            print('Polling temporarily unavailable; retrying', flush=True)
            time.sleep(10)


if __name__ == '__main__':
    main()
