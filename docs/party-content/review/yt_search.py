"""YouTube 후보 찾기 도우미.
  python -I yt_search.py "검색어"        → 상위 6개: videoId | 임베드(OK/HTTP401 등) | 길이 | 채널 | 제목 | 조회수
  python -I yt_search.py --desc VIDEOID  → 제목·채널·길이(초)·설명(챕터 타임스탬프 확인용)
"""
import sys, json, re, time, urllib.request, urllib.parse, urllib.error
UA = {'User-Agent': 'Mozilla/5.0', 'Accept-Language': 'ko-KR,ko;q=0.9'}
def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20).read().decode('utf-8', 'replace')
def search(q, n=6):
    html = get('https://www.youtube.com/results?search_query=' + urllib.parse.quote(q) + '&hl=ko&gl=KR')
    m = re.search(r'var ytInitialData = (\{.*?\});</script>', html)
    if not m: return []
    out = []
    def walk(o):
        if isinstance(o, dict):
            if 'videoRenderer' in o:
                v = o['videoRenderer']
                out.append((v['videoId'],
                            ''.join(r.get('text','') for r in v.get('title',{}).get('runs',[])),
                            ''.join(r.get('text','') for r in v.get('ownerText',{}).get('runs',[])),
                            v.get('lengthText',{}).get('simpleText',''),
                            v.get('viewCountText',{}).get('simpleText','')))
            for x in o.values(): walk(x)
        elif isinstance(o, list):
            for x in o: walk(x)
    walk(json.loads(m.group(1)))
    return out[:n]
def oembed(vid):
    u = 'https://www.youtube.com/oembed?format=json&url=' + urllib.parse.quote('https://www.youtube.com/watch?v=' + vid)
    try:
        urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=15).read()
        return 'OK'
    except urllib.error.HTTPError as e:
        return 'HTTP%d' % e.code
def desc(vid):
    html = get('https://www.youtube.com/watch?v=' + vid + '&hl=ko')
    m = re.search(r'var ytInitialPlayerResponse = (\{.*?\});(?:var|</script>)', html)
    if not m: return 'no player response'
    d = json.loads(m.group(1)).get('videoDetails', {})
    return 'title: %s\nchannel: %s\nlengthSeconds: %s\nembed: %s\n---\n%s' % (
        d.get('title'), d.get('author'), d.get('lengthSeconds'), oembed(vid), d.get('shortDescription', ''))
if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    if sys.argv[1] == '--desc':
        print(desc(sys.argv[2]))
    else:
        for vid, title, ch, length, views in search(sys.argv[1]):
            print(vid, '|', oembed(vid), '|', length, '|', ch, '|', title, '|', views)
    time.sleep(1)
