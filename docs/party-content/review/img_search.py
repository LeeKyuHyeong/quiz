"""사진 후보 찾기 도우미 (Bing 이미지 검색 결과 페이지를 읽는다 — 내려받지 않는다).

  python img_search.py "검색어"        → 상위 10개: 원본 URL | 썸네일 | 출처 페이지 | 제목 | 크기
"""
import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request

UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36',
      'Accept-Language': 'ko-KR,ko;q=0.9'}


def search(q, n=10):
    url = 'https://www.bing.com/images/search?q=' + urllib.parse.quote(q) + '&form=HDRSC2&first=1&setlang=ko'
    page = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20).read().decode('utf-8', 'replace')
    out = []
    for block in re.findall(r'<a[^>]*class="iusc"[^>]*>', page):
        m = re.search(r'\bm="([^"]+)"', block)
        if not m:
            continue
        d = json.loads(html.unescape(m.group(1)))
        size = re.search(r'(\d{3,5})\s*[x×]\s*(\d{3,5})', html.unescape(block))
        out.append({'murl': d.get('murl', ''), 'turl': d.get('turl', ''), 'purl': d.get('purl', ''),
                    'title': d.get('t', ''), 'size': size.group(0) if size else ''})
        if len(out) >= n:
            break
    return out


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    for r in search(sys.argv[1]):
        print(r['murl'], '|', r['turl'], '|', r['purl'], '|', r['title'][:60], '|', r['size'])
    time.sleep(1)
