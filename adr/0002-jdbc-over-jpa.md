# ADR 0002: JPA 대신 Spring JDBC로 SQL을 직접 작성한다

기록일: 2026-10-05 · 결정 출처: 구현 선택

## 배경

중요한 쓰기는 잔액 차감, 확정 거래, 중복 요청 결과 저장입니다. 읽기에는 팀별 유치액과 투자자 소진 상태 집계가 많습니다. 객체의 복잡한 연관관계를 관리하는 요구는 상대적으로 적습니다.

## 결정

JdbcTemplate으로 SQL과 결과 매핑을 작성하고 TransactionTemplate으로 트랜잭션 범위를 지정합니다. JPA·Hibernate는 사용하지 않습니다.

## 선택 이유

어떤 SQL로 얼마를 차감하고 어떤 조건에서 거절하는지 코드에서 직접 확인할 수 있도록 선택했습니다. SQLite용 Hibernate community dialect를 추가로 운영하는 설정도 줄였습니다. 이는 코드 구조에 대한 판단이며 JDBC가 언제나 더 빠르거나 JPA가 덜 안전하다는 의미는 아닙니다.

## 비교한 대안

JPA는 엔티티·Repository, 변경 감지와 연관관계 관리로 일반 CRUD를 줄입니다. JPA에서도 같은 트랜잭션·제약·명시적 SQL을 구현할 수 있습니다. 이 선택은 필수가 아니며 사용자와 사전 공유하지 못했던 구현 결정입니다.

## 영향과 한계

SQL과 객체 매핑을 직접 관리해야 합니다. ORM 선택과 스키마 마이그레이션 도입은 별개입니다. 현재는 CREATE TABLE IF NOT EXISTS만 있으며, 기존 컬럼·제약 변경의 버전별 마이그레이션은 아직 없습니다.

## 현재 상태와 구현 근거

구현됨. 운영 중 스키마를 바꾸기 전에는 별도의 버전별 마이그레이션 체계를 마련해야 합니다.

- [src/main/kotlin/com/tooja/Database.kt](../src/main/kotlin/com/tooja/Database.kt)
- [src/main/kotlin/com/tooja/InvestmentService.kt](../src/main/kotlin/com/tooja/InvestmentService.kt)

[Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html), [Hibernate community dialect](https://docs.hibernate.org/orm/6.6/dialect/)와 [DB 초기화·마이그레이션](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)에서 기술적 범위를 확인할 수 있습니다.
