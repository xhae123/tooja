# 개발·실행·검증 안내

Kotlin 2.1 · Spring Boot 3.5 · Java 17 · SQLite로 구현한 단일 서버입니다. 최종 산출물은 [화면별 품질 검증서](../reports/quality.html)입니다. 예전 수작성 HTML API 문서는 사용하지 않습니다.

## 실행

```sh
docker compose up -d --build --wait
```

- 투자자 SSR: http://localhost:18080/login
- 관리자 SSR: http://localhost:18080/admin/login
- 공개 순위: http://localhost:18080/dashboard
- 컨트롤러 자동 생성 API 문서: http://localhost:18080/api-docs
- OpenAPI JSON: http://localhost:18080/v3/api-docs
- Allure 통합 리포트: http://localhost:18080/reports/allure/

Allure는 생성된 HTML·데이터를 Spring에서 제공하고 브라우저의 JavaScript가 화면을 그립니다. Thymeleaf SSR 화면과 렌더링 방식이 다릅니다. 로컬 demo/test에서는 리포트를 활성화하며 실제 배포에서는 REPORTS_ENABLED 설정으로 활성화합니다. 로컬 Compose는 생성된 Allure 폴더를 읽기 전용으로 연결합니다. 리포트를 다시 생성하면 서버 재빌드 없이 최신 파일이 반영됩니다.

기본 Compose는 **데모 데이터**를 사용하는 로컬 실행 환경입니다. 참가자 `0037`(팀01), `0038`(팀03), `0039`(팀04), `0040`(팀05), 운영팀 투자자 `1000`, 관리자 `4821`. 코드는 문자열이며 실제 행사용 발급 코드가 아닙니다. 데모의 팀 이름·소개도 예시입니다. SSR은 Thymeleaf로 페이지 구조를 렌더링하고 브라우저에서 실제 API를 호출하는 테스트용 화면입니다.

## 품질 검증 재실행

Java 17, Node 20 이상, Docker Compose, Chrome을 준비합니다. 다른 환경은 `CHROME_PATH`로 Chromium 실행 파일을 지정하세요.

```sh
npm ci
./gradlew clean test bootJar
docker compose --profile qa up -d --build --wait
npm run test:e2e
npm run report:allure
npm run report:quality
```

`qa` 서버는 18081 포트와 별도 SQLite 볼륨을 사용합니다. 테스트는 QA 데이터만 초기화하며 데모 서버의 데이터에 접근하지 않습니다. 리포트는 최종 실행 결과에서 생성하며 Playwright 재시도는 0회입니다. 테스트 보조 초기화·세션 만료 API는 Spring `test` 프로필에만 존재합니다. 운영에서는 이 프로필을 사용하지 마세요.

- JUnit/Allure: Given–When–Then 단계, 금액 경계값, 역할·세션·CSRF, 실제 SQLite HTTP 통합, 동시성, 롤백, 문서 계약
- JaCoCo: 실행한 JVM 코드의 줄·분기 커버리지. 브라우저 분기 수와는 별개 지표
- Playwright: 실제 클릭·입력·모달·네트워크 오류·컨테이너 재시작, 매 분기 캡처, trace
- `reports/quality.html`: PDF 화면 번호별 분기와 컴포넌트 번호를 붙인 실행 캡처. 단일 HTML 안에 이미지를 포함해 전달 가능
- `reports/allure/index.html`: 단위·통합·브라우저 테스트를 합친 Allure 단일 HTML
- `reports/playwright/index.html`: Playwright 기본 리포트와 trace. 폴더 전체를 함께 보관

## 확정 정책과 구현 선택

투자자·관리자 모두 사전 발급된 재사용 4자리 코드로 로그인합니다. 투자자는 최초 성공 시에만 100만원을 받고 관리자는 지급받지 않습니다. 두 역할은 독립 DB 세션·쿠키로 동시 로그인하며 각각 발급 후 48시간 만료, 같은 계정 여러 기기 접속을 허용합니다. 세션 갱신에 의한 자동 수명 연장은 하지 않습니다.

로그인 시도는 브라우저 `localStorage`에서 제한합니다. 데모 제안값은 투자자 60초 10회, 관리자 60초 5회입니다. 행사장 공용 IP를 로그인 제한 키로 사용하지 않습니다. 다른 보호 API는 역할·계정별 60초 180회, 공개 대시보드는 IP별 60초 300회입니다. 이 세부 횟수는 조정 가능한 제안값입니다. 해외 접속 차단은 인프라에서 적용할 계획이며 앱이나 로컬 테스트가 적용 완료를 주장하지 않습니다.

참가자는 자기 팀에 투자할 수 없고 운영팀 투자자는 20개 팀 모두 가능합니다. 10만~70만원, 10만원 단위, 팀당 1회, 잔액 초과 불가. 거래는 취소·수정하지 않습니다. 관리자는 전체 투자 접수를 중지·재개할 수 있습니다. 점수·감점 계산, 방문 인증, CSV 내보내기는 구현하지 않습니다. 미로그인·미지급자 `NOT_ACTIVATED`, 지급 후 잔액이 남은 `UNSPENT`, 전액 사용한 `COMPLETED`를 구분합니다. 공동 순위는 1·1·3입니다.

