# CI/CD와 프론트엔드 연동

기존 OCI A1 인스턴스의 예약 공인 IP를 사용합니다. 현재 연결 주소는 `https://api.leafeep.com`입니다.

- [API Swagger](https://api.leafeep.com/api-docs)
- [현재 배포의 Allure](https://api.leafeep.com/reports/allure/): 단위·통합만 포함
- [배포 커밋·CI 실행·테스트 건수](https://api.leafeep.com/deployment.json)
- [GitHub Actions](https://github.com/xhae123/tooja/actions)

## 배포 흐름

앱·테스트·배포 설정이 바뀐 main push → GitHub-hosted runner에서 Java 17 단위·통합 테스트 → JAR와 이번 실행의 Allure 생성 → 성공한 산출물만 OCI 배포 → SSH로 인증된 서버에서 OCI LB를 거친 상태·커밋 확인 순서입니다. PR은 테스트만 합니다. ADR·README·docs 등 문서만 바뀌면 자동 실행하지 않습니다. workflow_dispatch로는 명시적으로 실행할 수 있습니다. E2E 실행·Playwright 설치·기존 E2E 결과 병합은 CI에 없습니다. `ci/package-lock.json`은 Allure CLI만 설치합니다.

배포된 Allure와 앱은 같은 실행·커밋의 산출물입니다. 실패한 테스트 실행은 GitHub artifact에 남기고 기존 앱·리포트를 유지합니다. 각 실행의 증거는 14일 보관합니다. 현재 서버의 Allure는 최신 성공 배포 결과이며 과거 E2E HTML은 저장소의 별도 실행 기록입니다. Swagger는 실제 앱의 컨트롤러에서 자동 생성합니다.

SSH 배포 키는 oci environment에 저장하고 main 브랜치에서만 사용합니다. 서버는 해당 키의 접속을 배포 수신 명령으로 제한합니다. 기존 관리자 SSH 키를 Actions에 등록하지 않습니다. 배포 패키지는 JAR checksum, 파일 allowlist, 커밋·테스트 메타데이터를 검사합니다.

## 진입점과 데이터 보존

OCI LB+WAF → 앱 VM → private MySQL로 연결해요. 앱의 18080은 loopback과 private IP에만 바인딩하며 NSG에서 LB만 허용해요. LB·WAF는 앱 배포마다 변경하지 않아요.

운영 DB는 Always Free MySQL이고 `production,mysql` 프로필을 사용해요. Flyway가 버전이 있는 SQL 마이그레이션을 적용하며 Hibernate/JPA 자동 DDL은 사용하지 않아요. 적용된 파일을 고치지 않고 다음 버전 파일을 추가해요. 기존 SQLite의 9개 테이블은 전체 행·열을 비교해 옮기며 코드 해시·세션·잔액·원장을 보존해요.

production 수동 등록 모드는 재시작·재배포 때 데모를 자동 삽입하지 않아요. CODE_PEPPER는 기존 값을 유지해요. 배포 실패 시 이전 앱으로 복귀하더라도 DB는 자동으로 과거 상태로 돌아가지 않아요. 스키마 변경은 이전 버전과의 호환성도 검토해야 해요.

Always Free DB는 기본 자동 백업이 1일이고 PITR·수동 OCI 백업·HA를 제공하지 않아요. 운영자가 직접 데이터를 수정할 때에는 일관된 논리 백업과 트랜잭션·전후 검증이 필요해요.

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

운영은 HTTPS와 Secure 쿠키를 사용해요. 로컬 프록시 방법은 [프론트엔드 문서](frontend.md)에 있어요.

## 직접 DB 작업과 기록

[AGENTS.md](../AGENTS.md)의 절차를 따라요. 모든 인프라 기록은 로컬 oci-man 스킬의 `infra/tooja/`, DB 작업은 그 안의 `db/`에 보관해요. 실제 데이터·비밀값은 기록이나 공개 저장소·CI artifact에 넣지 않아요.

## 인증서와 배포 확인

certbot은 HTTP-01로 인증서를 갱신해요. 별도 `tooja-lb-certificate-sync.timer`가 변경된 인증서만 LB에 반영하고 HTTPS 인증서 fingerprint를 확인해요. 앱 CI/CD는 이 작업을 실행하지 않아요.

배포 확인은 SSH로 인증된 서버에서 LB의 HTTPS 주소를 거쳐 health·OpenAPI·Allure·커밋을 검사해요. LB의 backend 상태 반영을 기다리며 인증서 검증은 끄지 않아요. 자세한 보호 범위는 [접속 보호](edge-protection.md)를 보세요.

## 이전 산출물 삭제

새 버전의 검증이 성공하면 이전 Tooja 이미지·릴리스(JAR·배포 파일·Allure)를 전부 삭제하고 현재 버전만 남겨요. 전용 builder 캐시만 정리해요. DB와 DB 백업은 삭제 대상이 아니에요. 성공 후 과거 커밋으로 돌아가려면 Actions로 다시 배포해요.
