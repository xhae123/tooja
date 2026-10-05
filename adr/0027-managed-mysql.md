# ADR 0027: 운영 DB를 관리형 MySQL로 옮겨요

## 배경

사용자가 OCI에서 무료로 제공하는 MySQL로 변경하기로 했어요. 기존 SQLite 데이터는 전부 유지해야 해요.

## 결정과 이유

Osaka home region에 MySQL.Free 단일 노드를 생성해요. DB를 앱 VM과 분리하고 관리형 자동 백업을 사용해요. 공개 DB 포트 없이 앱 private IP에서만 TLS 연결해요. Spring JDBC·DB 세션·금융 원장 방식은 유지해요.

전환 직전에 쓰기를 멈추고 SQLite backup API로 일관된 사본을 만들어요. 팀·계정·지급·세션·CSRF 컨텍스트·투자·요청 결과·버전·접수 상태의 모든 행과 열을 단일 트랜잭션으로 이전하고 커밋 전후 원본과 전체 값 비교를 수행해요.

## 한계

Always Free는 단일 노드이고 자동 백업 보관이 1일이에요. HA·PITR·수동 OCI 백업은 제공하지 않아요. 이전 후 새 쓰기가 발생하면 원본 SQLite로 단순 복귀해서는 안 돼요.

[기존 선택](0001-kotlin-spring-sqlite.md)의 운영 SQLite 부분을 대체해요. SQLite는 로컬 데모와 계약 테스트에 남겨요.
