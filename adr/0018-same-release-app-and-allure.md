# ADR 0018: 앱과 Allure는 같은 성공한 CI 산출물을 배포한다

기록일: 2026-10-05 · 결정 출처: 사용자 요구에 따른 구현 선택

## 배경

서버의 앱 버전과 Allure 결과가 서로 다른 실행이면 그 리포트로 현재 서버의 품질을 판단하기 어렵습니다. 사용자는 매 배포마다 리포트가 갱신되길 원했습니다.

## 결정

성공한 CI가 만든 JAR와 이번 JVM Allure를 하나의 release artifact로 묶습니다. 커밋·실행 번호·JAR checksum·테스트 범위를 기록하고 배포 후 같은 커밋인지 확인합니다.

## 선택 이유

OCI에서 테스트를 다시 만들거나 추적 중인 과거 리포트를 복사하지 않고 CI 증거를 그대로 배포합니다. 실패한 CI는 기존 배포 리포트를 바꾸지 않습니다.

## 비교한 대안

앱만 먼저 배포하고 리포트를 나중에 다른 실행에서 가져오는 방식은 사용하지 않습니다.

## 영향과 한계

서버는 최신 성공 배포의 Allure를 보여주며 모든 과거 실행을 무제한 보관하는 서비스는 아닙니다. CI artifact는 현재 14일 보관하며 테스트 실패 증거도 업로드합니다.

## 현재 상태와 구현 근거

구현됨. /deployment.json과 Actions 실행으로 버전을 확인할 수 있습니다.

- [scripts/package-release.py](../scripts/package-release.py)
- [.github/workflows/deploy.yaml](../.github/workflows/deploy.yaml)
- [src/main/kotlin/com/tooja/DeploymentMetadataController.kt](../src/main/kotlin/com/tooja/DeploymentMetadataController.kt)
