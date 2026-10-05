# ADR 0031: 인프라 기록은 oci-man 안에 통합해요

## 배경

사용자가 인프라 Markdown을 명세처럼 관리하고 기존 .local/infra와 .local/db를 OCI 스킬 폴더로 통합하기로 했어요.

## 결정과 이유

현재·원하는 상태는 로컬 oci-man의 context에, 작업 기록은 infra/tooja에, DB 계획·검증은 그 안의 db에 보관해요. 조회·SSH·배포·인증서·모니터링·DB 작업을 모두 기록해요. 비밀값과 실제 사용자 레코드는 기록하지 않아요.

## 한계

Markdown 편집이 실제 인프라를 변경하지는 않아요. 변경 후 실제 readback을 확인해야 하고 이 폴더는 Git·Docker·CI artifact에 들어가지 않아요. 가상 더미 명단은 .local/dummy-data.yaml에 남겨요.

[기존 .local 기록 선택](0022-local-infra-audit.md)을 대체해요.
