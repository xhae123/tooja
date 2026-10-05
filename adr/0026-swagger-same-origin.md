# Swagger는 문서를 연 주소로 API를 호출해요

## 배경

nginx에서 HTTPS를 처리하고 앱에는 내부 HTTP로 전달해요. springdoc이 내부 요청 주소를 자동으로 추측하면 HTTPS 문서에도 HTTP API 주소가 표시되어 브라우저가 호출을 차단할 수 있어요.

## 결정

OpenAPI servers의 URL을 상대 경로 `/`로 명시해요. 문서를 연 호스트와 프로토콜로 API를 호출하므로 로컬 HTTP와 운영 HTTPS에 같은 계약을 사용할 수 있어요. OpenAPI 경로에는 이미 `/api/v1`이 포함돼 있어요.

## 검증

컨트롤러가 생성한 명세에 servers[0].url이 `/`인지 통합 테스트로 확인해요. 배포 후 HTTPS Swagger에서도 실제 공개 API 호출을 확인해요.

[Swagger 공식 상대 URL 설명](https://swagger.io/docs/specification/v3_0/api-host-and-base-path/#relative-urls)을 따랐어요.
