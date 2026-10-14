"""고른 사진(photo-picks.tsv) 받기: python -I download_photos.py C:\\party
TSV 의 이미지파일명 그대로 저장한다. 원본이 PNG·WEBP 여도 JPEG 로 바꿔 저장(파일명이 .jpg 라서).
이미 있는 파일은 건너뛴다(다시 받으려면 그 파일을 지우고 돌린다). 실패한 줄은 끝에 모아 보여 준다.
"""
import csv
import io
import os
import sys
import urllib.parse
import urllib.request

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
MIN_WIDTH = 300  # 이보다 작으면 "이미지 없음" 대체 그림일 가능성이 높다


def fetch(url, source):
    ref = source or url
    parts = urllib.parse.urlsplit(ref)
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Referer': '%s://%s/' % (parts.scheme, parts.netloc)})
    with urllib.request.urlopen(req, timeout=30) as res:
        return res.read()


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(HERE, 'photo-picks.tsv'), encoding='utf-8-sig') as f:
        rows = [r for r in csv.DictReader(f, delimiter='\t') if r['status'] == 'ok']
    failed = []
    for r in rows:
        path = os.path.join(out_dir, r['이미지파일명'])
        if os.path.exists(path):
            print('있음', r['이미지파일명'])
            continue
        try:
            img = Image.open(io.BytesIO(fetch(r['img_url'], r['source_url'])))
            img.load()
            if img.width < MIN_WIDTH:
                raise ValueError('너무 작음 %dx%d' % img.size)
            if img.mode != 'RGB':
                img = img.convert('RGB')
            img.save(path, 'JPEG', quality=92)
            print('받음', r['이미지파일명'], '%dx%d' % img.size, r['정답'])
        except Exception as e:  # 한 장 실패로 전체를 멈추지 않고 끝에 모아 보여 준다
            failed.append((r['대분류'], r['line'], r['정답'], r['이미지파일명'], str(e)[:80]))
    print('\n%d장 중 실패 %d' % (len(rows), len(failed)))
    for x in failed:
        print('실패', *x)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main(sys.argv[1] if len(sys.argv) > 1 else r'C:\party')
