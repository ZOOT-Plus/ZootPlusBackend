package plus.maa.backend.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import plus.maa.backend.common.controller.PagedDTO
import plus.maa.backend.config.doc.RequireJwt
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.controller.request.CommonIdReq
import plus.maa.backend.controller.request.copilotset.CopilotSetCreateReq
import plus.maa.backend.controller.request.copilotset.CopilotSetModCopilotsReq
import plus.maa.backend.controller.request.copilotset.CopilotSetQuery
import plus.maa.backend.controller.request.copilotset.CopilotSetUpdateReq
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.success
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.service.CopilotSetService

/**
 * @author dragove
 * create on 2024-01-01
 */
@Tag(name = "CopilotSet", description = "作业集相关接口")
@RequestMapping("/set")
@RestController
class CopilotSetController(
    private val service: CopilotSetService,
    private val helper: AuthenticationHelper,
) {
    @Operation(summary = "查询作业集列表")
    @ApiResponse(description = "作业集id")
    @PostMapping("/query")
    fun querySets(@RequestBody req: @Valid CopilotSetQuery): MaaResult<PagedDTO<CopilotSetListRes>> =
        success(service.query(req, helper.obtainUserId()))

    @Operation(summary = "查询作业集列表")
    @ApiResponse(description = "作业集id")
    @GetMapping("/get")
    fun getSet(@RequestParam @Parameter(description = "作业id") id: Long): MaaResult<CopilotSetRes> = success(service.get(id))

    @Operation(summary = "创建作业集")
    @ApiResponse(description = "作业集id")
    @RequireJwt
    @PostMapping("/create")
    fun createSet(@RequestBody req: @Valid CopilotSetCreateReq): MaaResult<Long> = success(service.create(req, helper.obtainUserId()))

    @Operation(summary = "添加作业集作业列表")
    @RequireJwt
    @PostMapping("/add")
    fun addCopilotIds(@RequestBody req: @Valid CopilotSetModCopilotsReq): MaaResult<Unit> {
        service.addCopilotIds(req, helper.requireUserId())
        return success()
    }

    @Operation(summary = "添加作业集作业列表")
    @RequireJwt
    @PostMapping("/remove")
    fun removeCopilotIds(@RequestBody req: @Valid CopilotSetModCopilotsReq): MaaResult<Unit> {
        service.removeCopilotIds(req, helper.requireUserId())
        return success()
    }

    @Operation(summary = "更新作业集信息")
    @RequireJwt
    @PostMapping("/update")
    fun updateCopilotSet(@RequestBody req: @Valid CopilotSetUpdateReq): MaaResult<Unit> {
        service.update(req, helper.requireUserId())
        return success()
    }

    @Operation(summary = "删除作业集")
    @RequireJwt
    @PostMapping("/delete")
    fun deleteCopilotSet(@RequestBody req: @Valid CommonIdReq<Long>): MaaResult<Unit> {
        service.delete(req.id, helper.requireUserId())
        return success()
    }
}
