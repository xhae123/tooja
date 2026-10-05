# ADR 0021: 배포와 DB 명단 삽입을 분리한다

기록일: 2026-10-05 · 결정 출처: 사용자 결정

## 배경

사용자는 실제 서버를 배포한 다음 데모 데이터를 DB에 직접 넣고, 나중에 운영 중 명단을 바꾸고 싶다고 했습니다. demo 프로필은 재시작 때 누락된 데모 행을 다시 넣습니다.

## 결정

production + PROVISIONING_MODE=manual로 스키마만 준비하고, Actions 배포 확인 후 별도 트랜잭션으로 명단을 삽입합니다. data 디렉터리는 release와 분리합니다.

## 선택 이유

재배포가 직접 수정한 팀 소개·계정·잔액을 초기화하거나 데모 행을 재생성하지 않도록 합니다. 빈 DB 시작·직접 수정 보존 테스트도 작성했습니다.

## 비교한 대안

실제 서버에서 demo 프로필을 계속 사용하거나 매 배포 data.sql을 실행하는 방식은 사용하지 않습니다. CSV 필수 등록 모드는 다른 운영 선택으로 남아 있습니다.

## 영향과 한계

수동 등록 모드는 빈 명단이어도 앱을 시작합니다. 초기 명단이 필요한 사용은 직접 등록 후 가능합니다. 스키마 자동 변경과 데이터 직접 수정은 서로 다른 절차입니다.

## 현재 상태와 구현 근거

구현됨. 현재 가상 20팀·투자자 140명·관리자 3명을 직접 넣었습니다. 검증 로그인으로 투자자 1명만 최초 지급됐습니다.

- [src/main/kotlin/com/tooja/Database.kt](../src/main/kotlin/com/tooja/Database.kt)
- [src/test/kotlin/com/tooja/ManualProvisioningTest.kt](../src/test/kotlin/com/tooja/ManualProvisioningTest.kt)
- [deployment/compose.yaml](../deployment/compose.yaml)
- [AGENTS.md](../AGENTS.md)
