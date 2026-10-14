"""사진 후보 찾기(다음 이미지 검색 결과 정보만 읽음, 내려받지 않음). Bing(img_search.py)이 봇으로 막을 때 쓴다.
  python img_search_daum.py "검색어"  → 날짜 | 크기 | 출처 | 제목 | 출처 페이지 | 원본 URL
"""
import re, sys, time, html, urllib.parse, urllib.request
UA = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
      'Accept-Language': 'ko-KR,ko;q=0.9'}
BS = chr(92)
INFO = re.compile('info: [{] title: "([^"]*)"(.{0,1500}?)regdate: "([0-9.]*)"', re.S)
THUMB = re.compile('"type":"656x0_80_wr","url":"([^"]+)"')


def unesc(s):
    return s.replace(BS + '/', '/')


def clean(s):
    s = unesc(s).replace(BS, '')
    return html.unescape(re.sub('<[^>]+>', '', s))


def field(body, k):
    m = re.search('[ ,{]' + k + ': "([^"]*)"', body)
    return m.group(1) if m else ''


def search(q):
    url = 'https://search.daum.net/search?w=img&q=' + urllib.parse.quote(q)
    p = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20).read().decode('utf-8', 'replace')
    out = []
    for m in INFO.finditer(p):
        body = m.group(2)
        rs = p.rfind('ndid64', 0, m.start())
        rec = p[rs:m.start()] if rs >= 0 else ''
        cands = re.findall(r'"type":"(\d+)x0_\d+_wr","url":"([^"]+)"', rec)
        if cands:
            turl = max(cands, key=lambda c: int(c[0]))[1]
        else:
            u0 = re.search(' url: "([^"]+)"', rec)
            turl = u0.group(1) if u0 else ''
        out.append({'murl': unesc(field(body, 'oimgurl')), 'turl': unesc(turl),
                    'purl': unesc(field(body, 'docurl')), 'title': clean(m.group(1)),
                    'size': field(body, 'ow') + 'x' + field(body, 'oh'), 'date': m.group(3).replace('.', ''),
                    'cp': clean(field(body, 'cpname')), 'q': q})
    return out


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    for r in search(sys.argv[1]):
        print(r['date'], r['size'], r['cp'][:15], r['title'][:50], r['purl'][:60], r['murl'][:80], sep=' | ')
    time.sleep(1)
