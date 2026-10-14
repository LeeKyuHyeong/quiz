"""사진 후보 추가(다음 이미지 검색): python add_img_cand.py screen.tsv 18 "검색어1" "검색어2" ...
가로 사진·폭 600 이상만, 포스터·무대인사·예고편·화보 같은 제목은 뺀다. 검색어마다 최대 4개, 줄마다 이미 있는 주소는 건너뛴다.
끝나면 photos.js 를 다시 만든다. 후보 파일: screen.tsv → cand-screen.tsv, person.tsv·anime.tsv → cand-person.tsv
"""
import csv
import datetime
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import img_search_daum as d  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CAND = {'screen.tsv': 'cand-screen.tsv', 'person.tsv': 'cand-person.tsv', 'anime.tsv': 'cand-person.tsv'}
SKIP = re.compile('포스터|무대인사|예고편|티저|화보|시사회|제작보고회|쇼케이스|레드카펫|굿즈|포토에세이|배경화면')
PER_QUERY = 4


def main(src, line, *queries):
    line = int(line)
    answer = open(os.path.join(os.path.dirname(HERE), src), encoding='utf-8').read().split('\n')[line - 1].split('\t')[3]
    path = os.path.join(HERE, CAND[src])
    with open(path, encoding='utf-8-sig') as f:
        rows = list(csv.DictReader(f, delimiter='\t'))
    fields = list(rows[0].keys())
    mine = [r for r in rows if r['file'] == src and r['line'] == str(line)]
    rank = max([int(r['rank']) for r in mine] or [0])
    seen = {r['img_url'] for r in mine}
    today = datetime.date.today().strftime('%m-%d')
    for q in queries:
        taken = 0
        for r in d.search(q):
            m = re.match(r'(\d+)x(\d+)', r['size'])
            if not m or int(m.group(1)) < 600 or int(m.group(1)) <= int(m.group(2)):
                continue
            if SKIP.search(r['title']) or not r['murl'] or r['murl'] in seen:
                continue
            seen.add(r['murl'])
            rank += 1
            taken += 1
            row = {k: '' for k in fields}
            row.update({'file': src, 'line': str(line), '정답': answer, 'rank': str(rank), 'img_url': r['murl'],
                        'thumb_url': r['turl'], 'source_url': r['purl'], 'title': r['title'][:80], 'size': r['size'],
                        'note': '%s 재조사 · %s' % (today, r['date'])})
            rows.append(row)
            print(line, rank, r['size'], r['title'][:50])
            if taken >= PER_QUERY:
                break
    rows.sort(key=lambda r: (r['file'], int(r['line']), int(r['rank'])))
    with open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields, delimiter='\t', lineterminator='\n')
        w.writeheader()
        w.writerows(rows)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main(*sys.argv[1:])
    subprocess.run([sys.executable, '-I', os.path.join(HERE, 'build_photos.py')], check=True)
