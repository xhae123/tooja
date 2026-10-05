package com.tooja

import io.qameta.allure.Allure
import io.qameta.allure.model.StepResult
import io.qameta.allure.model.Status
import io.qameta.allure.model.StatusDetails
import java.util.UUID

object Steps {
    fun step(name: String) = Allure.step(name)
    fun <T> step(name: String,block: () -> T): T {
        val id=UUID.randomUUID().toString();val lifecycle=Allure.getLifecycle();lifecycle.startStep(id,StepResult().setName(name))
        try { val result=block();lifecycle.updateStep(id){it.status=Status.PASSED};return result }
        catch(e: Throwable) { lifecycle.updateStep(id){it.status=if(e is AssertionError)Status.FAILED else Status.BROKEN;it.statusDetails=StatusDetails().setMessage(e.message)};throw e }
        finally {lifecycle.stopStep(id)}
    }
}
