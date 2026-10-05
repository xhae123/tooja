# 접속 보호

운영 진입점은 OCI Load Balancer와 WAF예요. `api.leafeep.com`은 한국으로 분류된 IP만 허용하고, 국가 미확인 IP도 403으로 거절해요. 클라이언트가 보낸 X-Forwarded-For로 국가나 요청 제한을 판정하지 않아요.

요청 제한은 **클라이언트 IP별 60초에 30,000건**이에요. 초과하면 해당 IP를 60초 제한하고 `429 RATE_LIMITED`, `Retry-After: 60`을 돌려줘요. 평균 초당 500건에 해당하지만 순간 초당 제한이나 서비스 전체 합산 제한은 아니에요. 행사장 공용 IP를 고려해 여유 있게 정한 값이며 처리 용량 보증은 아니에요.

`/api/`에는 SQL injection·XSS 검사도 적용해요. 정상 별칭 입력이나 JSON이 거절되면 먼저 WAF와 앱 오류를 구분해서 확인해야 해요. 앱의 기존 역할·계정별 제한은 별도로 적용돼요.

API 쓰기 본문은 **1MiB**까지 허용하고, Content-Length가 없거나 chunked 전송이어도 실제 읽은 크기로 검사해요. 초과하면 `413 REQUEST_BODY_TOO_LARGE`예요. 앱에서 검사하므로 작은 고비용 요청이나 모든 동시 요청의 메모리를 제한하는 기능은 아니에요.

외부에서 앱의 backend 포트나 MySQL에 직접 연결할 수 없어요. backend는 LB의 NSG만, DB는 앱 VM의 private IP만 허용해요. `/actuator/metrics`는 공개하지 않아요.

앱 CI/CD는 LB·WAF를 변경하지 않아요. SSH로 인증된 앱 서버가 LB를 거쳐 배포를 확인하며 서버 공인 IP만 점검용으로 허용해요. 해외 GitHub 러너는 HTTP 예외 대상이 아니에요. HTTP는 HTTPS로 이동하고, 인증서 갱신용 HTTP-01 경로만 국가 차단·요청 제한에서 제외해요.

향후 서비스는 같은 LB에 hostname과 backend set을 추가할 수 있어요. WAF의 허용 hostname 목록도 함께 갱신해야 해요. Tooja의 국가·요청 제한은 해당 hostname에만 적용해요.
