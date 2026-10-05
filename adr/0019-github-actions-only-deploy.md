# ADR 0019: 앱 배포는 GitHub Actions를 통해서만 수행한다

기록일: 2026-10-05 · 결정 출처: 사용자 결정

## 배경

사용자는 에이전트가 직접 앱을 업로드하는 대신 Actions가 실제 배포를 수행하고 성공 여부까지 확인하길 원했습니다. 공개 저장소에서 PR 테스트와 서버 배포 권한을 분리해야 합니다.

## 결정

main의 성공 실행만 oci environment로 배포합니다. 전용 SSH 키는 서버의 강제 배포 수신 명령에 제한합니다. 관리자 키는 CI에 등록하지 않습니다.

## 선택 이유

앱/JAR 전달·컨테이너 교체·공개 검증을 CI 실행 이력으로 남기고, PR에는 배포 비밀값을 제공하지 않습니다. 수신 파일 allowlist와 checksum도 확인합니다.

## 비교한 대안

에이전트의 수동 앱 배포와 공개 저장소 PR을 OCI self-hosted runner에서 실행하는 방식은 사용하지 않습니다.

## 영향과 한계

최초 서버·nginx·배포 수신 설정과 사용자가 허용한 DB 직접 조작은 에이전트가 수행합니다. 앱 배포만 Actions로 제한하며 runtime 비밀값은 서버에 유지합니다.

## 현재 상태와 구현 근거

구현됨. 기존 TCP 22 경로를 사용하며 신규 OCI 인바운드 규칙을 만들지 않았습니다.

- [.github/workflows/deploy.yaml](../.github/workflows/deploy.yaml)
- [deployment/receive-release.sh](../deployment/receive-release.sh)
- [deployment/deploy.sh](../deployment/deploy.sh)
