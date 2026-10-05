# ADR 0008: 투자 확정의 모든 원장 변경을 하나의 트랜잭션으로 묶는다

기록일: 2026-10-05 · 결정 출처: 구현 선택

## 배경

잔액만 줄었는데 거래가 없거나, 거래만 생겼는데 잔액이 줄지 않으면 투자 결과를 신뢰할 수 없습니다. 동시 요청·DB 오류·응답 단절이 발생할 수 있습니다.

## 결정

잔액 차감, 확정 거래, snapshotVersion 증가, 요청 결과 저장을 함께 커밋합니다. 외래 키·CHECK·UNIQUE 제약으로 계정/팀 관계, 금액 단위, 팀당 1회와 요청 키 중복을 보호합니다.

## 선택 이유

실패하면 관련 변경이 함께 롤백돼야 합니다. 애플리케이션 검사 외에도 DB 제약으로 직접 쓰기나 경합에 대비합니다.

## 비교한 대안

여러 개의 독립 UPDATE/INSERT를 순차 실행하고 각각 커밋하는 방법은 중간 상태가 남을 수 있어 사용하지 않습니다.

## 영향과 한계

백업·직접 SQL 변경도 원장 관계를 지켜야 합니다. 시스템 오류로 응답이 불확실하면 새 키가 아니라 기존 요청 키로 결과를 확인합니다.

## 현재 상태와 구현 근거

구현됨. 동시 투자·동시 최초 지급·트랜잭션 롤백을 실제 SQLite 통합 테스트로 검증합니다.

- [src/main/kotlin/com/tooja/Database.kt](../src/main/kotlin/com/tooja/Database.kt)
- [src/main/kotlin/com/tooja/InvestmentService.kt](../src/main/kotlin/com/tooja/InvestmentService.kt)
- [src/test/kotlin/com/tooja/ApiIntegrationTest.kt](../src/test/kotlin/com/tooja/ApiIntegrationTest.kt)
