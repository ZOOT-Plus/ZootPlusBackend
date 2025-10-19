package plus.maa.backend.handler

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import plus.maa.backend.controller.response.MaaResult
import plus.maa.backend.controller.response.MaaResult.Companion.fail
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.service.sensitiveword.SensitiveWordException

private val log = KotlinLogging.logger { }

/**
 * @author john180
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    /**
     * @return plus.maa.backend.controller.response.MaaResult<java.lang.String>
     * @author FAll
     * @description 请求参数缺失
     * @date 2022/12/23 12:00
     </java.lang.String> */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingServletRequestParameterException(e: MissingServletRequestParameterException, request: HttpServletRequest): MaaResult<Unit> {
        logWarn(request)
        log.warn(e) { "请求参数缺失" }
        return fail(400, String.format("请求参数缺失:%s", e.parameterName))
    }

    /**
     * @return plus.maa.backend.controller.response.MaaResult<java.lang.String>
     * @author FAll
     * @description 参数类型不匹配
     * @date 2022/12/23 12:01
     </java.lang.String> */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun methodArgumentTypeMismatchException(e: MethodArgumentTypeMismatchException, request: HttpServletRequest): MaaResult<Unit> {
        logWarn(request)
        log.warn(e) { "参数类型不匹配" }
        return fail(400, String.format("参数类型不匹配:%s", e.message))
    }

    /**
     * @return plus.maa.backend.controller.response.MaaResult<java.lang.String>
     * @author FAll
     * @description 参数校验错误
     * @date 2022/12/23 12:02
     </java.lang.String> */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun methodArgumentNotValidException(e: MethodArgumentNotValidException): MaaResult<Unit> {
        val fieldError = e.bindingResult.fieldError
        if (fieldError != null) {
            return fail(400, String.format("参数校验错误: %s", fieldError.defaultMessage))
        }
        return fail(400, String.format("参数校验错误: %s", e.message))
    }

    /**
     * @return plus.maa.backend.controller.response.MaaResult<java.lang.String>
     * @author FAll
     * @description 请求地址不存在
     * @date 2022/12/23 12:03
     </java.lang.String> */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoHandlerFoundException::class)
    fun noHandlerFoundExceptionHandler(e: NoHandlerFoundException): MaaResult<Unit> {
        log.warn(e) { "请求地址不存在" }
        return fail(404, String.format("请求地址 %s 不存在", e.requestURL))
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFoundException(e: NoResourceFoundException): MaaResult<Unit> {
        log.warn(e) { "请求资源不存在" }
        return fail(404, String.format("请求资源 %s 不存在", e.resourcePath))
    }

    /**
     * @return plus.maa.backend.controller.response.MaaResult<java.lang.String>
     * @author FAll
     * @description
     * @date 2022/12/23 12:04
     </java.lang.String> */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun httpRequestMethodNotSupportedExceptionHandler(
        e: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): MaaResult<Unit> {
        logWarn(request)
        log.warn { "请求方式错误, method: ${request.method} url: ${request.requestURL}" }
        return fail(405, String.format("请求方法不正确:%s", e.message))
    }

    /**
     * 处理由 [org.springframework.util.Assert] 工具产生的异常
     */
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class, ConstraintViolationException::class)
    fun illegalArgumentOrStateExceptionHandler(e: RuntimeException): MaaResult<Unit> {
        return fail(HttpStatus.BAD_REQUEST.value(), e.message)
    }

    /**
     * @return plus.maa.backend.controller.response.MaaResult<java.lang.String>
     * @author cbc
     * @description
     * @date 2022/12/26 12:00
     </java.lang.String> */
    @ExceptionHandler(MaaResultException::class)
    fun maaResultExceptionHandler(e: MaaResultException): MaaResult<Unit> {
        return fail(e.code, e.msg)
    }

    /**
     * @author john180
     * @description 用户鉴权相关，异常兜底处理
     */
    @ExceptionHandler(AuthenticationException::class)
    fun authExceptionHandler(e: AuthenticationException): MaaResult<Unit> {
        return fail(401, e.message)
    }

    @ExceptionHandler(MultipartException::class)
    fun fileSizeThresholdHandler(e: MultipartException): MaaResult<Unit> {
        return fail(413, e.message)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): MaaResult<Unit> {
        return fail(ex.statusCode.value(), ex.message)
    }

    @ExceptionHandler(SensitiveWordException::class)
    fun handleSensitiveWordException(ex: SensitiveWordException): MaaResult<Unit> {
        return fail(400, ex.message)
    }

    /**
     * @return plus.maa.backend.controller.response.MaaResult
     * @author john180
     * @description 服务器内部错误，异常兜底处理
     * @date 2022/12/23 12:06
     */
    @ResponseBody
    @ExceptionHandler(value = [Exception::class])
    fun defaultExceptionHandler(e: Exception, request: HttpServletRequest): MaaResult<*> {
        logError(request)
        log.error(e) { "Exception: " }
        return fail(500, "服务器内部错误")
    }

    private fun logWarn(request: HttpServletRequest) {
        log.warn { "Request URL: ${request.requestURL}" }
        log.warn { "Request Method: ${request.method}" }
        log.warn { "Request IP: ${request.remoteAddr}" }
        log.warn { "Request Headers: ${request.headerNames}" }
        log.warn { "Request Parameters: ${request.parameterMap}" }
    }

    private fun logError(request: HttpServletRequest) {
        log.error { "Request URL: ${request.requestURL}" }
        log.error { "Request Method: ${request.method}" }
        log.error { "Request IP: ${request.remoteAddr}" }
        log.error { "Request Headers: ${request.headerNames}" }
        log.error { "Request Parameters: ${request.parameterMap}" }
    }
}
