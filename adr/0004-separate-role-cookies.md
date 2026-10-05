# ADR 0004: 투자자와 관리자는 별도 세션 쿠키를 사용한다

기록일: 2026-10-05 · 결정 출처: 사용자 요구사항

## 배경

요구사항에는 같은 브라우저에서 투자자와 관리자가 동시에 로그인할 수 있어야 한다고 적혀 있습니다. 운영팀 투자자는 투자자이며 관리자와 다릅니다.

## 결정

investor_session과 admin_session을 분리하고 각 역할의 CSRF 토큰도 따로 관리합니다.

## 선택 이유

한 역할의 로그인·로그아웃이 다른 역할을 바꾸지 않게 합니다. 서버는 필요한 역할을 매 API에서 검사합니다.

## 비교한 대안

쿠키 하나에 현재 역할만 넣으면 로그인 전환 시 이전 역할을 잃거나 복잡한 다중 역할 처리가 필요합니다.

## 영향과 한계

프론트엔드는 두 로그인 상태와 두 토큰을 구분해야 합니다. 쿠키 Path는 전송 범위이지 권한 검사의 대체물이 아닙니다.

## 현재 상태와 구현 근거

구현됨. STAFF 로그인에는 관리자 권한이 없습니다.

- [src/main/kotlin/com/tooja/AuthService.kt](../src/main/kotlin/com/tooja/AuthService.kt)
- [src/main/kotlin/com/tooja/WebConfig.kt](../src/main/kotlin/com/tooja/WebConfig.kt)
