# Runbook — Song Quiz 운영 절차

> 서버에서 사람이 직접 실행하는 절차만 모은다. 자동 배포 흐름은 `.github/workflows/deploy.yml`, 구성은 `docker-compose.yml`이 원본이다.
> 각 절 끝의 **검증** 칸은 실제 서버에서 한 번 실행해 본 날짜를 적는다. 날짜가 비어 있는 절차는 아직 리허설하지 않은 것이다.
> 이 저장소는 public이다. 서버 IP·비밀번호·토큰 값은 이 문서에 쓰지 않는다.

---

## 0. 전제

| 항목 | 값 |
|---|---|
| 서버 작업 디렉토리 | `/root/quiz` (git 클론, `.env`는 git 미추적) |
| compose 서비스 | `app-blue`(127.0.0.1:8092) · `app-green`(127.0.0.1:8093) · `db`(127.0.0.1:3308, 컨테이너명 `quiz-db`) |
| 활성 색 | nginx `/etc/nginx/conf.d/quiz-upstream.conf`가 가리키는 포트. 평시에는 한 색만 running, 다른 색은 Exited |
| nginx 설정 | 서버 `/etc/nginx/conf.d/game.conf` + `quiz-upstream.conf`. 재구축용 사본은 이 저장소 `infra/nginx/` (2026-09-14 실측본) |
| 이미지 | `<DOCKERHUB_USERNAME>/quiz-app:<커밋 SHA>` + 같은 이미지에 `latest` 태그 |
| 헬스체크 | `http://127.0.0.1:<포트>/actuator/health` → `"status":"UP"` |
| 백업 | `/root/backup/song-<YYYYMMDD-HHMM>.sql.gz` (수동, §4-1). 자동 백업은 아직 없음 |

아래 명령은 모두 `/root/quiz`에서 실행한다. 여러 절에서 쓰는 변수:

```bash
cd /root/quiz
UPSTREAM=/etc/nginx/conf.d/quiz-upstream.conf
if grep -qs 8092 "$UPSTREAM"; then ACTIVE=blue; ACTIVE_PORT=8092; OTHER=green; OTHER_PORT=8093; else ACTIVE=green; ACTIVE_PORT=8093; OTHER=blue; OTHER_PORT=8092; fi
echo "active=$ACTIVE($ACTIVE_PORT) other=$OTHER($OTHER_PORT)"
```

---

## 1. 상태 확인

```bash
docker compose ps
cat "$UPSTREAM"
curl -fsS "http://127.0.0.1:$ACTIVE_PORT/actuator/health"
docker compose logs --tail=100 "app-$ACTIVE"
docker exec "quiz-app-$ACTIVE" date   # KST 여부 (TZ=Asia/Seoul)
curl -s -o /dev/null -w "%{http_code}\n" https://game.kyuhyeong.com/actuator/health   # 403 이어야 정상 (nginx 차단)
curl -s -i --http1.1 --max-time 3 -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' https://game.kyuhyeong.com/ws/websocket | head -1   # 101 이어야 정상
```

- 활성 색 컨테이너가 running이고 upstream 포트와 일치해야 한다.
- 다른 색이 Exited인 것은 정상이다(직전 버전 = 롤백 대상). `Exited (143)`은 SIGTERM 정상 종료, `(137)`은 10초 안에 못 끝나 SIGKILL된 것.
- WebSocket 확인은 반드시 `--http1.1`로. HTTP/2로 붙으면 정상이어도 400이 나온다.

**검증**: 2026-09-14

---

## 2. 수동 롤백 — 전환 후 문제가 발견됐을 때

배포 스크립트는 헬스체크를 통과해야 전환하므로, 여기서 다루는 것은 **전환 뒤에 드러난 문제**다.
직전 버전은 Exited 상태인 다른 색 컨테이너에 그대로 남아 있다.

> ⚠ `docker compose up`을 쓰지 않는다. `up`은 컨테이너를 `latest`(= 문제 버전)로 다시 만든다. **`start`로 기존 컨테이너를 되살린다.**

