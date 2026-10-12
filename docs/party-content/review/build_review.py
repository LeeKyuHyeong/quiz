"""후보(cand-*.tsv) + 문제 TSV(../*.tsv) → data.js. 후보나 TSV 를 고친 뒤 다시 돌린다: python build_review.py"""
import csv, json, os, re, sys
S = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(S)
SETS = [('SOUND', 'sound.tsv', 'cand-sound.tsv'), ('ANIME', 'anime.tsv', 'cand-anime.tsv'), ('GAME', 'game.tsv', 'cand-game.tsv')]
data = {}
for cat, src, cand in SETS:
    rows = [l.rstrip('\n').split('\t') for l in open(os.path.join(REPO, src), encoding='utf-8-sig')]
    cands = {}
    p = os.path.join(S, cand)
    if os.path.exists(p):
        with open(p, encoding='utf-8-sig') as f:
            for c in csv.DictReader(f, delimiter='\t'):
                if c.get('videoId'):
                    cands.setdefault(int(c['line']), []).append(c)
    out = []
    for i, r in enumerate(rows[1:], start=2):
        if not any(x.strip() for x in r) or r[2] != 'AUDIO':
            continue
        note = r[18] if len(r) > 18 else ''
        cut = re.sub(r'검색:[^·]*·?', '', note).strip(' ·')
        info = ' · '.join(x for x in [r[4] and '인정: ' + r[4], r[5]] if x)
        out.append({'line': i, 'answer': r[3], 'sub': r[1], 'info': info, 'cut': cut,
                    'cands': sorted(cands.get(i, []), key=lambda c: c.get('rank', '9'))})
    data[cat] = out
with open(os.path.join(S, 'data.js'), 'w', encoding='utf-8') as f:
    f.write('const DATA = ' + json.dumps(data, ensure_ascii=False) + ';\n')
print({k: (len(v), sum(1 for x in v if x['cands'])) for k, v in data.items()})
