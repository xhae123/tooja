package com.tooja

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.examples.Example
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import com.fasterxml.jackson.databind.MapperFeature

@Configuration
class ApiDocumentation {
    @Bean fun strictTypes()=Jackson2ObjectMapperBuilderCustomizer { it.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS); it.postConfigurer { mapper ->
        listOf(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean).forEach { shape -> mapper.coercionConfigFor(String::class.java).setCoercion(shape,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail) }
    } }
    @Bean fun openApi(): OpenAPI = OpenAPI().info(Info().title("1호선톤 가상 투자 API").version("0.1.0").description("프런트엔드·기획자 연동 문서. 컨트롤러와 DTO에서 자동 생성합니다. 금액은 원 단위 정수, 시간은 UTC입니다. 인증은 독립 DB 세션이며 두 역할 모두 48시간·동시 접속을 허용합니다. 시작·마감, 투자 취소·수정, 자동 감점은 제공하지 않습니다.\n\nPOST·PATCH·DELETE는 허용 Origin과 해당 역할의 X-CSRF-Token이 필요합니다. 오류 검사 순서: 인증 → 서버 요청 제한 → 쿼리 구성 → CSRF → JSON 파싱 → 입력 검증 → 투자 도메인 검증. 저장소·시스템 예외는 실행 중 발생할 수 있습니다. 로그인은 서버 IP 제한 없이 브라우저 localStorage 제한을 사용합니다. 테스트 SSR 화면은 /login, /admin/login, /dashboard에서 확인합니다."))
        .schemaRequirement("InvestorSession",SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name("investor_session"))
        .schemaRequirement("AdminSession",SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name("admin_session"))
    @Bean fun responseContracts()=OpenApiCustomizer { api ->
        io.swagger.v3.core.converter.ModelConverters.getInstance().read(ApiError::class.java).forEach { (name,schema) -> api.components.addSchemas(name,schema) }
        val conditions=mapOf(
            "SESSION_EXPIRED" to "필요한 역할의 세션이 없거나 만료·폐기됨. 반대 역할만 유효한 경우는 403.",
            "FORBIDDEN" to "필요한 세션 쿠키 없음 AND 반대 역할로만 로그인함, 또는 요구 역할의 계정이 비활성 상태임.",
            "RATE_LIMITED" to "직전 60초 내 통과 요청이 보호 API는 역할+계정당 180회, 공개 대시보드는 IP당 300회 이상. 로그인·CSRF 준비에는 이 서버 제한을 적용하지 않음.",
            "CSRF_INVALID" to "Origin이 설정된 웹 출처와 다름/누락 OR X-CSRF-Token이 현재 역할의 세션·로그인 준비 컨텍스트와 다름/누락/만료.",
            "INVALID_REQUEST" to "JSON 문법·본문 객체·필드 타입·알 수 없는 본문 필드가 잘못됨, application/json이 아님, 또는 DELETE에 본문을 보냄.",
            "VALIDATION_FAILED" to "필수 헤더 누락, 잘못된 UUID v4, 금액 범위·10만원 단위 위반, 별칭 규칙 위반, 쿼리 중복·미정의 키, 또는 선택 필터가 전달됨 AND 허용값이 아님.",
            "AUTHENTICATION_FAILED" to "코드가 이 로그인 역할의 활성 계정과 일치하지 않음. 존재·비활성 여부를 구분해서 노출하지 않음.",
            "SELF_INVESTMENT_FORBIDDEN" to "새 투자 AND PARTICIPANT의 소속팀 == 투자 대상팀.",
            "ALREADY_INVESTED" to "새 투자 AND 본인 팀 아님 AND 동일 투자자·팀의 확정 거래가 존재함.",
            "INSUFFICIENT_BALANCE" to "새 투자 AND 앞선 검증 통과 AND 요청 금액 > 트랜잭션 검증 시점의 잔액.",
            "IDEMPOTENCY_KEY_REUSED" to "동일 투자자·요청 키의 기록 존재 AND teamId 또는 amount가 원 요청과 다름.",
            "RESOURCE_NOT_FOUND" to "조회 대상 팀·투자자가 없거나, 거래가 현재 투자자 소유가 아님. 타인 거래도 404.",
            "REQUEST_RESULT_NOT_FOUND" to "인증 투자자·요청 키에 커밋된 결과가 없음. 실패 확정이 아니며 동일 키·본문 재전송 가능.",
            "SERVICE_UNAVAILABLE" to "SQLite 등 필수 저장소 접근 실패. 투자 성공 여부는 동일 키로 다시 확인.",
            "INTERNAL_ERROR" to "다른 정의된 오류로 분류되지 않는 실행 예외. 투자 성공 여부는 동일 키로 다시 확인."
        )
        val fieldDescriptions=mapOf("investorId" to "서버 내부 투자자 ID. 로그인 코드는 응답에 포함하지 않습니다.", "alias" to "관리자가 수정할 수 있는 표시 별칭. 중복은 허용합니다.", "kind" to "PARTICIPANT=참가자, STAFF=운영팀 투자자. 운영팀 투자자는 관리자 권한이 없습니다.", "teamId" to "참가자 소속 또는 투자 대상 팀 번호. 운영팀 투자자의 소속은 null입니다.", "hasLoggedIn" to "최초 로그인·지급을 완료했으면 true.", "issuedAmount" to "최초 로그인 전 0원, 이후 100만원. 재지급하지 않습니다.", "investedAmount" to "확정한 투자 금액 합계, 원 단위.", "balance" to "현재 남은 투자금, 원 단위. 미지급자의 0원은 완료를 뜻하지 않습니다.", "spendingStatus" to "NOT_ACTIVATED=미로그인·미지급, UNSPENT=지급 후 잔액 남음, COMPLETED=전액 투자 완료.", "amount" to "원 단위 정수. 10만~70만원, 10만원 단위.", "asOf" to "응답 데이터의 조회 기준 시각 UTC. 화면에서 한국 시간으로 표시합니다.", "currentBalance" to "과거 거래 당시가 아닌 조회 시점의 현재 잔액.", "balanceAfterInvestment" to "해당 거래 확정 당시 잔액. 멱등 재전송도 같은 영수증을 반환합니다.", "snapshotVersion" to "투자 커밋마다 증가하는 버전. 조회 시각과 구분합니다.", "registeredInvestorCount" to "사전 등록한 전체 투자자 수. 미로그인자 포함, 관리자 제외.", "activatedInvestorCount" to "최초 로그인 성공으로 투자금을 지급받은 사람 수.", "notActivatedCount" to "아직 로그인하지 않아 투자금을 받지 않은 사람 수.", "investedInvestorCount" to "1회 이상 투자한 고유 투자자 수.", "rank" to "경쟁 순위 1·1·3. 화면 배열 인덱스로 순위를 계산하지 마세요.", "allowedAmounts" to "현재 잔액 이하의 선택 가능 금액. 화면은 7개 버튼을 모두 표시하고 나머지는 비활성화합니다.", "investmentState" to "SELF_TEAM → ALREADY_INVESTED → BALANCE_EXHAUSTED → AVAILABLE 우선순위.", "requestId" to "오류 보고용 HTTP 요청 추적 ID. X-Request-ID 헤더와 같습니다.")
        api.components.schemas?.values?.forEach { schema -> schema.properties?.forEach { (name,property) -> fieldDescriptions[name]?.let { property.description=it } } }
        api.components.schemas?.get("Investor")?.properties?.get("kind")?.setEnum(listOf("PARTICIPANT","STAFF"))
        api.components.schemas?.get("Investor")?.properties?.get("spendingStatus")?.setEnum(listOf("NOT_ACTIVATED","UNSPENT","COMPLETED"))
        api.components.schemas?.get("InvestmentInput")?.properties?.get("amount")?.multipleOf=java.math.BigDecimal(100000)
        api.paths?.forEach { (path,item) -> item.readOperationsMap().forEach { (method,op) ->
            if(!path.startsWith("/api/v1/")) return@forEach
            val id=op.operationId
            op.parameters?.forEach { p ->
                if(p.name=="kind") { p.schema.setEnum(listOf("PARTICIPANT","STAFF"));p.description="생략하면 전체. 전달한 값이 허용값이 아니면 422." }
                if(p.name=="spendingStatus") { p.schema.setEnum(listOf("NOT_ACTIVATED","UNSPENT","COMPLETED"));p.description="미지급·미소진·완료를 구분합니다. 생략하면 전체." }
                if(p.name in listOf("Idempotency-Key","requestKey")) { p.schema.format="uuid";p.description="논리적 투자마다 UUID v4. 재전송은 같은 값을 사용합니다." }
            }
            val protected= !op.security.isNullOrEmpty();val errors=linkedMapOf<String,MutableList<String>>()
            fun add(status: String,vararg codes: String) { errors.getOrPut(status){mutableListOf()}.addAll(codes) }
            if(protected){add("401","SESSION_EXPIRED");add("403","FORBIDDEN");add("429","RATE_LIMITED")}
            if(id=="getDashboard")add("429","RATE_LIMITED")
            if(method.name in listOf("POST","PATCH","DELETE")) { add("403","CSRF_INVALID");add("400","INVALID_REQUEST");op.addParametersItem(io.swagger.v3.oas.models.parameters.Parameter().`in`("header").name("X-CSRF-Token").required(true).description("같은 역할의 로그인 또는 로그인 준비 API에서 받은 csrfToken").schema(Schema<String>().type("string"))) }
            add("422","VALIDATION_FAILED")
            if(id in listOf("investorLogin","adminLogin"))add("401","AUTHENTICATION_FAILED")
            if(id=="createInvestment"){add("403","SELF_INVESTMENT_FORBIDDEN");add("409","IDEMPOTENCY_KEY_REUSED","ALREADY_INVESTED","INSUFFICIENT_BALANCE")}
            if(id in listOf("getTeam","getMyInvestment","getInvestorDetail","updateInvestorAlias"))add("404","RESOURCE_NOT_FOUND")
            if(id=="getInvestmentRequest")add("404","REQUEST_RESULT_NOT_FOUND")
            add("500","INTERNAL_ERROR");add("503","SERVICE_UNAVAILABLE")
            errors.forEach { (status,codes) ->
                val media=MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ApiError"))
                codes.forEach { code -> media.addExamples(code,Example().summary(code).description(conditions[code]).value(ApiError(ErrorBody(code,conditions[code]!!),"req_example"))) }
                op.responses.addApiResponse(status,ApiResponse().description(codes.joinToString(" / "){"$it: ${conditions[it]}"}).content(Content().addMediaType("application/json",media)))
            }
            if(id.endsWith("Logout")) { op.responses.remove("200");op.responses.addApiResponse("204",ApiResponse().description("로그아웃 성공 · 본문 없음")) }
        } }
    }
}