```bash
# 1) 직전 버전 컨테이너 기동 (이미지 재생성 없음)
docker compose start "app-$OTHER"

# 2) 헬스체크 통과 대기 (최대 90초)
for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$OTHER_PORT/actuator/health" | grep -q '"status":"UP"' && { echo OK; break; }; sleep 3; done

# 3) 트래픽 전환
cp -p "$UPSTREAM" "$UPSTREAM.bak"
printf 'upstream quiz_backend {\n    server 127.0.0.1:%s;   # active: %s\n}\n' "$OTHER_PORT" "$OTHER" > "$UPSTREAM"
nginx -t && nginx -s reload

# 4) 외부 응답 확인 후 문제 버전 정지
curl -fsS -o /dev/null -w "%{http_code}\n" https://game.kyuhyeong.com/
sleep 30
docker compose stop "app-$ACTIVE"
```

- 다른 색 컨테이너가 아예 없으면(`docker compose ps -a`에 없음) `start`가 실패한다 → §3으로 직전 SHA를 올린다.
- 되살린 컨테이너의 이미지 확인: `docker inspect -f '{{.Config.Image}} {{.Image}}' "quiz-app-$OTHER"`
- 롤백 후 **다음 푸시가 다시 배포**한다. 원인을 고친 커밋을 올리거나, 급하면 문제 커밋을 `git revert` 해서 푸시한다.
- 스키마를 바꾼 배포였다면 이전 버전이 `ddl-auto=validate`에서 뜨지 않을 수 있다 → §4 복원과 함께 판단.

**검증**: 🔲

---

## 3. 특정 커밋(SHA)으로 재배포

Docker Hub에 남아 있는 SHA 태그 이미지를 유휴 색에 올린다. 배포 스크립트의 3~7단계를 손으로 하는 것과 같다.

```bash
IMG=<DOCKERHUB_USERNAME>/quiz-app
SHA=<되돌릴 커밋 전체 SHA>

docker pull "$IMG:$SHA"
docker tag "$IMG:$SHA" "$IMG:latest"            # compose 는 latest 를 참조한다
docker compose up -d --no-deps --force-recreate "app-$OTHER"

for i in $(seq 1 30); do curl -fsS "http://127.0.0.1:$OTHER_PORT/actuator/health" | grep -q '"status":"UP"' && { echo OK; break; }; sleep 3; done
```

헬스체크가 통과하면 §2의 3)·4)로 전환한다. 실패하면 `docker compose logs --tail=100 "app-$OTHER"` 확인 후 `docker compose stop "app-$OTHER"` — 활성 색은 영향이 없다.

- 서버의 `latest`만 바뀌었을 뿐 `main`은 그대로다. 다음 푸시가 최신 커밋을 다시 배포한다.
- 보관 중인 이미지 목록: `docker images "$IMG"` (배포 스크립트의 `docker image prune -f`는 태그 없는 이미지만 지우므로 SHA 태그는 남는다)

**검증**: 🔲

---

## 4. DB 백업 · 복원

> 🔲 **자동 백업(cron)·외부 보관은 아직 구축 전이다.** 구축하면 이 절에 스크립트 위치와 보관 경로를 적는다.

### 4-1. 수동 백업

```bash
mkdir -p /root/backup && chmod 700 /root/backup
docker exec quiz-db sh -c 'mariadb-dump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines song' \
  | gzip > /root/backup/song-$(date +%Y%m%d-%H%M).sql.gz
chmod 600 /root/backup/song-*.sql.gz
```

### 4-2. 복원 리허설 (운영 무영향 — 임시 컨테이너)

```bash
docker run -d --name song-restore-test -e MARIADB_ROOT_PASSWORD=rehearsal -e MARIADB_DATABASE=song mariadb:11.8
until docker exec song-restore-test mariadb -uroot -prehearsal -e 'SELECT 1' >/dev/null 2>&1; do sleep 2; done
gunzip -c /root/backup/<파일>.sql.gz | docker exec -i song-restore-test mariadb -uroot -prehearsal song
docker exec song-restore-test mariadb -uroot -prehearsal song -e "SELECT COUNT(*) FROM member; SELECT COUNT(*) FROM song;"
docker rm -f song-restore-test
```

