# ADR 0029: 공유 진입점을 OCI LB와 WAF로 바꿔요

## 배경

앱 VM의 nginx에 진입점·TLS·국가 차단이 함께 있어요. 사용자가 nginx를 제거하고 향후 다른 서비스도 같은 진입점에 연결하기로 했어요.

## 결정과 이유

Always Free LB의 min/max bandwidth를 10Mbps로 고정하고 WAF를 연결해요. hostname별 backend set을 사용하고 Tooja의 한국 제한·요청 제한은 Tooja hostname에만 적용해요. 미등록 hostname은 거절해요.

앱 backend는 LB NSG만 접근할 수 있고 MySQL은 앱 VM만 접근할 수 있어요. 인증서는 certbot 갱신과 별도 LB 반영 timer로 관리해요. 앱 CI/CD는 진입점 설정을 수정하지 않아요.

## 한계

모든 서비스가 10Mbps를 공유해요. WAF 첫 인스턴스·월 1,000만 요청 무료 범위를 넘는 사용까지 무료 보장은 아니에요. 단일 앱과 단일 MySQL의 장애는 별도로 남아요.

[기존 nginx 선택](0020-nginx-configure-once.md)을 대체해요.
