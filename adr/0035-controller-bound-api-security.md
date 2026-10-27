# 0035 실제 컨트롤러의 선언으로 API 접근 권한을 검사해요

## 배경

기존 인증 검사는 원문 URL이 `/api/v1/`로 시작할 때만 실행됐어요. 하지만 Spring은 경로 부가 정보와 인코딩을 해석한 뒤 컨트롤러를 선택해요. 두 판단이 달라지는 요청에서 인증 검사는 생략되고 관리자 컨트롤러는 실행되는 결함을 확인했어요.

내부 메트릭 차단과 API 본문 크기 제한도 원문 URL 문자열에 의존하고 있었어요. 같은 요청을 라우터와 보안 코드가 다르게 판단하지 않도록 함께 수정해요.

## 결정

실제 선택된 컨트롤러의 `@Access`를 기준으로 세션·역할을 검사해요. 원문 URL을 보고 이 검사를 생략하지 않아요. 인터셉터 등록도 URL 패턴으로 제한하지 않아요.

공개 API는 `@PublicAccess`로 명시해요. 로그인 준비·로그인·로그아웃에는 투자자 또는 관리자 영역을 선언해서, URL 표기와 관계없이 해당 역할의 CSRF를 검사해요. 공개라는 선언이 쓰기 요청의 CSRF 면제를 뜻하지는 않아요.

접근 정책을 선언하지 않은 `/api/v1` 컨트롤러는 기본 거절해요. 보호·공개 선언이 충돌하거나 역할 값이 잘못된 경우에도 실행하지 않아요. 전체 22개 작업의 정책이 빠짐없이 선언됐는지 테스트해요.

쿼리 허용 목록은 Spring이 선택한 매핑 패턴으로 결정해요. 따라서 URL의 표기만 달라졌을 때 필터가 잘못 거절되거나 입력 검사가 생략되지 않아요.

Servlet 필터에서 경로를 판별해야 하는 메트릭 보호·본문 크기 제한에는 Spring의 `ServletRequestPathUtils`와 `PathPattern`을 사용해요. 별도로 URL을 반복 디코딩하거나 세미콜론만 제거하는 규칙을 만들지 않아요. Actuator는 health만 공개하고 나머지는 기존 내부 주소에서만 허용해요. 전달 헤더를 내부 접속의 증거로 신뢰하지 않아요.

## 영향과 검증

기존 세션·쿠키·CSRF·요청 제한과 22개 API의 요청·응답 계약은 유지해요. DB 스키마나 데이터, OCI LB·WAF·네트워크 설정은 바꾸지 않아요.

단위 테스트에서 정책 누락·충돌·역할·경로 판별을 확인해요. 실제 SQLite·MySQL API 테스트에서 URL 표기를 달리한 인증·CSRF·로그인·로그아웃·필터·투자·요청 제한을 확인해요. 실제 Tomcat을 띄운 HTTP 통합 테스트도 실행해요. CI에는 이 단위·통합 테스트만 포함하며 브라우저 E2E는 실행하지 않아요.

이 결정은 현재 Servlet/MVC API의 보안 경계를 수정해요. 다른 HandlerMapping이나 별도 Servlet으로 API를 추가할 때도 동일한 보호가 적용되는지 확인해야 해요.

참고: [Spring의 Interceptor 보안 경고](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/interceptors.html), [ServletRequestPathUtils](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/util/ServletRequestPathUtils.html), [PathPattern](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/util/pattern/PathPattern.html).
