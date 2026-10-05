# ADR 0006: 쿠키 인증의 쓰기 요청에 CSRF·Origin 검증을 적용한다

기록일: 2026-10-05 · 결정 출처: 협의된 구현 선택

## 배경

세션 쿠키는 브라우저가 자동 전송합니다. 로그인 전 요청과 로그인 후 투자·별칭 수정·로그아웃을 화면의 정상 요청인지 확인해야 합니다.

## 결정

각 역할의 GET csrf API로 준비 토큰을 받고, POST·PATCH·DELETE에 X-CSRF-Token과 정확한 허용 Origin을 요구합니다. 로그인 성공 때 토큰을 교체합니다.

## 선택 이유

세션 쿠키만으로는 요청 의도를 확인하기 어려워 역할별 토큰과 Origin을 함께 검사합니다. 로그인 준비 상태는 10분만 유지합니다.

## 비교한 대안

토큰 없이 쿠키만 보내는 구성은 채택하지 않았습니다. 서로 다른 출처에 직접 쿠키를 보내는 구성보다 동일 출처 또는 개발 프록시를 기본으로 선택했습니다.

## 영향과 한계

Origin은 scheme·host·port가 모두 맞아야 합니다. 로컬 개발 서버는 /api/v1 프록시에서 대상 Origin을 전달해야 합니다. 현재 HTTP는 Secure=false이며 HTTPS 전환 시 Origin·Secure 설정도 함께 바꿉니다.

## 현재 상태와 구현 근거

구현됨. 읽기 API에는 CSRF 헤더가 필요하지 않습니다.

- [src/main/kotlin/com/tooja/AuthService.kt](../src/main/kotlin/com/tooja/AuthService.kt)
- [docs/deployment.md](../docs/deployment.md)
