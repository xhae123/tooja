# ADR 0011: 확정 투자는 수정·취소하지 않는다

기록일: 2026-10-05 · 결정 출처: 사용자 정책

## 배경

행사 심사에 사용할 투자 합계와 개인 잔액의 일관성이 중요합니다. 투자 후 되돌리는 업무 흐름은 요구사항에 없습니다.

## 결정

확정 거래에 수정·취소·환불 API를 제공하지 않습니다. 참가자는 자기 팀에 투자할 수 없고 한 팀에 한 번만 투자합니다.

## 선택 이유

투자 확인 화면에서 취소 불가를 안내하고 한 번 확정한 기록으로 집계합니다. 관리자도 거래를 임의 수정하는 UI를 갖지 않습니다.

## 비교한 대안

환불·취소 거래를 추가하는 방식은 별도 회계 규칙이 필요하므로 이번 범위에서 제외했습니다.

## 영향과 한계

계정 비활성화는 deleted_at을 통해 인증·조회에서 제외할 수 있지만 모든 테이블의 삭제 기능이 구현된 것은 아닙니다. 확정 원장을 지우지 않는 정책과 계정의 소프트 삭제는 구분합니다.

## 현재 상태와 구현 근거

구현됨. 팀 1회·금액 10만~70만원·10만원 단위는 서버와 DB에서 검사합니다.

- [src/main/kotlin/com/tooja/Models.kt](../src/main/kotlin/com/tooja/Models.kt)
- [src/main/kotlin/com/tooja/Database.kt](../src/main/kotlin/com/tooja/Database.kt)
- [src/main/kotlin/com/tooja/InvestmentService.kt](../src/main/kotlin/com/tooja/InvestmentService.kt)