- 건수가 운영과 같은지 확인하고, 가능하면 이 DB로 앱을 한 번 띄워 `validate` 통과까지 본다:

```bash
docker network create restore-net && docker network connect restore-net song-restore-test
docker run -d --name song-restore-app --network restore-net --memory 640m \
  -e SPRING_PROFILES_ACTIVE=prod -e 'SPRING_DATASOURCE_URL=jdbc:mariadb://song-restore-test:3306/song?useUnicode=true&characterEncoding=utf8mb4' \
  -e SPRING_DATASOURCE_USERNAME=root -e SPRING_DATASOURCE_PASSWORD=rehearsal -e BREVO_API_KEY=dummy -e MAIL_FROM=noreply@example.com \
  -e TZ=Asia/Seoul -e 'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50 -XX:+UseSerialGC' <DOCKERHUB_USERNAME>/quiz-app:latest
for i in $(seq 1 30); do docker exec song-restore-app wget -qO- http://127.0.0.1:8082/actuator/health 2>/dev/null | grep -q '"status":"UP"' && { echo UP; break; }; sleep 3; done
docker rm -f song-restore-app song-restore-test && docker network rm restore-net
```

- **백업 파일이 있는 것과 복원이 되는 것은 다른 명제다.** 리허설 날짜를 아래에 적는다.

### 4-3. 운영 복원 (데이터 손상 시)

```bash
# 1) 앱 정지 (쓰기 차단) — 서비스 중단 시작
docker compose stop app-blue app-green

# 2) 복원 직전 상태도 백업
docker exec quiz-db sh -c 'mariadb-dump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines song' | gzip > /root/backup/song-before-restore-$(date +%Y%m%d-%H%M).sql.gz

# 3) 재생성 후 적재
docker exec quiz-db sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE song; CREATE DATABASE song DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"'
gunzip -c /root/backup/<복원할 파일>.sql.gz | docker exec -i quiz-db sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" song'

# 4) 활성 색만 기동 → 헬스체크
docker compose start "app-$ACTIVE"
```

**검증**: 백업 2026-09-14 (31 테이블, gzip 330KB) · 복원 리허설 2026-09-14 (임시 컨테이너 적재 1.2초, 6개 표 행수 운영과 일치, 앱 기동 UP 33초·`validate` 통과) · 운영 복원 — (실행 시 날짜·사유 기록)

---

## 5. 서버 재부팅 후 점검

모든 컨테이너가 `restart: unless-stopped`다. 직접 `stop`한 색은 재부팅 후에도 Exited로 남는 것이 정상이다.

```bash
docker compose ps                      # db healthy, 활성 색 running, 다른 색 Exited
cat "$UPSTREAM"                        # 포트가 running 색과 일치하는지
curl -fsS "http://127.0.0.1:$ACTIVE_PORT/actuator/health"
curl -fsS -o /dev/null -w "%{http_code}\n" https://game.kyuhyeong.com/
systemctl is-active nginx
systemctl list-timers | grep -i certbot
docker exec "quiz-app-$ACTIVE" date     # KST
```

- upstream이 가리키는 색이 Exited라면: `docker compose start "app-$ACTIVE"` 후 헬스체크.
- db가 unhealthy라면: 컨테이너 env의 `MYSQL_ROOT_PASSWORD`와 실제 root 비밀번호가 다른지 먼저 의심한다(healthcheck가 그 값으로 접속한다).

**검증**: 2026-09-14 (명령만 실행. 실제 재부팅 리허설은 미실시)

---

## 6. DB 비밀번호 교체

환경변수(`MYSQL_ROOT_PASSWORD` 등)는 **볼륨 최초 생성 때만** 반영된다. `.env`만 바꾸면 실제 비밀번호는 그대로이고 healthcheck만 깨진다.

