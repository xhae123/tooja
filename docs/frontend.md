# 프론트엔드 연동을 시작할 때 봐주세요

서버 주소는 **https://api.leafeep.com**이에요. API 경로는 `/api/v1`로 시작해요.

## 먼저 여기부터 열어보세요

- [테스트 화면·더미 계정](https://api.leafeep.com/): 데스크톱 오른쪽에 팀·이름·코드가 있어요. 실제 API를 호출하는 임시 화면이라 화면 흐름도 확인할 수 있어요.
- [API 문서](https://api.leafeep.com/api-docs): 요청 값, 응답 필드, 오류가 나는 조건은 여기를 봐주세요.
- [테스트 리포트](https://api.leafeep.com/reports/allure/): 현재 배포의 단위·통합 테스트 결과예요. E2E는 포함하지 않아요.

한국 IP에서만 접속할 수 있어요. 해외 VPN이나 해외에 둔 프록시 서버를 사용하면 `403`이 나올 수 있어요.

## 로컬에서는 프록시를 연결해 주세요

브라우저가 OCI 주소를 직접 호출하면 로그인 쿠키와 CSRF 검사 때문에 연동이 안 돼요. **브라우저는 로컬 개발 서버에 요청하고, 개발 서버가 OCI로 전달**하도록 해주세요.

Vite를 쓰면 아래 설정을 넣으면 돼요.

```ts
import { defineConfig } from 'vite';

const api = 'https://api.leafeep.com';

export default defineConfig({
  server: {
    proxy: {
      '/api/v1': {
        target: api,
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (request) => {
            request.setHeader('Origin', api);
          });
          // 로컬 HTTP 개발 서버에서만 Secure를 제거해 쿠키를 전달해요.
          // 운영 환경에서는 이 처리를 사용하지 마세요.
          proxy.on('proxyRes', (response) => {
            const cookies = response.headers['set-cookie'];
            if (cookies) response.headers['set-cookie'] =
              cookies.map((cookie) => cookie.replace(/;\s*Secure/gi, ''));
          });
        },
      },
    },
  },
});
```

프론트 코드에서는 `fetch('/api/v1/investor/csrf', { credentials: 'same-origin' })`처럼 **상대 경로**로 호출해 주세요. `/api/v1`을 제거하거나 다른 경로로 바꾸면 안 돼요. 다른 개발 서버를 쓰더라도 경로·쿠키·Origin을 전달하는 규칙은 같아요.

## 로그인은 이 순서로 하면 돼요

투자자를 예로 들면 아래 순서예요.

1. `GET /api/v1/investor/csrf`를 호출하고 응답의 `csrfToken`을 보관해요.
2. `POST /api/v1/investor/session`에 `{"code":"6096"}`을 보내요. 헤더에는 `X-CSRF-Token`을 넣어요. 코드는 앞자리 `0`이 사라지지 않도록 **숫자가 아닌 문자열**로 다뤄주세요.
3. 로그인 응답의 **새 `csrfToken`으로 교체**해요. 이후 `POST`·`PATCH`·`DELETE` 요청에도 이 헤더를 넣어요.
4. 새로고침 후에는 `GET /api/v1/investor/session`으로 로그인 상태와 CSRF 토큰을 다시 받아요. 로그아웃은 `DELETE /api/v1/investor/session`이에요.

인증은 **쿠키 기반 세션**이에요. 쿠키는 브라우저가 관리하니 `Authorization: Bearer ...`를 만들 필요는 없어요. 관리자도 코드로 로그인하며, 위 경로의 `investor`를 `admin`으로 바꾸면 돼요. 운영팀 투자자는 관리자와 달라서 `investor` 경로를 사용해요.

투자자와 관리자는 같은 브라우저에서 동시에 로그인할 수 있어요. **두 역할의 CSRF 토큰을 각각 보관**해 주세요. 세션은 로그인 후 48시간이고, `401`이면 해당 역할의 로그인 화면으로 보내주세요.

## 투자 확정에서 이것만 주의해 주세요

먼저 팀 조회 응답의 **`investmentStatus.status`**를 확인해 주세요. `PAUSED`면 투자 버튼을 비활성화하고 중지 안내를 보여주세요. 소개·잔액·내역 조회는 계속 가능해요. `RUNNING`이면 팀별 `investmentState`와 `allowedAmounts`로 투자 가능 여부와 금액 버튼을 표시해 주세요. 확정한 투자는 취소하거나 바꿀 수 없어요.

투자 요청에는 `X-CSRF-Token` 외에 **`Idempotency-Key: UUID v4`**가 필요해요. 한 번의 투자에 키를 하나 만들고, 응답이 끊겼다면 새 키를 만들지 말고 **같은 키·같은 본문**으로 결과를 조회하거나 재전송해 주세요. 복구 순서는 [API 문서의 투자 확정·요청 결과 조회](https://api.leafeep.com/api-docs)를 봐주세요.

현재 서버는 HTTPS라서 `crypto.randomUUID()`를 사용할 수 있어요. HTTP 개발 환경에서 이 함수가 없으면 필요한 경우 [임시 화면의 `investmentRequestKey()`](../src/main/resources/static/app.js)를 참고해 주세요. HTTP에서도 사용할 수 있는 `crypto.getRandomValues()`로 키를 만들어요.

오류는 HTTP 상태와 `error.code`로 구분하고, `error.message`를 안내에 사용해 주세요. `429`에 `Retry-After`가 있으면 그 시간 동안 기다렸다가 다시 요청해 주세요. API별 상세 조건은 [API 문서](https://api.leafeep.com/api-docs)에 있어요.

관리자는 `PATCH /api/v1/admin/investment-status`에 `{"status":"PAUSED"}`로 중지하고, `{"status":"RUNNING"}`으로 재개해요. 관리자 현황에도 상태가 포함돼요. 로그인 없이 상태만 확인하려면 `GET /api/v1/public/investment-status`를 사용하세요.

화면을 연 뒤 관리자가 중지할 수도 있으니 확정 응답의 `409 INVESTMENT_PAUSED`도 처리해 주세요. 이 오류는 요청 키를 소비하지 않아요. 재개 후 같은 키·본문으로 다시 시도할 수 있고, 이미 성공한 요청을 중지 중에 재전송하면 기존 영수증을 받아요.
