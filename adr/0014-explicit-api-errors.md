# ADR 0014: 오류는 HTTP 상태와 안정적인 code로 구분한다

기록일: 2026-10-05 · 결정 출처: 사용자 문서 요구와 구현 선택

## 배경

프론트엔드가 오류 메시지 문구를 비교하면 문구 수정에 따라 동작이 깨집니다. 어떤 입력·상태에서 오류가 발생하는지 기계적으로 설명해야 합니다.

## 결정

error.code, 화면용 message, 추가 details, 추적용 requestId를 공통 응답으로 사용합니다. 각 API의 오류 조건을 Swagger 응답에 명시합니다.

## 선택 이유

프론트엔드는 code로 분기하고 requestId로 서버 문제를 추적합니다. JSON 구성이 잘못된 경우와 파싱 이후 입력 범위 오류도 구분합니다.

## 비교한 대안

모든 실패를 HTTP 200이나 하나의 오류 문자열로 반환하는 방식은 사용하지 않습니다.

## 영향과 한계

검사는 적용되는 인증→요청 제한→쿼리 구성→DELETE 본문→CSRF→바인딩/검증→도메인 순으로 진행하며 최초 실패를 반환합니다. 저장소·실행 예외는 도중에 발생할 수 있습니다. 오류를 별도 우선순위 표와 중복 표시하지 않습니다.

## 현재 상태와 구현 근거

구현됨. 경로별 실제 조건은 ApiDocumentation과 WebConfig가 기준입니다.

- [src/main/kotlin/com/tooja/ApiDocumentation.kt](../src/main/kotlin/com/tooja/ApiDocumentation.kt)
- [src/main/kotlin/com/tooja/WebConfig.kt](../src/main/kotlin/com/tooja/WebConfig.kt)
