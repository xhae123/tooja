package com.tooja

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@Controller
class Pages {
    private fun page(model: Model,id: String,detail: String=""): String { model.addAttribute("screen",id);model.addAttribute("detail",detail);return "page" }
    // TEMPORARY: remove the demoAccess flag/panel before using real event codes.
    @GetMapping("/") fun root(m: Model): String {
        m.addAttribute("demoAccess",true)
        return page(m,"P01")
    }
    @GetMapping("/login") fun login(m: Model)=page(m,"P01")
    @GetMapping("/invest") fun home(m: Model)=page(m,"P02")
    @GetMapping("/invest/teams/{teamId}") fun team(m: Model,@PathVariable teamId: String)=page(m,"P03",teamId)
    @GetMapping("/invest/success/{investmentId}") fun success(m: Model,@PathVariable investmentId: String)=page(m,"P04",investmentId)
    @GetMapping("/invest/history") fun history(m: Model)=page(m,"P05")
    @GetMapping("/admin/login") fun adminLogin(m: Model)=page(m,"A01")
    @GetMapping("/admin") fun overview(m: Model)=page(m,"A02")
    @GetMapping("/admin/investors") fun investors(m: Model)=page(m,"A03")
    @GetMapping("/admin/investors/{investorId}") fun investor(m: Model,@PathVariable investorId: String)=page(m,"A04",investorId)
    @GetMapping("/dashboard") fun dashboard(m: Model)=page(m,"D01")
}
