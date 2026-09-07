# 🎵 노래 맞추기 게임

<div align="center">

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![MariaDB](https://img.shields.io/badge/MariaDB-003545?style=for-the-badge&logo=mariadb&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/CI%2FCD-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)

**YouTube 기반 멀티플레이어 음악 퀴즈 플랫폼**

노래를 듣고 제목을 맞추는 실시간 대전 게임 · 솔로/멀티/팬챌린지 등 5가지 게임 모드

[🎮 플레이하기](https://game.kyuhyeong.com)

</div>

---

## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **서비스 상태** | 운영 중(첫 게임 기록 2026-01-19) |
| **누적 회원** | 46명 |
| **개발 인원** | 1인 (기획 · 설계 · 개발 · 배포 · 운영) |
| **개발 기간** | 2026.01 ~ 현재 |
| **운영 환경** | Cafe24 VPS · Docker Compose · Nginx · HTTPS |

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| **Backend** | Spring Boot 3.4.1, Spring Data JPA, Spring Security |
| **Frontend** | Thymeleaf (SSR), Vanilla JavaScript, CSS3 |
| **Database** | MariaDB 10.11 |
| **Infra / DevOps** | Docker, Docker Compose, GitHub Actions CI/CD, Nginx, Let's Encrypt |
| **Testing** | JUnit 5, Playwright (E2E) |
| **Tooling** | MCP 기반 데이터 관리 도구 3종 (Python) |

---

## 아키텍처

```
┌─────────────┐     ┌──────────────────────────────────────────────┐
│   Browser    │────▶│  Nginx (Reverse Proxy, SSL Termination)     │
└─────────────┘     └──────────────┬───────────────────────────────┘
                                   │
                    ┌──────────────▼───────────────────────────────┐
                    │         Spring Boot Application              │
                    │                                              │
                    │  Controller (MVC + REST API)                 │
                    │       ├── client/ (사용자 40개)               │
                    │       └── admin/ (관리자 16개)                │
                    │           ↓                                  │
                    │  Service (비즈니스 로직 22개)                  │
                    │       ├── GameSessionService (게임 세션)      │
                    │       ├── AnswerValidationService (정답 검증) │
                    │       ├── MultiTierService (ELO 레이팅)      │
                    │       └── BatchScheduler (배치 27개)          │
                    │           ↓                                  │
                    │  Repository (Spring Data JPA 29개)           │
                    └──────────────┬───────────────────────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │  MariaDB (Docker Container)  │
                    │  26개 테이블, utf8mb4         │
                    └──────────────────────────────┘
```

---

## 핵심 기능 및 기술적 구현

### 1. 실시간 멀티플레이어 게임 시스템

- **Polling 기반 실시간 통신**: 채팅과 게임 상태를 HTTP Polling으로 동기화
- **게임 라이프사이클 관리**: `WAITING → PREPARING → PLAYING → FINISHED` 상태 머신으로 게임 흐름 제어
- **동시성 제어**: 최대 8인 동시 접속 시 정답 판정의 원자성 보장

### 2. ELO 기반 LP 티어 시스템

- League of Legends 스타일의 7단계 티어 시스템 (Bronze → Challenger)
- **LP 변동 계산**: 게임 내 순위, 참가자 수, 상대 티어 차이를 반영한 ELO 레이팅 알고리즘
- LP Decay, 승급/강등 로직, 일일 랭킹 스냅샷 저장

### 3. 정답 검증 엔진

- **다단계 정규화**: 소문자 변환 → 특수문자 제거 → 공백 정규화 → 한영 매핑
- **영→한 발음 변환**: 700+ 영단어 발음 매핑 테이블 기반 자동 변환 (예: "Love" → "러브")
- **복수 정답 지원**: `SongAnswer` 테이블로 곡당 다수의 허용 답안 관리

### 4. YouTube 영상 무결성 검증

- **2단계 검증 파이프라인**: oEmbed API 응답 확인 → 썸네일 이미지 크기 분석
- 삭제/비공개 영상 자동 감지, 배치 작업으로 주기적 전수 검사

### 5. 27개 스케줄링 배치 시스템

- DB 기반 Cron 표현식으로 **런타임 스케줄 변경** 가능
- 관리자 페이지에서 개별 배치 활성화/비활성화, 실행 이력 조회
- 카테고리: 데이터 정리(9개), 통계/랭킹(5개), 회원 관리(4개), 곡 무결성(5개), 팬챌린지(2개), 시스템(1개)

### 6. 관리자 시스템

- 16개 관리 모듈: 곡/장르/회원/게임방/채팅/신고/배치/통계 등 전 영역 관리
- `AdminInterceptor`를 통한 관리자 경로 일괄 인증
- 탭 네비게이션 간 검색 상태 유지 (히스토리 API 활용)

### 7. 다크 모드 & 반응형 디자인

- CSS 변수 기반 테마 시스템 (라이트/다크 모드 + 게임 페이지 전용 다크)
- 3단계 반응형: 데스크탑(769px+) / 태블릿(768px) / 모바일(480px)

---

## 게임 모드

| 모드 | 설명 | 핵심 기술 |
|------|------|-----------|
| **솔로 맞추기** | 노래를 듣고 제목 맞추기 (3회 시도) | 시간 기반 점수 계산, 장르/아티스트/연도 필터링 |
| **솔로 문제내기** | 호스트가 곡을 틀고 참가자가 맞추기 | 차등 점수 지급 (100→70→50) |
| **멀티플레이어** | 최대 8인 실시간 대전 | Polling 기반 동기화, ELO 레이팅 |
| **팬 챌린지** | 특정 아티스트 30곡 도전 | 난이도별 차등 제한시간(7s/5s/3s), 퍼펙트 클리어 추적 |
| **레트로 게임** | 2000년 이전 곡 전용 모드 | 연도 기반 필터링 |

---

## DevOps & 운영

### CI/CD 파이프라인

```
Push to main → GitHub Actions
                  ├── Maven Build & Test
                  ├── Docker Image Build & Push (Docker Hub)
                  └── SSH Deploy to Production Server
                        ├── docker-compose pull
                        └── docker-compose up -d (무중단)
```

### 인프라 구성

- **Docker Compose**: Spring Boot 앱(512MB) + MariaDB(256MB) 컨테이너 오케스트레이션
- **Nginx**: 리버스 프록시 + Let's Encrypt SSL 인증서 자동 갱신
- **모니터링**: `SystemReportBatch`를 통한 일일 시스템 리포트, `DailyStatsBatch`로 일별 통계 수집

### MCP 데이터 관리 도구

운영 효율화를 위해 MCP(Model Context Protocol) 기반 CLI 도구 3종 자체 개발:

| 도구 | 용도 |
|------|------|
| `song_register_mcp` | 곡 데이터 등록 자동화 |
| `song_checker_mcp` | 등록된 곡의 YouTube 링크 유효성 검증 |
| `song_integrity_mcp` | 곡 메타데이터 무결성 분석 및 정리 |

---

## 테스트

- **단위 테스트**: JUnit 5 (30개 테스트 클래스, 8,400+ 라인)
- **E2E 테스트**: Playwright 기반 브라우저 자동화 테스트
- **TDD 적용**: 게임 타입 설정 등 핵심 비즈니스 로직에 TDD 방식 적용

---

## 프로젝트 구조

```
src/main/java/com/kh/quiz/
├── controller/           # MVC + REST 컨트롤러
│   ├── client/           #   사용자 기능 (24개)
│   └── admin/            #   관리자 기능 (16개)
├── service/              # 비즈니스 로직 (22개)
├── repository/           # Spring Data JPA (29개)
├── entity/               # JPA 엔티티 (26개)
├── dto/                  # 데이터 전송 객체
├── batch/                # 스케줄링 배치 (27개)
├── config/               # 설정 (Security, Web, Scheduler)
├── interceptor/          # 인증 인터셉터
└── util/                 # 유틸리티 (영→한 발음 변환 등)

src/main/resources/
├── templates/            # Thymeleaf 템플릿 (77개)
├── static/               # CSS(40) · JS(43) · 이미지
└── application.yml       # 환경별 설정 (dev/prod)

tools/                    # MCP 기반 운영 도구 (Python)
.github/workflows/        # CI/CD 파이프라인
```

---

## 로컬 실행

```bash
# 요구사항: Java 17+, MariaDB

# 1. 저장소 클론
git clone https://github.com/LeeKyuHyeong/quiz.git
cd quiz

# 2. DB 생성
mysql -u root -p -e "CREATE DATABASE song CHARACTER SET utf8mb4"

# 3. 애플리케이션 실행 (개발 프로필)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# → http://localhost:8082

# Docker로 실행
docker-compose up -d
```

---

## 보안

- SQL Injection 방지: 전체 쿼리 파라미터 바인딩 적용
- XSS 방지: Thymeleaf `th:text` 자동 이스케이프, JavaScript `textContent` 사용 원칙
- 인증/인가: `AdminInterceptor` + `SessionValidationInterceptor` 2중 검증
- IDOR 방지: 리소스 접근 시 소유권 검증 로직 적용
- HTTPS: Nginx + Let's Encrypt SSL 적용

---

<div align="center">

📝 **License:** MIT

</div>
