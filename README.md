# 1호선톤 가상 투자 서버

Kotlin · Spring Boot · MySQL(운영) · SQLite(로컬 데모). 투자자·관리자 코드 로그인, 투자 확정, 관리자 현황, 공개 순위를 제공합니다.

- [프론트엔드 연동 시작하기](docs/frontend.md): 접속 주소, 로컬 프록시, 로그인·투자 요청, 중지·재개 화면 처리
- [설계 결정 기록](adr/README.md): 선택별 배경·근거·대안·한계
- [CI/CD·배포·프론트엔드 연동](docs/deployment.md): Actions 배포, 고정 주소, 프록시, DB 관리
- [개발·실행·검증 안내](docs/development.md): Docker Compose, 데모 코드, 테스트·리포트 생성, 운영 설정
- [현재 API 명세](https://api.leafeep.com/api-docs): Swagger 호출 방법·응답·오류
- [현재 OpenAPI JSON](https://api.leafeep.com/v3/api-docs): 배포된 컨트롤러에서 생성한 명세
- [화면별 품질 검증서](reports/quality.html): E2E 분기·번호별 화면 캡처·실행 결과
- [현재 CI Allure](https://api.leafeep.com/reports/allure/): 단위·통합 결과
- [과거 통합 리포트](reports/allure/index.html): 단위·통합·E2E 실행 기록
- [서버 구현](src/main/kotlin/com/tooja) · [단위·통합 테스트](src/test/kotlin/com/tooja) · [E2E 테스트](e2e)

HTML 리포트는 해당 실행 당시의 기록입니다. GitHub에서는 다운로드하여 브라우저로 열 수 있습니다.

- [현재 운영 설정·보호 범위·남은 과제](docs/runtime-review.md)
