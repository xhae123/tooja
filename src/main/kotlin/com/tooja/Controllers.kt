package com.tooja

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import java.net.URI
import java.util.UUID

fun principal(req: HttpServletRequest)=(req.getAttribute("principal") as SessionRecord).accountId
fun requestKey(value: String): String { if(!value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) throw ApiException(422,"VALIDATION_FAILED","Idempotency-Key는 UUID v4여야 합니다.");return UUID.fromString(value).toString() }

@RestController
@RequestMapping(produces=["application/json"])
@Tag(name="01 로그인",description="모두 허용. 투자자와 관리자 코드는 숫자 4자리 문자열이며 두 세션은 독립적입니다.")
class AuthController(private val auth: AuthService,private val service: InvestmentService) {
    @GetMapping("/api/v1/investor/csrf") @Operation(operationId="investorCsrf",summary="[모두 허용] 투자자 로그인 준비",description=OperationDescriptions.INVESTOR_CSRF)
    fun investorCsrf(req: HttpServletRequest,res: HttpServletResponse): Csrf=auth.csrf("investor",req,res)
    @GetMapping("/api/v1/admin/csrf") @Operation(operationId="adminCsrf",summary="[모두 허용] 관리자 로그인 준비",description=OperationDescriptions.ADMIN_CSRF)
    fun adminCsrf(req: HttpServletRequest,res: HttpServletResponse): Csrf=auth.csrf("admin",req,res)
    @PostMapping("/api/v1/investor/session") @Operation(operationId="investorLogin",summary="[모두 허용] 투자자 코드 로그인",description=OperationDescriptions.INVESTOR_LOGIN)
    fun investorLogin(@Valid @RequestBody body: LoginInput,req: HttpServletRequest,res: HttpServletResponse): InvestorSession { val s=auth.login("investor",body.code,req,res);return InvestorSession(service.investor(s.accountId),s.csrf,s.expiresAt) }
    @PostMapping("/api/v1/admin/session") @Operation(operationId="adminLogin",summary="[모두 허용] 관리자 코드 로그인",description=OperationDescriptions.ADMIN_LOGIN)
    fun adminLogin(@Valid @RequestBody body: LoginInput,req: HttpServletRequest,res: HttpServletResponse): AdminSession { val s=auth.login("admin",body.code,req,res);return AdminSession(Admin(s.accountId),s.csrf,s.expiresAt) }
    @GetMapping("/api/v1/investor/session") @Access("investor") @SecurityRequirement(name="InvestorSession") @Operation(operationId="investorSession",summary="[투자자] 로그인 상태 복구",description=OperationDescriptions.INVESTOR_SESSION)
    fun investorSession(req: HttpServletRequest): InvestorSession { val s=req.getAttribute("principal") as SessionRecord;return InvestorSession(service.investor(s.accountId),s.csrf,s.expiresAt) }
    @GetMapping("/api/v1/admin/session") @Access("admin") @SecurityRequirement(name="AdminSession") @Operation(operationId="adminSession",summary="[관리자] 로그인 상태 복구",description=OperationDescriptions.ADMIN_SESSION)
    fun adminSession(req: HttpServletRequest): AdminSession { val s=req.getAttribute("principal") as SessionRecord;return AdminSession(Admin(s.accountId),s.csrf,s.expiresAt) }
    @DeleteMapping("/api/v1/investor/session") @Operation(operationId="investorLogout",summary="[모두 허용] 투자자 로그아웃",description=OperationDescriptions.INVESTOR_LOGOUT)
    fun investorLogout(req: HttpServletRequest,res: HttpServletResponse): ResponseEntity<Void> { auth.logout("investor",req,res);return ResponseEntity.noContent().build() }
    @DeleteMapping("/api/v1/admin/session") @Operation(operationId="adminLogout",summary="[모두 허용] 관리자 로그아웃",description=OperationDescriptions.ADMIN_LOGOUT)
    fun adminLogout(req: HttpServletRequest,res: HttpServletResponse): ResponseEntity<Void> { auth.logout("admin",req,res);return ResponseEntity.noContent().build() }
}
@RestController
@RequestMapping("/api/v1/investor", produces=["application/json"]) @Access("investor") @SecurityRequirement(name="InvestorSession")
@Tag(name="02 투자자",description="참가자는 본인 팀 제외, 운영팀 투자자는 20개 팀 모두 가능합니다.")
class InvestorController(private val service: InvestmentService) {
    @GetMapping("/me") @Operation(operationId="getMyInvestor",summary="[투자자] 내 잔액·소속 조회",description=OperationDescriptions.MY_INVESTOR)
    fun me(req: HttpServletRequest): Investor=service.investor(principal(req))
    @GetMapping("/teams") @Operation(operationId="listTeams",summary="[투자자] 20개 부스 목록",description=OperationDescriptions.LIST_TEAMS)
    fun teams(req: HttpServletRequest): TeamList=service.teams(principal(req))
    @GetMapping("/teams/{teamId}") @Operation(operationId="getTeam",summary="[투자자] 부스 소개·투자 가능 상태",description=OperationDescriptions.GET_TEAM)
    fun team(@PathVariable teamId: Int,req: HttpServletRequest): TeamDetail=service.teamDetail(principal(req),teamId)
    @PostMapping("/investments") @Operation(operationId="createInvestment",summary="[투자자] 투자 확정",description=OperationDescriptions.CREATE_INVESTMENT)
    @ApiResponses(ApiResponse(responseCode="201",description="투자 확정 또는 기존 성공 복구"),ApiResponse(responseCode="409",description="이미 투자한 팀 / 잔액 부족 / 다른 본문으로 요청 키 재사용"))
    fun create(@RequestHeader("Idempotency-Key") key: String,@Valid @RequestBody body: InvestmentInput,req: HttpServletRequest): ResponseEntity<InvestmentReceipt> { Policy.validateAmount(body.amount);val r=service.create(principal(req),requestKey(key),body);return ResponseEntity.created(URI.create("/api/v1/investor/investments/${r.investment.investmentId}")).body(r) }
    @GetMapping("/investment-requests/{requestKey}") @Operation(operationId="getInvestmentRequest",summary="[투자자] 응답이 끊긴 투자 결과 확인",description=OperationDescriptions.GET_REQUEST)
    fun result(@PathVariable requestKey: String,req: HttpServletRequest): RequestResult=service.result(principal(req),requestKey(requestKey))
    @GetMapping("/investments") @Operation(operationId="listMyInvestments",summary="[투자자] 내 거래 최신순 목록",description=OperationDescriptions.LIST_INVESTMENTS)
    fun history(req: HttpServletRequest): InvestmentHistory=service.history(principal(req))
    @GetMapping("/investments/{investmentId}") @Operation(operationId="getMyInvestment",summary="[투자자] 투자 완료 상세",description=OperationDescriptions.GET_INVESTMENT)
    fun detail(@PathVariable investmentId: String,req: HttpServletRequest): InvestmentDetail=service.detail(principal(req),investmentId)
}
@RestController
@RequestMapping("/api/v1/admin", produces=["application/json"]) @Access("admin") @SecurityRequirement(name="AdminSession")
@Tag(name="03 관리자",description="운영팀 투자자와 관리자 권한은 별개입니다. 조회와 별칭 수정만 제공합니다.")
class AdminController(private val service: InvestmentService) {
    @GetMapping("/overview") @Operation(operationId="getOverview",summary="[관리자] 전체·역할별 지급·소진 현황",description=OperationDescriptions.GET_OVERVIEW)
    fun overview(): Overview=service.overview()
    @GetMapping("/investors") @Operation(operationId="listInvestors",summary="[관리자] 투자자 검색·필터",description=OperationDescriptions.LIST_INVESTORS)
    fun list(@RequestParam(required=false) q: String?,@RequestParam(required=false) teamId: Int?,@RequestParam(required=false) kind: String?,@RequestParam(required=false) spendingStatus: String?): InvestorList {
        if(q!=null && q.length>100 || teamId!=null && teamId !in 1..20 || kind!=null && kind !in listOf("PARTICIPANT","STAFF") || spendingStatus!=null && spendingStatus !in listOf("NOT_ACTIVATED","UNSPENT","COMPLETED")) throw ApiException(422,"VALIDATION_FAILED","전달한 필터 값이 허용 범위를 벗어났습니다.")
        return service.list(q,teamId,kind,spendingStatus)
    }
    @GetMapping("/investors/{investorId}") @Operation(operationId="getInvestorDetail",summary="[관리자] 투자자 잔액·거래 조회",description=OperationDescriptions.GET_INVESTOR)
    fun detail(@PathVariable investorId: String): InvestorDetail { val h=service.history(investorId);return InvestorDetail(h.investor,h.items,h.asOf) }
    @PatchMapping("/investors/{investorId}") @Operation(operationId="updateInvestorAlias",summary="[관리자] 별칭 수정",description=OperationDescriptions.UPDATE_ALIAS)
    fun alias(@PathVariable investorId: String,@Valid @RequestBody body: AliasInput): Investor=service.updateAlias(investorId,body.alias)
}
@RestController @RequestMapping(produces=["application/json"]) @Tag(name="04 공개 대시보드")
class PublicController(private val service: InvestmentService) {
    @GetMapping("/api/v1/public/dashboard") @Operation(operationId="getDashboard",summary="[모두 허용] 20개 팀의 실시간 순위",description=OperationDescriptions.GET_DASHBOARD)
    fun dashboard(): Dashboard=service.dashboard()
}