1. §4-1 백업
2. DB 안에서 변경: `ALTER USER '<계정>'@'<host>' IDENTIFIED BY '<새 비밀번호>';` (`SELECT user,host FROM mysql.user;`로 대상 확인)
3. `.env`의 `DB_PASSWORD` 갱신
4. `docker compose up -d --no-deps --force-recreate db` → healthy 대기 (healthcheck가 새 env 값을 쓰도록)
5. `docker compose up -d --no-deps --force-recreate "app-$ACTIVE"` → 헬스체크 (1~2분 중단)
6. 구 비밀번호로 접속이 거부되는지 확인

> 개선 과제: 앱 전용 계정 분리 + healthcheck를 root 비밀번호와 분리하면 root 교체가 무중단이 된다.

**검증**: 2026-09-13 (root 교체, 위 절차)

---

## 7. 만료 · 갱신 항목

| 항목 | 갱신 방식 | 확인 방법 | 다음 만료 |
|---|---|---|---|
| TLS 인증서 (Let's Encrypt) | `certbot-renew.timer` 자동 | `certbot certificates` · `certbot renew --dry-run` | 2026-12-01 (타이머 동작 확인 2026-09-14) |
| 도메인 `kyuhyeong.com` | 등록기관 수동 | 등록기관 콘솔 | 🔲 |
| Docker Hub 토큰 (`DOCKERHUB_TOKEN`) | 수동 재발급 → GitHub Secret 교체 | Docker Hub 계정 설정 | 🔲 |
| 배포 SSH 키 (`SERVER_SSH_KEY`) | 수동 | 서버 `authorized_keys` | 만료 없음 (유출 시 교체) |
| Brevo API 키 (`BREVO_API_KEY`) | 수동 → 서버 `.env` → 앱 재생성(무중단은 `gh workflow run deploy.yml`) | Brevo 대시보드 → SMTP & API → API Keys. **키는 대시보드에서 활성화(activate)해야 401이 풀린다** (2026-09-15 겪음) | 🔲 |
| Brevo Authorised IPs | 서버 IP 변경 시 재등록 | 미등록이면 메일 API가 401 | 서버 IP 변경 시 |
| VPS 계약 | 호스팅사 | 호스팅 콘솔 | 🔲 |

---

## 8. 자주 보는 증상

| 증상 | 원인 후보 | 확인 |
|---|---|---|
| 배포 Actions가 헬스체크 단계에서 실패 | 새 이미지 기동 실패(스키마 `validate` 불일치, env 누락) | `docker compose logs --tail=100 app-<유휴 색>` — 활성 색은 영향 없음 |
| 기동 로그에 `배치 작업을 찾을 수 없음: <ID>` | 코드에서 지운 배치의 `batch_config` 행이 DB에 남음 | `SELECT batch_id, enabled FROM batch_config;` 후 해당 행 삭제 |
| 시간이 9시간 어긋남 | 컨테이너 TZ 폴백 | `docker exec quiz-app-<색> date`, 이미지에 `tzdata` 포함 여부 |
| 메일 인증 코드·임시 비밀번호가 안 감 (401) | Brevo 키 미활성화·만료 / Authorised IPs 미등록 | `docker compose logs app-<색> \| grep '\[Mail\]'` 의 응답 본문: `Key not found`→키, `unrecognised IP address`→IP |
| 멀티플레이가 폴링으로만 동작, 브라우저 콘솔에 `[WS] Connection error` | nginx `game.conf`에 `location /ws/` Upgrade 헤더 전달이 빠짐 (2026-09-14 이전 상태) | §1의 `--http1.1` curl → 101 이어야 함. 400 `Can "Upgrade" only to "WebSocket"` 이면 `infra/nginx/game.conf`의 `/ws/` 블록을 서버에 복원 |
| 기동 직후 첫 요청이 10~30초 걸림 | `SecureRandom` 엔트로피 부족 (`SessionIdGeneratorBase` WARN) | `docker compose logs app-<색> \| grep SecureRandom`. `JAVA_TOOL_OPTIONS`에 `-Djava.security.egd=file:/dev/./urandom` |
