"""후보 추가: python add_cand.py SOUND 19 VIDEOID "메모" [start_hint]
제목·채널·길이는 YouTube 에서 읽고, 임베드가 막힌 영상은 넣지 않는다. 순번은 그 줄의 마지막 다음. 끝나면 data.js 를 다시 만든다.
"""
import csv
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import yt_search  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CAND = {'SOUND': 'cand-sound.tsv', 'ANIME': 'cand-anime.tsv', 'GAME': 'cand-game.tsv'}


def main(cat, line, video_id, note='', hint=''):
    path = os.path.join(HERE, CAND[cat])
    with open(path, encoding='utf-8-sig') as f:
        rows = list(csv.DictReader(f, delimiter='\t'))
    fields = list(rows[0].keys())
    same = [r for r in rows if r['line'] == str(line)]
    if any(r['videoId'] == video_id for r in same):
        sys.exit('이미 있음: %s' % video_id)
    embed = yt_search.oembed(video_id)
    if embed != 'OK':
        sys.exit('임베드 불가(%s): %s' % (embed, video_id))
    html = yt_search.get('https://www.youtube.com/watch?v=' + video_id + '&hl=ko')
    m = re.search(r'var ytInitialPlayerResponse = (\{.*?\});(?:var|</script>)', html)
    d = json.loads(m.group(1)).get('videoDetails', {}) if m else {}
    sec = int(d.get('lengthSeconds') or 0)
    row = {k: '' for k in fields}
    row.update({'line': str(line), '정답': same[0]['정답'] if same else '', 'videoId': video_id,
                'rank': str(max([int(r['rank']) for r in same if r['rank'].isdigit()] or [0]) + 1),
                'url': 'https://www.youtube.com/watch?v=' + video_id, 'channel': d.get('author', ''),
                'title': d.get('title', ''), 'length': '%d:%02d' % (sec // 60, sec % 60), 'embed': 'OK',
                'start_hint': hint, 'confidence': '중', 'note': note})
    rows.append(row)
    rows.sort(key=lambda r: (int(r['line']), int(r['rank']) if r['rank'].isdigit() else 99))
    with open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields, delimiter='\t', lineterminator='\n')
        w.writeheader()
        w.writerows(rows)
    print(cat, line, row['rank'] + '번', video_id, row['length'], row['channel'], '|', row['title'])


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main(*sys.argv[1:])
    subprocess.run([sys.executable, '-I', os.path.join(HERE, 'build_review.py')], check=True)
