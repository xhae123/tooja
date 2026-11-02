# ADR 0039: 서버는 테스트 리포트를 제공하지 않는다

기록일: 2026-11-02 · 결정 출처: 사용자 요구

## 배경

[ADR 0018](0018-same-release-app-and-allure.md)에 따라 매 배포에 이번 CI의 JVM Allure 단일 HTML(약 5.9MB)을 포함해 `/reports/allure/`로 제공했습니다. 이 파일은 캐시 없이(`Cache-Control` 기간 0) 앱이 직접 내보냈고, 외부에서 접속할 때마다 앱 VM의 CPU 부하가 크게 올랐습니다. 행사 중 투자 API와 같은 JVM·VM 자원을 나눠 씁니다.

## 결정

- CI에서 Allure를 생성하지 않고 release 패키지에도 넣지 않습니다.
- 앱의 `/reports/allure/**` 제공을 제거합니다. 이 경로는 404입니다.
- Swagger(`/api-docs`, `/v3/api-docs`)와 `/deployment.json`은 유지합니다.
- 테스트 증거는 GitHub artifact(JUnit XML·HTML, JaCoCo)로 14일 보관합니다.

## 비교한 대안

캐시 헤더·압축을 붙여 계속 제공하는 방법은 첫 요청 비용이 남고, 운영 경로에서 테스트 리포트가 필요하지 않아 택하지 않았습니다.

## 영향과 한계

배포된 서버에서 테스트 결과를 바로 볼 수 없습니다. Actions 실행의 artifact 또는 로컬 `npm run report:allure`로 확인합니다. 테스트 코드의 Allure 단계 기록(`allure-junit5`)은 로컬 리포트용으로 유지합니다.

## 현재 상태와 구현 근거

- [scripts/package-release.py](../scripts/package-release.py)
- [deployment/receive-release.sh](../deployment/receive-release.sh): 파일 allowlist에서 Allure 제외. 서버 설치본은 운영자가 같은 내용으로 교체합니다.
- [.github/workflows/deploy.yaml](../.github/workflows/deploy.yaml)
