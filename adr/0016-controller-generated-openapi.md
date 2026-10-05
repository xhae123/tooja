# ADR 0016: API 문서는 컨트롤러에서 생성한다

기록일: 2026-10-05 · 결정 출처: 사용자 요구사항

## 배경

수작성 HTML 문서가 실제 요청·응답과 다르게 유지될 수 있습니다. 프론트엔드 개발자가 API별 사용 시점·입력·오류를 이해할 수 있는 문서가 필요합니다.

## 결정

springdoc으로 컨트롤러·DTO·애너테이션의 OpenAPI와 Swagger를 생성합니다. 동작 설명은 컨트롤러가 참조하는 상수에, 오류·필드 보강은 customizer에 둡니다.

## 선택 이유

배포된 API와 같은 코드에서 문서를 생성하고, 각 API에 호출 흐름·응답 의미·오류 조건을 함께 보여줍니다. CI는 문서 계약도 검사합니다.

## 비교한 대안

이전 수작성 HTML API 명세는 사용하지 않습니다. 저장소의 OpenAPI JSON은 생성 당시 기록이며 현재 서버의 /v3/api-docs가 실행 명세입니다.

## 영향과 한계

자동 생성만으로 설명 품질이 보장되지는 않습니다. 동작을 바꾸면 설명·오류 조건·테스트도 함께 갱신해야 합니다.

## 현재 상태와 구현 근거

구현됨. Swagger 20개 작업과 필수 설명·오류 계약을 검증합니다.

- [src/main/kotlin/com/tooja/Controllers.kt](../src/main/kotlin/com/tooja/Controllers.kt)
- [src/main/kotlin/com/tooja/OperationDescriptions.kt](../src/main/kotlin/com/tooja/OperationDescriptions.kt)
- [src/main/kotlin/com/tooja/ApiDocumentation.kt](../src/main/kotlin/com/tooja/ApiDocumentation.kt)
