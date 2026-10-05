# ADR 0001: Kotlin·Spring Boot·SQLite를 사용한다

기록일: 2026-10-05 · 결정 출처: 사용자 지정

## 배경

행사 현장에서 약 140명의 투자자가 20개 팀에 가상 투자금을 배분하는 서버가 필요합니다. 사용자는 Kotlin, Spring, SQLite와 Docker Compose를 개발 조건으로 지정했습니다.

## 결정

Kotlin·Spring Boot로 API를 만들고, 한 OCI 인스턴스의 로컬 영속 파일에 SQLite 데이터를 저장합니다.

## 선택 이유

사용자 지정 스택을 따르며 별도 DB 서버를 운영하지 않아도 되는 구성을 택했습니다. 실제 예상 부하는 투자자 수뿐 아니라 동시 요청으로도 확인해야 하므로 SQLite 통합·동시성 테스트를 함께 작성했습니다.

## 비교한 대안

PostgreSQL 등 외부 DB 서버는 별도 운영 대상이 되고, 이번 지정 조건과 달라 채택하지 않았습니다.

## 영향과 한계

현재 단일 인스턴스를 전제로 합니다. 향후 여러 서버가 같은 데이터를 다루게 되면 DB·세션·잠금 구성을 다시 검토해야 합니다.

## 현재 상태와 구현 근거

구현됨. build.gradle.kts와 deployment/compose.yaml이 기준입니다.

- [build.gradle.kts](../build.gradle.kts)
- [deployment/compose.yaml](../deployment/compose.yaml)
