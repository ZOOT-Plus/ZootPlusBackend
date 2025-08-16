package plus.maa.backend.controller.file
// TODO 摸一下
//import io.swagger.v3.oas.annotations.Operation
//import io.swagger.v3.oas.annotations.Parameter
//import io.swagger.v3.oas.annotations.media.Content
//import io.swagger.v3.oas.annotations.media.Schema
//import io.swagger.v3.oas.annotations.responses.ApiResponse
//import jakarta.servlet.http.HttpServletResponse
//import jakarta.validation.Valid
//import org.springframework.http.MediaType
//import org.springframework.web.bind.annotation.GetMapping
//import org.springframework.web.bind.annotation.PostMapping
//import org.springframework.web.bind.annotation.RequestBody
//import org.springframework.web.bind.annotation.RequestMapping
//import org.springframework.web.bind.annotation.RequestPart
//import org.springframework.web.bind.annotation.RestController
//import org.springframework.web.multipart.MultipartFile
//import plus.maa.backend.config.accesslimit.AccessLimit
//import plus.maa.backend.config.doc.RequireJwt
//import plus.maa.backend.config.security.AuthenticationHelper
//import plus.maa.backend.controller.response.MaaResult
//import plus.maa.backend.controller.response.MaaResult.Companion.fail
//import plus.maa.backend.controller.response.MaaResult.Companion.success
//import plus.maa.backend.service.FileService
//
///**
// * @author LoMu
// * Date  2023-03-31 16:41
// */
//@RestController
//@RequestMapping("file")
//class FileController(
//    private val fileService: FileService,
//    private val helper: AuthenticationHelper,
//) {
//    /**
//     * 支持匿名
//     *
//     * @param file file
//     * @return 上传成功, 数据已被接收
//     */
//    @AccessLimit
//    @PostMapping(value = ["/upload"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
//    fun uploadFile(
//        @RequestPart file: MultipartFile,
//        @RequestPart type: String?,
//        @RequestPart version: String,
//        @RequestPart(required = false) classification: String?,
//        @RequestPart(required = false) label: String,
//    ): MaaResult<String> {
//        fileService.uploadFile(file, type, version, classification, label, helper.obtainUserIdOrIpAddress())
//        return success("上传成功,数据已被接收")
//    }
//
//    @Operation(summary = "下载文件")
//    @ApiResponse(
//        responseCode = "200",
//        content = [Content(mediaType = "application/zip", schema = Schema(type = "string", format = "binary"))],
//    )
//    @RequireJwt
//    @AccessLimit
//    @GetMapping("/download")
//    fun downloadSpecifiedDateFile(
//        @Parameter(description = "日期 yyyy-MM-dd") date: String?,
//        @Parameter(description = "在日期之前或之后[before,after]") beLocated: String,
//        @Parameter(description = "对查询到的数据进行删除") delete: Boolean,
//        response: HttpServletResponse,
//    ) {
//        fileService.downloadDateFile(date, beLocated, delete, response)
//    }
//
//    @Operation(summary = "下载文件")
//    @ApiResponse(
//        responseCode = "200",
//        content = [Content(mediaType = "application/zip", schema = Schema(type = "string", format = "binary"))],
//    )
//    @RequireJwt
//    @PostMapping("/download")
//    fun downloadFile(@RequestBody imageDownloadDTO: @Valid ImageDownloadDTO, response: HttpServletResponse) {
//        fileService.downloadFile(imageDownloadDTO, response)
//    }
//
//    @Operation(summary = "设置上传文件功能状态")
//    @RequireJwt
//    @PostMapping("/upload_ability")
//    fun setUploadAbility(@RequestBody request: UploadAbility): MaaResult<Unit> {
//        fileService.isUploadEnabled = request.enabled
//        return success()
//    }
//
//    @GetMapping("/upload_ability")
//    @RequireJwt
//    @Operation(summary = "获取上传文件功能状态")
//    fun getUploadAbility(): MaaResult<UploadAbility> = success(UploadAbility(fileService.isUploadEnabled))
//
//    @Operation(summary = "关闭uploadfile接口")
//    @RequireJwt
//    @PostMapping("/disable")
//    fun disable(@RequestBody status: Boolean): MaaResult<String?> {
//        if (!status) {
//            return fail(403, "Forbidden")
//        }
//        return success(fileService.disable())
//    }
//
//    @Operation(summary = "开启uploadfile接口")
//    @RequireJwt
//    @PostMapping("/enable")
//    fun enable(@RequestBody status: Boolean): MaaResult<String?> {
//        if (!status) {
//            return fail(403, "Forbidden")
//        }
//        return success(fileService.enable())
//    }
//}
