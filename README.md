# 1호선톤 가상 투자 서버

Kotlin · Spring Boot · SQLite. 투자자·관리자 코드 로그인, 투자 확정, 관리자 현황, 공개 순위를 제공합니다.

- [CI/CD·배포·프론트엔드 연동](docs/deployment.md): Actions 배포, 고정 주소, 프록시, DB 관리
- [개발·실행·검증 안내](docs/development.md): Docker Compose, 데모 코드, 테스트·리포트 생성, 운영 설정
- [API 명세](http://localhost:18080/api-docs): 서버 실행 후 Swagger에서 호출 방법·응답·오류 확인
- [OpenAPI JSON](reports/openapi.json): 컨트롤러에서 생성한 명세
- [화면별 품질 검증서](reports/quality.html): E2E 분기·번호별 화면 캡처·실행 결과
- [Allure 통합 리포트](reports/allure/index.html): 단위·통합·E2E 결과. 실행 후 [서버에서 열기](http://localhost:18080/reports/allure/)
- [서버 구현](src/main/kotlin/com/tooja) · [단위·통합 테스트](src/test/kotlin/com/tooja) · [E2E 테스트](e2e)

HTML 리포트는 해당 실행 당시의 기록입니다. GitHub에서는 다운로드하여 브라우저로 열 수 있습니다.
