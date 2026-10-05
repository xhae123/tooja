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
    @Bean fun strictTypes()=Jackson2ObjectMapperBuilderCustomizer { it.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS); it.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS); it.postConfigurer { mapper ->
        listOf(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean).forEach { shape -> mapper.coercionConfigFor(String::class.java).setCoercion(shape,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail) }
    } }
    @Bean fun openApi(): OpenAPI = OpenAPI().servers(listOf(io.swagger.v3.oas.models.servers.Server().url("/").description("문서를 연 주소와 같은 출처에서 호출해요."))).info(Info().title("1호선톤 가상 투자 API").version("0.1.0").description("프런트엔드·기획자 연동 문서. 컨트롤러와 DTO에서 자동 생성합니다. 금액은 원 단위 정수, 시간은 UTC입니다. 인증은 독립 DB 세션이며 두 역할 모두 48시간·동시 접속을 허용합니다. 관리자는 전체 투자를 중지·재개할 수 있습니다. 투자 취소·수정, 자동 감점과 결과 동결은 제공하지 않습니다.\n\nPOST·PATCH·DELETE는 허용 Origin과 해당 역할의 X-CSRF-Token이 필요합니다. 브라우저는 웹과 API가 같은 scheme·host·port인 동일 출처에서 호출합니다. 세션 쿠키는 HttpOnly이므로 JavaScript로 읽지 않습니다. fetch는 credentials: \"same-origin\"으로 호출하세요. 읽기 API에는 CSRF가 필요 없습니다. 오류 검사는 WAF 보안·IP 요청 제한 → 쓰기 본문 크기 → 인증 → 서버 요청 제한 → 쿼리 키·중복 → DELETE 본문 → CSRF → 입력 바인딩·검증 → 도메인 검증 순이며 첫 실패를 반환합니다. 도메인 전 바인딩·검증은 Spring 처리 순서를 따릅니다. 저장소·시스템 예외는 실행 중 발생할 수 있습니다. 로그인 화면은 브라우저 localStorage 제한을 사용합니다. 운영 WAF는 로그인도 포함하여 IP당 60초 동안 30,000건을 초과하면 60초 제한합니다. 테스트 SSR 화면은 /login, /admin/login, /dashboard에서 확인합니다."))
        .schemaRequirement("InvestorSession",SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name("investor_session"))
        .schemaRequirement("AdminSession",SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.COOKIE).name("admin_session"))
    @Bean fun responseContracts()=OpenApiCustomizer { api ->
        io.swagger.v3.core.converter.ModelConverters.getInstance().read(ApiError::class.java).forEach { (name,schema) -> api.components.addSchemas(name,schema) }
        val conditions=mapOf(
            "REQUEST_BODY_TOO_LARGE" to "POST·PATCH·DELETE의 Content-Length가 1,048,576바이트를 초과함 OR 길이가 없는 본문을 실제로 읽은 크기가 1,048,576바이트를 초과함. 인증·CSRF 검사 전에 거절해요.",
            "SESSION_EXPIRED" to "이 API의 역할 세션이 유효하지 않음 AND (해당 역할 쿠키가 있거나 반대 역할의 유효 세션도 없음). 로그인 화면으로 이동.",
            "FORBIDDEN" to "필요한 세션 쿠키 없음 AND 반대 역할로만 로그인함, 또는 요구 역할의 계정이 비활성 상태임.",
            "RATE_LIMITED" to "직전 60초 내 통과 요청이 보호 API는 역할+계정당 180회, 공개 API는 서버 연결 주소당 30,000회 이상. 운영 WAF는 클라이언트 IP당 60초 동안 30,000건을 초과하면 60초 제한. 로그인·CSRF 준비에는 이 서버 제한을 적용하지 않음.",
            "CSRF_INVALID" to "Origin이 설정된 웹 출처와 다름/누락 OR X-CSRF-Token이 현재 역할의 세션·로그인 준비 컨텍스트와 다름/누락/만료.",
            "INVALID_REQUEST" to "본문이 없거나 JSON 객체로 파싱할 수 없음 OR 필수 문자열 필드(code/alias/status) 누락/null OR 미정의 본문 필드 존재 OR 필드 타입 불일치 OR 지원하지 않는 Content-Type.",
            "VALIDATION_FAILED" to "필수 헤더 누락, 잘못된 UUID v4, 금액 범위·10만원 단위 위반, 별칭 규칙 위반, 쿼리 중복·미정의 키, 또는 선택 필터가 전달됨 AND 허용값이 아님.",
            "AUTHENTICATION_FAILED" to "코드가 이 로그인 역할의 활성 계정과 일치하지 않음. 존재·비활성 여부를 구분해서 노출하지 않음.",
            "INVESTMENT_PAUSED" to "동일 키의 기존 결과가 없음 AND 투자 접수 상태가 PAUSED. 요청 키를 소비하지 않으며 재개 후 같은 키·본문 재전송 가능.",
            "SELF_INVESTMENT_FORBIDDEN" to "새 투자 AND 투자 상태 RUNNING AND PARTICIPANT의 소속팀 == 투자 대상팀.",
            "ALREADY_INVESTED" to "새 투자 AND 투자 상태 RUNNING AND 본인 팀 아님 AND 동일 투자자·팀의 확정 거래가 존재함.",
            "INSUFFICIENT_BALANCE" to "새 투자 AND 앞선 검증 통과 AND 요청 금액 > 트랜잭션 검증 시점의 잔액.",
            "IDEMPOTENCY_KEY_REUSED" to "동일 투자자·요청 키의 기록 존재 AND teamId 또는 amount가 원 요청과 다름.",
            "RESOURCE_NOT_FOUND" to "조회 대상 팀·투자자가 없거나, 거래가 현재 투자자 소유가 아님. 타인 거래도 404.",
            "REQUEST_RESULT_NOT_FOUND" to "인증 투자자·요청 키에 커밋된 결과가 없음. 실패 확정이 아니며 동일 키·본문 재전송 가능.",
            "SERVICE_UNAVAILABLE" to "SQLite 등 필수 저장소 접근 실패. 투자 성공 여부는 동일 키로 다시 확인.",
            "INTERNAL_ERROR" to "다른 정의된 오류로 분류되지 않는 실행 예외. 투자 성공 여부는 동일 키로 다시 확인."
        )
        val fieldDescriptions=mapOf("investmentStatus" to "전체 투자 접수 상태. PAUSED이면 팀별 상태가 AVAILABLE이어도 투자 버튼을 차단합니다.", "csrfToken" to "이 역할의 쓰기 요청 X-CSRF-Token 헤더 값. 로그인 성공 후 새 값으로 교체합니다.", "expiresAt" to "세션 만료 시각(UTC). 발급 후 48시간 고정이며 조회로 연장되지 않습니다.", "serviceName" to "부스의 서비스 표시 이름.", "description" to "부스의 서비스 소개.", "myInvestmentAmount" to "이 투자자가 이 팀에 확정한 투자액. 아직 투자하지 않았으면 0.", "investmentId" to "확정 거래 ID. 내 거래 상세 경로에서 사용합니다.", "confirmedAt" to "거래 확정 시각(UTC).", "raisedAmount" to "이 팀에 확정된 전체 투자액 합계(원).", "count" to "이 응답 목록의 전체 항목 수. 페이지네이션은 없습니다.", "totalIssuedAmount" to "최초 로그인한 투자자에게 실제 지급한 금액 합계(원).", "totalInvestedAmount" to "확정 투자 금액 합계(원).", "totalBalance" to "지급받은 투자자의 남은 투자금 합계(원).", "completedCount" to "지급 후 잔액이 0원인 투자자 수. 미지급자 제외.", "unspentCount" to "지급 후 잔액이 남은 투자자 수.", "originalHttpStatus" to "저장된 투자 거절의 HTTP 상태. 이 결과 조회 자체는 HTTP 200입니다.", "message" to "화면 표시용 오류 안내. 분기 처리는 message가 아닌 code로 하세요.", "details" to "추가 오류 정보. 입력 오류는 fieldErrors 배열을 포함할 수 있습니다.", "investorId" to "서버 내부 투자자 ID. 로그인 코드는 응답에 포함하지 않습니다.", "alias" to "관리자가 수정할 수 있는 표시 별칭. 중복은 허용합니다.", "kind" to "PARTICIPANT=참가자, STAFF=운영팀 투자자. 운영팀 투자자는 관리자 권한이 없습니다.", "teamId" to "참가자 소속 또는 투자 대상 팀 번호. 운영팀 투자자의 소속은 null입니다.", "hasLoggedIn" to "최초 로그인·지급을 완료했으면 true.", "issuedAmount" to "최초 로그인 전 0원, 이후 100만원. 재지급하지 않습니다.", "investedAmount" to "확정한 투자 금액 합계, 원 단위.", "balance" to "현재 남은 투자금, 원 단위. 미지급자의 0원은 완료를 뜻하지 않습니다.", "spendingStatus" to "NOT_ACTIVATED=미로그인·미지급, UNSPENT=지급 후 잔액 남음, COMPLETED=전액 투자 완료.", "amount" to "원 단위 정수. 10만~70만원, 10만원 단위.", "asOf" to "응답 데이터의 조회 기준 시각 UTC. 화면에서 한국 시간으로 표시합니다.", "currentBalance" to "과거 거래 당시가 아닌 조회 시점의 현재 잔액.", "balanceAfterInvestment" to "해당 거래 확정 당시 잔액. 멱등 재전송도 같은 영수증을 반환합니다.", "snapshotVersion" to "투자 커밋마다 증가하는 버전. 조회 시각과 구분합니다.", "registeredInvestorCount" to "사전 등록한 전체 투자자 수. 미로그인자 포함, 관리자 제외.", "activatedInvestorCount" to "최초 로그인 성공으로 투자금을 지급받은 사람 수.", "notActivatedCount" to "아직 로그인하지 않아 투자금을 받지 않은 사람 수.", "investedInvestorCount" to "1회 이상 투자한 고유 투자자 수.", "rank" to "경쟁 순위 1·1·3. 화면 배열 인덱스로 순위를 계산하지 마세요.", "allowedAmounts" to "현재 잔액 이하의 선택 가능 금액. 화면은 7개 버튼을 모두 표시하고 나머지는 비활성화합니다.", "investmentState" to "SELF_TEAM → ALREADY_INVESTED → BALANCE_EXHAUSTED → AVAILABLE 우선순위.", "requestId" to "오류 보고용 HTTP 요청 추적 ID. X-Request-ID 헤더와 같습니다.")
        api.components.schemas?.values?.forEach { schema -> schema.properties?.forEach { (name,property) -> fieldDescriptions[name]?.let { property.description=it } } }
        api.components.schemas?.get("InvestmentStatus")?.properties?.apply {
            get("status")?.description="RUNNING=신규 투자 접수, PAUSED=신규 투자 중지. 로그인·조회는 계속 가능합니다."
            get("revision")?.description="실제 상태 변경 때만 증가하는 버전. 초기값 0이며 투자 snapshotVersion과 별개입니다."
            get("updatedAt")?.apply { description="마지막 상태 변경 시각 UTC. 아직 변경하지 않았으면 null."; nullable=true }
        }
        api.components.schemas?.get("InvestmentStatusInput")?.properties?.get("status")?.description="목표 상태 RUNNING 또는 PAUSED. 필수 문자열이며 알 수 없는 값·누락·null은 400 INVALID_REQUEST입니다. 같은 상태 재요청은 추가 변경이 없습니다."
        api.components.schemas?.get("Investor")?.properties?.get("kind")?.setEnum(listOf("PARTICIPANT","STAFF"))
        api.components.schemas?.get("Investor")?.properties?.get("spendingStatus")?.setEnum(listOf("NOT_ACTIVATED","UNSPENT","COMPLETED"))
        api.components.schemas?.get("InvestmentInput")?.properties?.get("amount")?.multipleOf=java.math.BigDecimal(100000)
        api.components.schemas?.get("RequestResult")?.properties?.get("status")?.apply { setEnum(listOf("SUCCEEDED","REJECTED")); description="투자 요청의 확정 결과" }
        api.components.schemas?.get("RequestResult")?.properties?.get("status")?.description="SUCCEEDED=투자 확정 성공, REJECTED=투자 거절 확정. 조회 자체의 HTTP 상태는 둘 다 200."
        api.components.schemas?.get("Team")?.properties?.get("investmentState")?.setEnum(listOf("SELF_TEAM","ALREADY_INVESTED","BALANCE_EXHAUSTED","AVAILABLE"))
        api.components.schemas?.get("InvestmentInput")?.properties?.get("teamId")?.description="투자 대상 팀 번호(1~20). 참가자는 소속팀에 투자할 수 없습니다."
        api.components.schemas?.get("LoginInput")?.properties?.get("code")?.description="ASCII 숫자 4자리 문자열. 재사용하는 사전 발급 코드이며 앞자리 0을 유지합니다. 숫자 타입은 400입니다."
        api.components.schemas?.get("AliasInput")?.properties?.get("alias")?.description="새 표시 별칭. 1~40자, 앞뒤 공백·CR/LF 불가. 중복 허용, 자동 trim 없음."
        api.paths?.forEach { (path,item) -> item.readOperationsMap().forEach { (method,op) ->
            if(!path.startsWith("/api/v1/")) return@forEach
            val id=op.operationId
            val exampleMessages=mapOf(
                "REQUEST_BODY_TOO_LARGE" to "요청 본문은 1MiB 이하여야 합니다.",
                "SESSION_EXPIRED" to "다시 로그인해 주세요.", "FORBIDDEN" to "이 역할의 로그인 권한이 필요합니다.",
                "RATE_LIMITED" to "요청이 많습니다. 잠시 후 다시 시도해 주세요.", "CSRF_INVALID" to "인증 준비 정보가 만료되었거나 일치하지 않습니다.",
                "INVALID_REQUEST" to "JSON 본문·타입·필드 구성을 확인해 주세요.", "VALIDATION_FAILED" to "입력값을 확인해 주세요.",
                "AUTHENTICATION_FAILED" to "코드가 올바르지 않습니다.", "SELF_INVESTMENT_FORBIDDEN" to "내 팀에는 투자할 수 없습니다.",
                "INVESTMENT_PAUSED" to "지금은 투자가 중지되어 있어요. 관리자가 재개하면 다시 투자할 수 있어요.",
                "ALREADY_INVESTED" to "같은 팀에는 한 번만 투자할 수 있습니다.", "INSUFFICIENT_BALANCE" to "남은 투자금이 부족합니다.",
                "IDEMPOTENCY_KEY_REUSED" to "같은 요청 키를 다른 투자에 사용할 수 없습니다.", "RESOURCE_NOT_FOUND" to "거래를 찾을 수 없습니다.",
                "REQUEST_RESULT_NOT_FOUND" to "아직 요청 결과를 찾지 못했습니다. 같은 키로 다시 확인해 주세요.",
                "SERVICE_UNAVAILABLE" to "저장소에 연결할 수 없습니다. 결과를 다시 확인해 주세요.", "INTERNAL_ERROR" to "처리 중 오류가 발생했습니다. 결과를 다시 확인해 주세요."
            ).toMutableMap()
            if(id.endsWith("Logout")) exampleMessages["INVALID_REQUEST"]="로그아웃에는 본문을 보내지 않습니다."
            if(id in listOf("getInvestorDetail","updateInvestorAlias")) exampleMessages["RESOURCE_NOT_FOUND"]="투자자를 찾을 수 없습니다."
            if(id in listOf("getTeam","createInvestment")) exampleMessages["RESOURCE_NOT_FOUND"]="팀을 찾을 수 없습니다."
            val localConditions=conditions.toMutableMap()
            val queryCondition=if(id=="listInvestors") "미정의 쿼리 키 존재 OR 동일 쿼리 키가 2번 이상 전달됨 OR q 길이 > 100 OR teamId가 정수 1~20이 아님 OR kind가 전달됨 AND PARTICIPANT/STAFF가 아님 OR spendingStatus가 전달됨 AND NOT_ACTIVATED/UNSPENT/COMPLETED가 아님." else "쿼리 파라미터가 하나라도 전달됨(이 API는 쿼리를 받지 않음)."
            localConditions["VALIDATION_FAILED"]=queryCondition + when(id) {
                "investorLogin","adminLogin" -> " 또는 code가 문자열로 파싱되었으나 ASCII 숫자 4자리 패턴이 아님."
                "createInvestment" -> " 또는 Idempotency-Key 누락/UUID v4 아님 OR teamId가 1~20 밖 OR amount가 100000~700000 밖 OR amount % 100000 != 0. teamId/amount 누락 또는 null은 현재 바인더에서 0으로 처리되어 범위 검증 422를 반환."
                "getInvestmentRequest" -> " 또는 requestKey가 UUID v4가 아님."
                "getTeam" -> " 또는 teamId 경로값을 Int로 변환할 수 없음."
                "updateInvestorAlias" -> " 또는 alias 길이가 1~40 밖 OR 앞뒤 공백 OR CR/LF 포함."
                else -> ""
            }
            if(id=="updateInvestmentStatus") localConditions["INVALID_REQUEST"]="본문이 없거나 JSON 객체가 아님 OR status 누락/null/문자열 아님 OR status가 RUNNING·PAUSED 중 하나가 아님(대소문자 구분) OR 미정의 본문 필드 OR 지원하지 않는 Content-Type."
            if(id.endsWith("Logout")) localConditions["INVALID_REQUEST"]="DELETE 요청에 본문이 있음(Content-Length > 0 OR Transfer-Encoding 헤더 존재). CSRF보다 먼저 검사합니다."
            if(id=="getInvestmentRequest") localConditions["REQUEST_RESULT_NOT_FOUND"]="현재 투자자 + requestKey로 커밋된 결과가 없음. 미도착·미커밋·다른 투자자의 키 포함. 같은 키·본문으로 재전송하며 실패 확정으로 처리하지 않습니다."
            if(id=="getTeam") localConditions["RESOURCE_NOT_FOUND"]="정수로 변환된 teamId에 해당하는 팀이 없음."
            if(id=="getMyInvestment") localConditions["RESOURCE_NOT_FOUND"]="investmentId에 해당하는 현재 투자자 소유 거래가 없음. 없는 ID와 타인 거래를 구분하지 않습니다."
            if(id in listOf("getInvestorDetail","updateInvestorAlias")) localConditions["RESOURCE_NOT_FOUND"]="investorId에 해당하는 활성 투자자가 없음. 비활성 투자자·관리자 ID 포함."
            if(id=="createInvestment") {
                localConditions["SELF_INVESTMENT_FORBIDDEN"]="새 요청 키 AND 투자 상태 RUNNING AND kind=PARTICIPANT AND 본인 teamId=요청 teamId. 동일 키·본문의 이전 거절이면 그 거절을 그대로 재현."
                localConditions["ALREADY_INVESTED"]="새 요청 키 AND 투자 상태 RUNNING AND 자기 팀 검증 통과 AND 동일 투자자·팀의 확정 거래 존재. 이전 동일 키·본문의 거절도 재현."
                localConditions["INSUFFICIENT_BALANCE"]="새 요청 키 AND 투자 상태 RUNNING AND 자기 팀·기투자 검증 통과 AND amount > 트랜잭션 시점의 잔액. 이전 동일 키·본문의 거절도 재현."
                localConditions["RESOURCE_NOT_FOUND"]="새 요청 키 AND 투자 상태 RUNNING AND 대상 팀이 DB에 없음(정상 초기화는 20개 팀을 모두 등록)."
            }
            op.parameters?.forEach { p ->
                if(p.name=="q") p.description="선택. 별칭 또는 내부 투자자 ID 부분 검색, 대소문자 무시, 최대 100자. 생략 또는 빈 문자열이면 검색 제한 없음."
                if(p.name=="teamId") p.description=if(id=="listInvestors") "선택. 참가자 소속팀 번호 1~20. STAFF는 소속팀이 없어 이 필터와 일치하지 않습니다." else "부스 목록에서 받은 정수 팀 번호. 존재하지 않는 번호는 404."
                if(p.name=="investorId") p.description="투자자 목록에서 받은 내부 ID. 로그인 코드가 아닙니다."
                if(p.name=="investmentId") p.description="내 영수증 또는 거래 목록에서 받은 거래 ID. 타인 거래도 404입니다."
                if(p.name=="kind") { p.schema.setEnum(listOf("PARTICIPANT","STAFF"));p.description="생략하면 전체. 전달한 값이 허용값이 아니면 422." }
                if(p.name=="spendingStatus") { p.schema.setEnum(listOf("NOT_ACTIVATED","UNSPENT","COMPLETED"));p.description="미지급·미소진·완료를 구분합니다. 생략하면 전체." }
                if(p.name in listOf("Idempotency-Key","requestKey")) { p.schema.format="uuid";p.description="논리적 투자마다 UUID v4. 재전송은 같은 값을 사용합니다." }
            }
            op.requestBody?.content?.get("application/json")?.addExamples("request",Example().summary("호출 예시").value(when(id) {
                "investorLogin" -> mapOf("code" to "0037")
                "adminLogin" -> mapOf("code" to "4821")
                "createInvestment" -> mapOf("teamId" to 2,"amount" to 100000)
                "updateInvestmentStatus" -> mapOf("status" to "PAUSED")
                "updateInvestorAlias" -> mapOf("alias" to "새 별칭")
                else -> emptyMap<String,Any>()
            }))
            val protected= !op.security.isNullOrEmpty();val errors=linkedMapOf<String,MutableList<String>>()
            fun add(status: String,vararg codes: String) { errors.getOrPut(status){mutableListOf()}.addAll(codes) }
            if(method.name in listOf("POST","PATCH","DELETE")) add("413","REQUEST_BODY_TOO_LARGE")
            if(protected){add("401","SESSION_EXPIRED");add("403","FORBIDDEN");add("429","RATE_LIMITED")}
            if(id in listOf("getDashboard","getInvestmentStatus"))add("429","RATE_LIMITED")
            if(method.name in listOf("POST","PATCH","DELETE")) { add("403","CSRF_INVALID");add("400","INVALID_REQUEST");op.addParametersItem(io.swagger.v3.oas.models.parameters.Parameter().`in`("header").name("X-CSRF-Token").required(true).description("같은 역할의 로그인 또는 로그인 준비 API에서 받은 csrfToken").schema(Schema<String>().type("string"))) }
            add("422","VALIDATION_FAILED")
            if(id in listOf("investorLogin","adminLogin"))add("401","AUTHENTICATION_FAILED")
            if(id=="createInvestment"){add("409","IDEMPOTENCY_KEY_REUSED","INVESTMENT_PAUSED");add("404","RESOURCE_NOT_FOUND");add("403","SELF_INVESTMENT_FORBIDDEN");add("409","ALREADY_INVESTED","INSUFFICIENT_BALANCE")}
            if(id in listOf("getTeam","getMyInvestment","getInvestorDetail","updateInvestorAlias"))add("404","RESOURCE_NOT_FOUND")
            if(id=="getInvestmentRequest")add("404","REQUEST_RESULT_NOT_FOUND")
            add("500","INTERNAL_ERROR");add("503","SERVICE_UNAVAILABLE")
            errors.forEach { (status,codes) ->
                val media=MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ApiError"))
                codes.forEach { code -> media.addExamples(code,Example().summary(code).description(localConditions[code]).value(ApiError(ErrorBody(code,exampleMessages.getValue(code)),"req_example"))) }
                op.responses.addApiResponse(status,ApiResponse().description(codes.joinToString("\n\n"){"**$it** — ${localConditions[it]}"}).content(Content().addMediaType("application/json",media)))
            }
            val successStatus=if(id=="createInvestment") "201" else "200"
            op.responses[successStatus]?.description=when(id) {
                "createInvestment" -> "투자 확정 또는 같은 키·본문의 기존 성공 영수증 복구. 추가 차감 없음."
                "getInvestmentRequest" -> "요청 결과 조회 성공. SUCCEEDED와 REJECTED 모두 HTTP 200이며 본문의 status로 구분."
                "listMyInvestments","listInvestors" -> "목록 조회 성공. 일치 항목이 없으면 빈 배열과 count=0."
                "investorLogin" -> "투자자 로그인 성공. 최초 로그인에만 100만원 지급. 세션 쿠키 발급 및 CSRF 토큰 교체."
                "adminLogin" -> "관리자 로그인 성공. 세션 쿠키 발급 및 CSRF 토큰 교체. 투자금 지급 없음."
                else -> "조회 또는 변경 성공. 아래 Schema에서 필드별 의미를 확인하세요."
            }
            op.responses["429"]?.addHeaderObject("Retry-After",io.swagger.v3.oas.models.headers.Header().description("재시도까지 기다릴 초 수(정수). 이 시간이 지난 뒤 재호출하세요.").schema(Schema<Int>().type("integer")))
            op.responses["503"]?.addHeaderObject("Retry-After",io.swagger.v3.oas.models.headers.Header().description("현재 구현은 5초. 투자 결과가 불확실하면 같은 요청 키로 확인하세요.").schema(Schema<Int>().type("integer")))
            if(id=="createInvestment") op.responses["201"]?.addHeaderObject("Location",io.swagger.v3.oas.models.headers.Header().description("확정 거래 상세 API 경로.").schema(Schema<String>().type("string")))
            if(id.endsWith("Logout")) { op.responses.remove("200");op.responses.addApiResponse("204",ApiResponse().description("로그아웃 성공 · 본문 없음")) }
        } }
    }
}
