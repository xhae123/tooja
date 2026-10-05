# CI/CD와 프론트엔드 연동

기존 OCI A1 인스턴스의 예약 공인 IP를 사용합니다. 현재 연결 주소는 `https://api.leafeep.com`입니다.

- [API Swagger](https://api.leafeep.com/api-docs)
- [현재 배포의 Allure](https://api.leafeep.com/reports/allure/): 단위·통합만 포함
- [배포 커밋·CI 실행·테스트 건수](https://api.leafeep.com/deployment.json)
- [GitHub Actions](https://github.com/xhae123/tooja/actions)

## 배포 흐름

앱·테스트·배포 설정이 바뀐 main push → GitHub-hosted runner에서 Java 17 단위·통합 테스트 → JAR와 이번 실행의 Allure 생성 → 성공한 산출물만 OCI 배포 → 서버 내부에서 nginx를 거친 상태·커밋 확인 순서입니다. PR은 테스트만 합니다. ADR·README·docs 등 문서만 바뀌면 자동 실행하지 않습니다. workflow_dispatch로는 명시적으로 실행할 수 있습니다. E2E 실행·Playwright 설치·기존 E2E 결과 병합은 CI에 없습니다. `ci/package-lock.json`은 Allure CLI만 설치합니다.

배포된 Allure와 앱은 같은 실행·커밋의 산출물입니다. 실패한 테스트 실행은 GitHub artifact에 남기고 기존 앱·리포트를 유지합니다. 각 실행의 증거는 14일 보관합니다. 현재 서버의 Allure는 최신 성공 배포 결과이며 과거 E2E HTML은 저장소의 별도 실행 기록입니다. Swagger는 실제 앱의 컨트롤러에서 자동 생성합니다.

SSH 배포 키는 oci environment에 저장하고 main 브랜치에서만 사용합니다. 서버는 해당 키의 접속을 배포 수신 명령으로 제한합니다. 기존 관리자 SSH 키를 Actions에 등록하지 않습니다. 배포 패키지는 JAR checksum, 파일 allowlist, 커밋·테스트 메타데이터를 검사합니다.

## nginx와 데이터 보존

공용 nginx 연결은 최초 한 번 설정하고, 보안 정책은 운영자가 별도로 관리합니다. 외부 80번 요청을 Docker edge 네트워크의 `tooja-app:8080`으로 프록시하며 앱은 host port를 공개하지 않습니다. nginx는 Docker DNS를 재조회하므로 앱 컨테이너 IP가 바뀌어도 배포마다 설정 복사·reload·컨테이너 재시작을 하지 않습니다.

release는 `/home/ubuntu/services/tooja/releases/`에, DB는 별도 `data/`에 저장합니다. 배포 전에 기존 SQLite DB를 backup API로 백업하고, 앱 시작·nginx 경유 상태 확인에 실패하면 이전 앱 release로 복귀합니다. 이 롤백은 DB를 자동으로 과거로 되돌리지 않습니다. 스키마 변경 때는 별도의 검토된 마이그레이션이 필요합니다.

production 프로필의 수동 등록 모드는 재시작·재배포 때 데모를 자동 삽입하지 않습니다. 데모 데이터도 배포 성공 후 실제 SQLite DB에 직접 넣습니다. 코드 해시에 사용한 CODE_PEPPER는 서버에 보존하며 임의로 교체하지 않습니다.

## 로컬 프론트엔드: Vite 프록시 예시

브라우저가 OCI API를 직접 호출하면 localhost와 다른 출처가 되고 SameSite 세션 쿠키와 Origin 검증에 걸립니다. 브라우저는 자기 개발 서버의 `/api/v1`을 호출하고 Vite가 OCI로 전달하도록 설정하세요.

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
        },
      },
    },
  },
});
```

`fetch('/api/v1/investor/csrf', { credentials: 'same-origin' })`처럼 상대 주소로 호출합니다. 로그인/투자/별칭 변경/로그아웃에는 받은 역할별 `X-CSRF-Token`을 넣습니다. 경로를 rewrite하지 마세요. 세션 쿠키의 Path와 API 경로를 그대로 유지해야 합니다. 관리자와 투자자 쿠키·CSRF 토큰은 각각 관리합니다. Next.js 등 다른 개발 서버도 동일한 경로·Origin 전달 규칙으로 프록시를 구성합니다.

지금 HTTP 접속에 맞춰 Secure 쿠키는 false입니다. 행사 HTTPS 주소로 전환할 때 APP_ORIGIN과 SECURE_COOKIES=true를 함께 설정하고 프록시 목적지도 변경합니다. HTTPS 웹 페이지가 HTTP API를 브라우저에서 직접 호출하는 구성은 mixed content 차단 대상입니다.

## 직접 DB 조작과 작업 기록

[AGENTS.md](../AGENTS.md)의 절차를 따릅니다. 모든 인프라 작업은 Git 제외 `.local/infra/`에, SQL·트랜잭션·변경 전후 건수·검증·복구 기록은 `.local/db/`에 보관합니다. runtime.env, private key, pepper, 실제 사용자 데이터는 작업 기록·커밋·CI artifact에 넣지 않습니다.

## 해외 접근 차단 이후

[nginx 접속 정책](edge-protection.md)에 따라 해외 IP의 HTTP 접근은 거절됩니다. GitHub Actions 배포 검증은 SSH로 연결된 서버 내부에서 nginx를 거쳐 수행하고, 일치하는 배포 커밋 증거를 Actions에서 확인합니다. 한국 외부 IP에서의 실제 접근은 별도 운영 검증 대상입니다. 앱 배포는 nginx 설정을 변경하거나 reload하지 않습니다.

## 이전 배포 산출물 삭제

새 버전의 health·OpenAPI·Allure·커밋 검증에 성공하면 이전 tooja 이미지와 모든 이전 릴리스 디렉터리(JAR·배포 파일·리포트)를 삭제합니다. 현재 릴리스만 보존합니다. 빌드는 tooja-release 전용 builder에서 수행하고 성공 후 그 캐시를 비웁니다. nginx·certbot 이미지나 다른 builder 캐시는 자동 정리 대상이 아닙니다. DB와 배포 전 DB 백업은 보존합니다. 새 버전 검증 실패 전에는 이전 버전을 삭제하지 않으므로 실패 복구가 가능하지만, 성공 후 이전 커밋으로 돌아가려면 CI/CD를 통해 재배포해야 합니다.

## HTTPS 연결

주소는 https://api.leafeep.com이에요. 서버 runtime.env의 APP_ORIGIN은 이 주소, SECURE_COOKIES는 true로 설정해요. 배포 검증은 SSH 안에서 이 호스트를 loopback으로 resolve해 nginx와 TLS 인증서를 확인해요. 인증서 검증을 끄지 않아요.

nginx HTTPS 설정과 certbot은 운영자가 한 번 연결해요. HTTP-01 인증서 경로만 해외에도 열고 앱·API는 한국 IP만 허용해요. certbot의 기존 갱신 작업과 별도 tooja-certificate-reload.timer가 인증서 변경 시 nginx 설정 검사 후 reload해요. 이 timer와 nginx 설정은 앱 CI/CD에서 재배포하지 않아요.
