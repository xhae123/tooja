# 설계 결정 기록

2026-10-05까지의 대화와 실제 구현을 기준으로 정리했습니다. 사용자 지정·사용자 결정·구현 선택을 구분합니다. 현재 상태가 바뀌면 관련 문서를 갱신하거나 새 결정으로 대체하세요.

문서마다 한 선택을 설명합니다. 미구현 기능은 완료로 표시하지 않습니다.

- [0001 Kotlin·Spring Boot·SQLite를 사용한다](0001-kotlin-spring-sqlite.md)
- [0002 JPA 대신 Spring JDBC로 SQL을 직접 작성한다](0002-jdbc-over-jpa.md)
- [0003 인증은 JWT 대신 DB 세션으로 관리한다](0003-database-sessions.md)
- [0004 투자자와 관리자는 별도 세션 쿠키를 사용한다](0004-separate-role-cookies.md)
- [0005 두 역할 모두 재사용하는 숫자 4자리 코드로 로그인한다](0005-reusable-four-digit-codes.md)
- [0006 쿠키 인증의 쓰기 요청에 CSRF·Origin 검증을 적용한다](0006-csrf-and-same-origin.md)
- [0007 투자금은 최초 로그인 성공 때 한 번 지급한다](0007-first-login-grant.md)
- [0008 투자 확정의 모든 원장 변경을 하나의 트랜잭션으로 묶는다](0008-atomic-investment.md)
- [0009 SQLite 쓰기를 한 JVM 안에서 직렬화한다](0009-single-jvm-write-lock.md)
- [0010 투자는 동기 확정하고 요청 키로 중복 차감을 막는다](0010-synchronous-idempotent-investment.md)
- [0011 확정 투자는 수정·취소하지 않는다](0011-immutable-investment-ledger.md)
- [0012 공동 순위는 1·1·3 방식으로 표시한다](0012-competition-ranking.md)
- [0013 투자 시작·마감 기능 없이 현재 현황을 제공한다](0013-live-results-without-close.md)
- [0014 오류는 HTTP 상태와 안정적인 code로 구분한다](0014-explicit-api-errors.md)
- [0015 로그인 횟수는 브라우저에서 제한한다](0015-browser-login-limit.md)
- [0016 API 문서는 컨트롤러에서 생성한다](0016-controller-generated-openapi.md)
- [0017 CI에서는 단위·통합 테스트만 실행한다](0017-ci-excludes-e2e.md)
- [0018 앱과 Allure는 같은 성공한 CI 산출물을 배포한다](0018-same-release-app-and-allure.md)
- [0019 앱 배포는 GitHub Actions를 통해서만 수행한다](0019-github-actions-only-deploy.md)
- [0020 nginx는 한 번 연결하고 앱 배포마다 갱신하지 않는다](0020-nginx-configure-once.md)
- [0021 배포와 DB 명단 삽입을 분리한다](0021-persistent-data-manual-provisioning.md)
- [0022 직접 인프라·DB 작업은 .local에 기록한다](0022-local-infra-audit.md)

- [0023 · nginx 국가 차단과 서비스 전체 요청 제한](0023-nginx-country-and-service-rate-limit.md)

- [0024 · 성공한 현재 배포 산출물만 보존](0024-current-release-only.md)
