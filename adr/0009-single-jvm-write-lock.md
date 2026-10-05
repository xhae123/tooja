# ADR 0009: SQLite 쓰기를 한 JVM 안에서 직렬화한다

기록일: 2026-10-05 · 결정 출처: 사용자 배포 전제에 따른 구현 선택

## 배경

서버 스케일아웃은 하지 않을 계획이며 SQLite 쓰기 경합을 단순하게 다루고 싶습니다. 읽기와 쓰기를 한 서버에서 수행합니다.

## 결정

공정한 ReentrantLock을 쓰기 트랜잭션 입구에 두고 SQLite WAL을 사용합니다. 잔액·원장은 JVM 캐시로 복제하지 않습니다.

## 선택 이유

한 번에 하나의 쓰기를 실행해 동시 잔액 계산과 SQLite 쓰기 경합을 명시적으로 제어합니다. WAL과 읽기 트랜잭션으로 조회는 일관된 스냅샷을 사용합니다.

## 비교한 대안

여러 쓰기를 동시에 시작해 DB 잠금 재시도로 처리하는 방법보다 현재 예상 규모에서 단순한 제어를 선택했습니다.

## 영향과 한계

잠금은 이 JVM에만 적용됩니다. 여러 앱 인스턴스나 외부 직접 SQL은 이 잠금을 공유하지 않습니다. 직접 DB 변경은 별도 트랜잭션·검증이 필요합니다.

## 현재 상태와 구현 근거

구현됨. JVM에는 요청 제한 카운터가 있으며 금융 원장 캐시는 없습니다.

- [src/main/kotlin/com/tooja/Database.kt](../src/main/kotlin/com/tooja/Database.kt)
- [src/main/kotlin/com/tooja/WebConfig.kt](../src/main/kotlin/com/tooja/WebConfig.kt)

## MySQL 전환 후

단일 앱이라는 전제는 유지해서 JVM 쓰기 잠금도 유지해요. MySQL은 InnoDB 트랜잭션으로 커밋하며 SQLite PRAGMA/WAL 설정은 MySQL 프로필에서 실행하지 않아요. 앱을 여러 대로 늘릴 때에는 이 잠금을 DB row lock 등으로 바꿔야 해요.