SQLite WAL·외래 키·CHECK·UNIQUE 제약을 사용하고 투자 차감·거래·버전·요청 결과를 하나의 트랜잭션으로 커밋합니다. 단일 JVM의 쓰기 작업은 공정한 잠금으로 직렬화합니다. DB 제약은 우회 쓰기도 보호합니다. 조회 집계는 한 읽기 트랜잭션의 스냅샷입니다. JVM에는 조회 원장이나 잔액을 캐싱하지 않고 요청 제한 카운터만 둡니다.

**동기 처리 선택:** 요청은 SQLite 트랜잭션 완료 뒤 응답합니다. 초안의 비동기 `202 PROCESSING`은 사용하지 않습니다. 커밋된 요청 결과는 `SUCCEEDED/REJECTED`; 미커밋·미도착 요청은 조회 `404 REQUEST_RESULT_NOT_FOUND`입니다. 이 404를 실패 확정으로 보지 않고 같은 키·본문을 재전송합니다. 쓰기 직렬화와 멱등 기록으로 원 요청과 재전송 중 한 번만 차감합니다.

**엄격한 JSON 계약:** 숫자 타입 코드, 문자열·소수·boolean 금액, 미정의 본문 필드는 자동 변환하지 않고 `400 INVALID_REQUEST`로 거절합니다. 파싱된 입력의 길이·범위·단위·선택 필터 값 위반은 `422 VALIDATION_FAILED`입니다. 실행 중 오류 검사 순서는 자동 생성 API 문서에 명시합니다. 초기 초안과 달라진 부분은 실행 코드·자동 생성 문서·테스트가 기준입니다.

## 실제 행사 데이터로 전환

데모·테스트 프로필을 제외하고 `CODE_PEPPER`(16자 이상), HTTPS의 `APP_ORIGIN`, `SECURE_COOKIES=true`, 영속 `DB_PATH`를 지정합니다. 최초 기동에는 `TEAMS_CSV`, `ACCOUNTS_CSV`의 파일 경로도 필요합니다. 20개 팀과 계정 데이터를 한 트랜잭션으로 검증·등록하며 오류가 있으면 초기화하지 않습니다. OCI는 production 프로필과 PROVISIONING_MODE=manual로 배포합니다. DB 스키마만 준비하고 초기 데모 데이터는 배포 확인 후 직접 등록합니다. 재배포는 직접 수정한 데이터를 보존합니다.

팀 파일 헤더: `teamId,serviceName,description` (소개에 쉼표 가능, 이름에는 불가).
계정 파일 헤더: `id,code,role,kind,teamId,alias` (필드 내 쉼표·줄바꿈 불가).
`role`은 `INVESTOR/ADMIN`, 투자자 `kind`는 `PARTICIPANT/STAFF`. 참가자 소속팀은 필수, 운영팀·관리자 소속팀은 비웁니다. 관리자 kind도 비웁니다. 등록 단계에서 코드 중복과 팀 외래 키를 DB가 거절합니다. 코드는 pepper를 포함한 해시만 DB에 저장하며 원문 파일은 별도 보호·관리 대상입니다. 코드 배부·명단 확인은 담당자가 수행합니다.

SQLite 파일·WAL이 저장되는 볼륨을 보존하세요. `docker compose down`은 데이터를 보존하고 `down -v`는 볼륨을 삭제합니다.

## 선택한 도구의 공식 근거

[springdoc](https://springdoc.org/v2/)는 컨트롤러·타입·애너테이션에서 OpenAPI와 Swagger UI를 생성합니다. [Allure 첨부 기능](https://allurereport.org/docs/attachments/)은 단계별 캡처와 서버 응답을 테스트 결과에 연결합니다. [Playwright 리포트](https://playwright.dev/docs/test-reporters)는 브라우저 동작·실패·trace를 제공합니다. [JUnit 5](https://docs.junit.org/5.12.2/user-guide/)의 이름 있는 테스트·매개변수 테스트와 [JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/counters.html)의 코드 커버리지를 함께 사용합니다. 커버리지 숫자를 품질 보증 자체로 해석하지 않고, 업무 규칙·분기·실행 증거를 같이 확인하도록 구성했습니다.

## 저장소에 포함한 실행 증거

최종 `reports/quality.html`과 단일 파일 `reports/allure/index.html`, OpenAPI JSON·분기 목록·실행 환경 기록을 함께 보관합니다. 원본 Playwright trace·브라우저 상세 리포트·빌드 캐시는 커밋하지 않습니다. 상세 trace와 JaCoCo/JUnit 리포트는 위 검증 명령으로 다시 생성합니다. 포함된 HTML은 해당 실행 당시의 결과 기록이며 이후 코드 변경의 검증 결과로 자동 간주하지 않습니다.
