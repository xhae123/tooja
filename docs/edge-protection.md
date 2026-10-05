# nginx 해외 접속 차단과 요청 제한

한국으로 분류된 IP만 tooja에 접속할 수 있습니다. 로그인·Swagger·Allure·화면을 포함한 HTTP 접근에 적용하며, SSH 접근 정책은 변경하지 않습니다.

IP 판별은 무료 DB-IP IP to Country Lite의 한국 범위를 nginx 기본 `geo` 모듈에 넣습니다. 별도 유료 서비스나 nginx 이미지 교체가 필요하지 않습니다. 국가 미확인 IP도 403으로 거절합니다. 실제 접속 IP를 사용하며, 외부 요청의 X-Forwarded-For를 국가 판별에 사용하지 않습니다. 국가 데이터의 오분류 및 한국 VPN을 이용한 우회는 가능합니다.

`/api/`에 속한 모든 API는 **tooja 서비스 전체 합산 초당 500건** 제한을 공유합니다. IP마다 500건씩 부여하는 설정이 아닙니다. 로그인도 같은 제한을 사용하고 별도 nginx 로그인 제한을 두지 않습니다. 순간 집중에는 burst=1000, nodelay를 적용합니다. 순간 초과 여유가 소진되면 JSON `429 RATE_LIMITED`와 `Retry-After: 1`을 반환합니다. 이 값은 서버 처리 능력 보증이나 코드 대입 방어 수치가 아닙니다.

## 운영과 배포 검증

[nginx 설정](../deployment/nginx/tooja.conf)은 운영자가 한 번 설치합니다. 앱 GitHub Actions는 nginx를 변경하거나 reload하지 않습니다. SSH로 인증된 배포 스크립트는 서버의 loopback에서 nginx를 거쳐 health·OpenAPI·Allure·배포 메타데이터를 확인합니다. 배포 메타데이터가 릴리스 파일과 일치해야 성공 증거를 Actions에 반환합니다. 해외 GitHub 러너의 HTTP 접근을 예외로 허용하지 않습니다.

국가 판별에서는 loopback과 실제 Docker edge gateway인 172.19.0.1만 내부 점검용으로 허용합니다. 네트워크를 재구성해 gateway가 바뀌면 이 설정도 검토해야 합니다.

[갱신 스크립트](../deployment/nginx/update-country-allowlist.py)는 매월 2일 최신 CSV를 받아 한국 IPv4/IPv6 CIDR 목록을 생성합니다. 다운로드·검증 실패 시 기존 목록을 유지합니다. 한국 범위가 바뀐 경우에만 nginx 설정 검사 후 graceful reload하며, 검사 실패 시 기존 목록으로 복구합니다. 이 작업은 앱 배포와 별개인 systemd timer로 수행합니다. 실행 내역은 journal과 `.local/infra/`에 기록합니다.

## 출처

IP Geolocation by [DB-IP](https://db-ip.com). 무료 데이터는 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)이며 nginx용 한국 CIDR 목록으로 변환했습니다. 차단 안내 페이지에도 출처 링크를 표시합니다.

nginx 공식 문서: [geo](https://nginx.org/en/docs/http/ngx_http_geo_module.html), [limit_req](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html).

## 요청 크기와 시간

본문 크기는 기존 nginx 기본 제한인 **1MiB**를 명시적으로 유지합니다. 사용자 요청은 제한이 없으면 3MiB를 설정하는 것이었으며, 이미 더 작은 제한이 있으므로 늘리지 않습니다. 초과 시 `413 REQUEST_BODY_TOO_LARGE`입니다. HTTP 헤더 크기까지 합친 총 요청 크기나 응답 크기의 제한은 아닙니다.

헤더·본문을 수신하는 사이의 무응답 제한은 각각 15초, 클라이언트로 보내는 사이의 무응답 제한은 30초, idle keepalive는 30초, upstream 연결 제한은 5초입니다. 본문/응답 시간 제한은 전송 전체의 절대 마감이 아니라 연속 읽기/쓰기 사이의 시간입니다. 따라서 아주 느린 지속 전송과 대규모 분산 공격까지 완전히 막지는 않습니다.
