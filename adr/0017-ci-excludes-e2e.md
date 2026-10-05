# ADR 0017: CI에서는 단위·통합 테스트만 실행한다

기록일: 2026-10-05 · 결정 출처: 사용자 결정

## 배경

화면 분기 캡처가 포함된 E2E 검증서는 이미 별도로 만들었습니다. 사용자는 이후 배포 CI에 E2E 코드를 실행하지 않기를 원했습니다.

## 결정

GitHub-hosted CI는 JVM 단위·SQLite 통합 테스트를 실행하고 그 결과만 Allure로 만듭니다. CI Node 의존성에는 Allure CLI만 설치합니다.

## 선택 이유

사용자 요청에 맞춰 브라우저 설치·실행과 오래된 E2E 결과 병합을 제외합니다. 새로운 CI 리포트가 어떤 범위를 검증했는지 명확히 표시합니다.

## 비교한 대안

Playwright까지 매 배포 실행하는 방법과 예전 150개 통합 HTML을 최신 CI 결과로 다시 사용하는 방법은 채택하지 않았습니다.

## 영향과 한계

CI 성공은 브라우저 전체 흐름을 이번 배포에서 다시 검증했다는 의미가 아닙니다. E2E는 필요할 때 별도로 실행하며 과거 품질 검증서는 해당 실행 기록입니다.

## 현재 상태와 구현 근거

구현됨. 현재 CI는 JVM 106개·E2E 0개입니다. 테스트 수는 코드 변경에 따라 달라질 수 있습니다.

- [.github/workflows/deploy.yaml](../.github/workflows/deploy.yaml)
- [ci/package.json](../ci/package.json)
- [scripts/prepare-allure.mjs](../scripts/prepare-allure.mjs)
