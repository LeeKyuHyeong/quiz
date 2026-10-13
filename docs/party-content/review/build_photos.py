"""사진 후보(cand-screen.tsv·cand-person.tsv) + 문제 TSV → photos.js. 후보나 TSV 를 고친 뒤 다시 돌린다."""
import csv
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CAND_FILES = ['cand-screen.tsv', 'cand-person.tsv']
LABEL = {'screen.tsv': 'SCREEN', 'person.tsv': 'PERSON', 'anime.tsv': 'ANIME'}

cands = {}
for name in CAND_FILES:
    path = os.path.join(HERE, name)
    if not os.path.exists(path):
        continue
    with open(path, encoding='utf-8-sig') as f:
        for c in csv.DictReader(f, delimiter='\t'):
            if c.get('img_url'):
                cands.setdefault((c['file'], int(c['line'])), []).append(c)

data = {}
for src, label in LABEL.items():
    rows = open(os.path.join(REPO, src), encoding='utf-8').read().split('\n')
    out = []
    for i, line in enumerate(rows[1:], start=2):
        r = line.split('\t')
        if len(r) < 19 or r[2] != 'IMAGE':
            continue
        out.append({'line': i, 'answer': r[3], 'sub': r[1], 'file': r[9], 'info': r[5],
                    'hint': re.sub(r'검색:[^·]*·?', '', r[18]).strip(' ·'),
                    'cands': sorted(cands.get((src, i), []), key=lambda c: int(c.get('rank') or 9))})
    data[label] = out
with open(os.path.join(HERE, 'photos.js'), 'w', encoding='utf-8') as f:
    f.write('const PHOTOS = ' + json.dumps(data, ensure_ascii=False) + ';\n')
print({k: (len(v), sum(1 for x in v if x['cands'])) for k, v in data.items()})
