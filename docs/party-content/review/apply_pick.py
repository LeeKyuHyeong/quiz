"""듣고 고른 후보를 문제 TSV 에 채우고 picks.tsv 에 기록한다.

  python apply_pick.py SOUND 11 2                 # 11번 줄, 후보 2번 (시작초는 후보의 start_hint)
  python apply_pick.py SOUND 6 3 34               # 시작초 34
  python apply_pick.py SOUND 22 1 0 7 "브랜드 직전"  # 시작초 0, 길이 7, 메모
  python apply_pick.py SOUND 30 url:VIDEOID 5     # 후보에 없는 영상을 직접
"""
import csv
import datetime
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FILES = {'SOUND': ('sound.tsv', 'cand-sound.tsv'), 'ANIME': ('anime.tsv', 'cand-anime.tsv'),
         'GAME': ('game.tsv', 'cand-game.tsv')}


def main(cat, line, rank, start=None, length=None, memo=''):
    src, cand = FILES[cat]
    line = int(line)
    src_path = os.path.join(os.path.dirname(HERE), src)
    rows = open(src_path, encoding='utf-8').read().split('\n')
    cells = rows[line - 1].split('\t')
    if rank.startswith('url:'):
        video_id, hint, label = rank[4:], '', '직접 찾은 영상'
    else:
        with open(os.path.join(HERE, cand), encoding='utf-8-sig') as f:
            found = [c for c in csv.DictReader(f, delimiter='\t') if int(c['line']) == line and c['rank'] == rank]
        if not found:
            sys.exit('후보 없음: %s %d 번 줄 %s번' % (cat, line, rank))
        video_id, hint, label = found[0]['videoId'], found[0]['start_hint'], rank + '번'
        if found[0]['정답'] != cells[3] and '교체' not in found[0]['정답']:
            sys.exit('정답이 다름: TSV %s / 후보 %s' % (cells[3], found[0]['정답']))
    start = hint if start is None else start
    cells[6] = 'https://www.youtube.com/watch?v=' + video_id
    cells[7] = start or ''
    if length is not None:
        cells[8] = length
    rows[line - 1] = '\t'.join(cells)
    open(src_path, 'w', encoding='utf-8', newline='').write('\n'.join(rows))

    picks = os.path.join(HERE, 'picks.tsv')
    new = not os.path.exists(picks)
    note = '%s %s 채택%s' % (datetime.date.today().strftime('%m-%d'), label, (' · ' + memo) if memo else '')
    with open(picks, 'a', encoding='utf-8', newline='') as f:
        if new:
            f.write('대분류\tline\t정답\tvideoId\t시작초\t길이\t메모\n')
        f.write('\t'.join([cat, str(line), cells[3], video_id, cells[7], cells[8], note]) + '\n')
    print(cat, line, cells[3], cells[6], '시작', cells[7] or '-', '길이', cells[8] or '-')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main(*sys.argv[1:])
