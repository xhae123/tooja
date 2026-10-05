# ADR 0007: 투자금은 최초 로그인 성공 때 한 번 지급한다

기록일: 2026-10-05 · 결정 출처: 사용자 결정

## 배경

코드를 미리 발급받았지만 로그인하지 않은 사람과 실제 투자금을 받은 사람을 구분해야 합니다. 사전 발급 시 미리 지급하는 제안과 PDF의 최초 로그인 지급을 비교했습니다.

## 결정

사전 등록 상태는 activated=0, 잔액=0입니다. 최초 로그인에만 1,000,000원 지급과 활성화를 같은 트랜잭션으로 수행합니다. 관리자는 지급 대상이 아닙니다.

## 선택 이유

사용자가 PDF의 최초 로그인 지급 방식을 선택했습니다. 여러 기기 동시 로그인에서도 계정별 grants 제약과 쓰기 트랜잭션으로 재지급을 막습니다.

## 비교한 대안

사전 등록 때 100만원을 배정하면 등록 인원과 지급 인원을 혼동하므로 채택하지 않았습니다.

## 영향과 한계

미지급자와 전액 사용자의 잔액은 모두 0원입니다. NOT_ACTIVATED, UNSPENT, COMPLETED로 분류해야 하며 잔액만 보고 상태를 계산하면 안 됩니다.

## 현재 상태와 구현 근거

구현됨. 직접 더미 계정을 넣을 때도 임의 활성화·지급하지 않습니다.

- [src/main/kotlin/com/tooja/AuthService.kt](../src/main/kotlin/com/tooja/AuthService.kt)
- [src/main/kotlin/com/tooja/InvestmentService.kt](../src/main/kotlin/com/tooja/InvestmentService.kt)
