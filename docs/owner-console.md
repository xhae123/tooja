# 조물주 콘솔: 운영자 전용 조회 화면

API 호스트가 직접 그리는 서버 렌더링 화면이에요. 주소는 `https://api.leafeep.com/owner/`이고, 프론트엔드(Vercel)와 투자자·관리자 API 계약과는 분리돼 있어요. 조회 전용이며 쓰기는 로그인·로그아웃 폼만이에요. 배경과 결정은 [ADR 0036](../adr/0036-owner-console-and-activity-log.md), [ADR 0037](../adr/0037-infra-metrics-in-app-db.md)에 있어요.

## 설정

| 환경 변수 | 의미 | 비고 |
|---|---|---|
| `OWNER_TOKEN` | 조물주 로그인 토큰, 32자 이상 | 비어 있으면 `/owner` 전체가 404. 운영은 `runtime.env`에만 두고 기록에 값을 남기지 않아요 |
| `INGEST_TOKEN` | 호스트 수집기가 `POST /internal/ingest`에 보내는 `X-Internal-Token` | 비어 있으면 ingest가 404 |
| `TRUSTED_PROXY_HOPS` | `X-Forwarded-For`에서 오른쪽부터 신뢰할 홉 수 | 기본 1(LB 한 단계) |
| `OPS_DISK_PATH` | 디스크 사용률을 재는 경로 | 컨테이너에서는 `/app/data` |
| `OPS_SCHEDULING` | 1분 샘플러·일일 정리 스케줄러 | 테스트 프로필은 `false` |

토큰 발급 예: `openssl rand -base64 48 | tr -d '/+=' | cut -c1-48`. 회전은 `runtime.env` 값을 바꾸고 재배포하면 돼요. 기존 조물주 세션은 토큰과 무관하게 12시간 뒤 만료되며, 즉시 끊으려면 `owner_sessions`를 비워요.

로컬 데모(`docker compose up`)는 `application-demo.yml`의 기본 토큰 `demo-owner-token-change-before-production-0001`로 `http://localhost:18080/owner/login`에 들어갈 수 있어요.

## 화면

| 경로 | 내용 |
|---|---|
| `/owner` | heartbeat·수집기·투자 접수·마지막 다운타임 타일, 최근 5분/1시간 요청·오류·p95, 세션·계정·투자 합계, 기록 큐 상태, 최신 지표, 최근 이벤트. 30초 자동 갱신 |
| `/owner/activity` | 모든 API 요청 기록. 시작·끝·행위·역할·계정·상태·경로·IP·오류 코드 필터, 최신순 커서 페이징 |
| `/owner/accounts`, `/owner/accounts/{id}` | 전체 계정과 팀·잔액·지급·마지막 로그인·마지막 활동·요청 수·활성 세션. 상세는 세션·투자·요청·최근 활동 50건. 로그인 코드는 해시만 있어 어디에도 표시되지 않아요 |
| `/owner/sessions` | 투자자·관리자·조물주 세션. 토큰 해시 앞 8자만 표시 |
| `/owner/investments` | 팀별 모금, 확정 투자(불변 원장), 거절된 요청 |
| `/owner/infra` | 주요 지표 차트(1h/6h/24h/7d), 지표 하나를 크게 보기와 표. 버킷 평균을 선, 최소~최대를 띠로 그려요 |
| `/owner/events` | `app_started`, `app_stopping`, `downtime_detected`, `db_unreachable`, `collector_offline/online`, `external_unavailable` |
| `/owner/errors` | 5xx와 SERVICE_UNAVAILABLE·INTERNAL_ERROR 요청. requestId로 OCI 로그의 `server_error`와 맞춰 봐요 |

## 활동 기록(`activity_log`)

`/api/**` 요청마다 한 줄이에요: `ts`(epoch ms), `request_id`, `method`, `route`(매칭된 패턴), `status`, `duration_ms`, `actor_role`(INVESTOR/ADMIN/OWNER), `account_id`, `action`, `error_code`, `team_id`, `investment_id`, `amount`, `origin`, `client_ip`, `remote_addr`, `user_agent`. 조물주 로그인·로그아웃도 `OWNER` 행위로 남아요. 원문 경로·쿠키·본문·코드·SQL은 저장하지 않아요. 기록은 best effort라 큐가 가득 차면 버리고 개요에 개수를 보여줘요.

행위 이름: `csrf`, `login_success`, `login_failed`, `logout`, `invest_succeeded`, `invest_rejected`, `pause`, `resume`, `view_me`, `view_teams`, `view_team`, `view_investments`, `view_investment`, `view_investment_request`, `view_session`, `view_overview`, `view_investors`, `view_investor`, `view_investor_rankings`, `view_dashboard`, `view_investment_status`, `body_too_large`, `unmatched`.

## 지표(`infra_samples`)와 ingest 계약

앱(`source=app`)은 1분마다 `app.heartbeat`, `app.uptime_seconds`, `jvm.heap.used`, `jvm.heap.max`, `jvm.threads`, `jvm.gc.pause.count`, `jvm.gc.pause.total_ms`, `hikari.active/idle/pending`, `http.requests.count`, `http.errors.count`, `http.rate_per_min`, `http.p95_ms`, `host.cpu.percent`, `host.mem.used_percent`, `disk.used_percent`, MySQL이면 `mysql.roundtrip_ms`, `mysql.threads_connected`, `mysql.threads_running`, `mysql.uptime_seconds`, `mysql.slow_queries`를 저장해요.

호스트 수집기는 다음 형식으로 보내요. 주소 허용 목록(127.0.0.1, ::1, Docker gateway)과 `X-Internal-Token`이 모두 맞아야 하고, 그 외에는 404예요.

```http
POST /internal/ingest
Content-Type: application/json
X-Internal-Token: <INGEST_TOKEN>

{"collector":"tooja-observer","sentAt":"2026-10-28T09:00:00Z",
 "samples":[{"ts":1761642000000,"source":"host","metric":"host.cpu.percent","value":12.5},
            {"ts":1761642000000,"source":"oci","metric":"oci.apm.availability","value":100,"dims":{"monitor":"seoul"}}],
 "events":[{"ts":1761642000000,"type":"external_unavailable","detail":{"httpCode":503}}]}
```

제한: `samples` 500건, `events` 50건, `source`는 `host`|`oci`, `metric`은 `^[a-z0-9_.]{1,64}$`, `dims` 직렬화 255자, `type`은 `^[a-z_]{1,32}$`. 응답은 `202 {"accepted":n,"dropped":m}`, 검증 실패는 422예요.

권장 지표 이름: `host.cpu.percent`, `host.mem.used_percent`, `host.disk.used_percent`, `oci.lb.requests`, `oci.lb.4xx`, `oci.lb.5xx`, `oci.lb.backend_unhealthy`, `oci.lb.connections`, `oci.waf.requests`, `oci.waf.blocked`, `oci.apm.availability`, `oci.apm.http_code`, `oci.mysql.cpu_percent`, `oci.mysql.mem_percent`, `oci.mysql.storage_percent`, `oci.mysql.connections`.

## 보존과 다운타임

활동 90일, 샘플 30일, 이벤트 365일을 매일 03:10 UTC에 정리해요. 다운타임은 마지막 `app.heartbeat`와 다음 기동 사이의 공백이 3분을 넘을 때 `downtime_detected`로 기록돼요. 수집기 호스트 샘플이 3분 넘게 끊기면 `collector_offline`, 복구되면 `collector_online`이 남아요.
