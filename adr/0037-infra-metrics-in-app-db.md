# 0037 인프라 지표를 앱 DB에 적재해 서비스 활동과 함께 보기

## 배경

VM CPU·메모리·디스크, JVM·연결 풀, MySQL 상태는 호스트 수집기 `tooja-observer`가 OCI Monitoring으로 보내고 경보는 Discord로 받아요. 하지만 LB 요청 수·5xx, WAF 요청 수, 서울에서 본 외부 가용성, MySQL 서비스 지표는 OCI 콘솔에서만 보이고, 앱의 활동 기록과 같은 시간축에서 비교할 수 없었어요. 다운타임도 외부 점검 경보로만 알 수 있었어요.

앱의 instance principal은 `oci_waf` 지표만 읽을 수 있어요. 자기 CPU나 LB·MySQL·APM 지표를 읽을 IAM 권한은 없었어요.

## 결정

지표는 두 경로로 `infra_samples`(epoch ms, 출처, 지표, 값, 차원)에 모여요.

1. **앱 자체 수집**: 1분마다 heartbeat, JVM 힙·GC, Hikari 연결, 최근 1분 요청 수·5xx·p95·분당 요청(활동 기록에서 계산), 호스트 `/proc/stat`·`/proc/meminfo`, DB 경로 디스크 사용률, MySQL `SHOW GLOBAL STATUS`(연결·업타임·느린 쿼리·왕복 시간)를 저장해요. 테스트 프로필은 스케줄러를 끄고 `sampleOnce()`를 직접 호출해요.
2. **호스트 수집기 → ingest**: 기존 `collector.py`가 OCI에 보내는 호스트·앱·DB 값과, 새로 읽는 `oci_lbaas`·`oci_waf`·`oracle_apm_synthetics`·`oci_mysql_database` 지표를 `POST /internal/ingest`로 앱에 넘겨요. 이 경로는 `/api/v1` 밖이고, Docker bridge 주소 허용 목록 **그리고** 공유 비밀 `X-Internal-Token`(`INGEST_TOKEN`)이 모두 맞아야 하며 토큰이 비어 있으면 404예요. 주소만으로는 docker-proxy를 거친 호스트 트래픽도 통과하기 때문이에요. 1MiB 본문 제한은 API와 같아요.

다운타임은 heartbeat 공백으로 판정해요. 기동 시 마지막 heartbeat와 현재 차이가 간격의 3배를 넘으면 `downtime_detected{from,to,gapSeconds}`를 `system_events`에 남기고, `app_started`(배포 commit·프로필)·`app_stopping`·`db_unreachable`(분당 1회)·`collector_offline/online`(호스트 샘플 지연)도 기록해요.

OCI 지표를 읽기 위해 dynamic group `tooja-observer`에 **읽기 전용** `read metrics` 정책을 추가해요(관측 compartment의 `tooja` namespace, 플랫폼 compartment의 `oci_lbaas`·`oci_mysql_database`, 테넌시의 `oracle_apm_synthetics`·`oci_computeagent`). Monitoring 조회는 무료 한도 안이고 공개 노출이 없어요. 이 변경과 수집기 교체는 앱 배포 파이프라인 밖의 운영자 작업으로 oci-man 기록에 남겨요.

## 대안

- 앱이 OCI SDK로 직접 지표를 읽기: 컨테이너에 OCI SDK 의존성과 instance principal 처리를 추가하고 로컬 개발에서는 동작하지 않아요. 수집기는 이미 `oci` SDK와 인증을 갖고 있어요.
- 수집기가 MySQL에 직접 INSERT: 관측 계정에 쓰기 권한을 부여해야 하고 스키마 소유가 앱(Flyway) 밖으로 새요. 로컬 SQLite에서도 동작하지 않아요.
- OCI 콘솔·대시보드만 사용: 서비스 활동과 같은 화면·시간축에서 볼 수 없고, 로그인 없이 공유할 수도 없어요.

## 영향과 검증

새 유료 자원·공개 포트·LB/WAF/NSG 변경은 없어요. 수집기가 멈추면 호스트·OCI 지표만 끊기고 앱 자체 지표와 활동 기록은 계속돼요. 샘플은 30일 보존이며 하루 약 1,500~3,000행 수준이에요.

통합 테스트로 ingest의 주소·토큰 게이트와 검증(422), 샘플러 한 번 실행 결과, 4분 공백의 다운타임 판정, 보존 정리, 인프라 페이지의 SVG 렌더링을 SQLite·MySQL에서 확인해요. 실제 OCI 지표 이름은 적용 시 `oci monitoring metric list`로 확정하고, 적재 결과는 콘솔 인프라 페이지에서 확인해요.
