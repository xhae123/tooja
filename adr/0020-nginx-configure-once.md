# ADR 0020: nginx는 한 번 연결하고 앱 배포마다 갱신하지 않는다

기록일: 2026-10-05 · 결정 출처: 사용자 결정

## 배경

기존 OCI에는 공용 nginx가 있습니다. 처음 작성한 배포 절차에는 매번 설정 복사·reload가 있었지만, 사용자는 이를 원하지 않았습니다.

## 결정

공용 nginx의 80번 요청을 edge 네트워크의 tooja-app:8080으로 전달합니다. 최초 설정 이후 앱 배포는 nginx 설정·이미지·컨테이너·reload를 건드리지 않습니다.

## 선택 이유

앱 교체와 프록시 운영을 분리합니다. nginx는 Docker DNS를 재조회해 컨테이너의 내부 IP 변경을 따라갑니다.

## 비교한 대안

매 배포 nginx를 재생성하거나 설정을 덮어쓰는 방법은 최종 구현에서 제거했습니다. 앱 8080 포트를 외부에 직접 공개하지 않습니다.

## 영향과 한계

DNS 재조회 주기는 현재 10초이며 단일 앱 교체 동안 짧은 응답 단절은 가능하므로 무중단 배포를 보장하지 않습니다. 도메인·TLS·프록시 정책 변경은 별도 인프라 작업입니다.

## 현재 상태와 구현 근거

구현됨. 재배포 후 nginx StartedAt·RestartCount·설정 mtime이 유지되는 것을 확인했습니다.

- [deployment/deploy.sh](../deployment/deploy.sh)
- [deployment/compose.yaml](../deployment/compose.yaml)
- [docs/deployment.md](../docs/deployment.md)
