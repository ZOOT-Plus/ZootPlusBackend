package plus.maa.backend.common.utils

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * @Author leaves
 * @Date 2023/1/20 14:33
 */
object IpUtil {
    /**
     * 获取登录用户IP地址
     *
     * @param request
     * @return
     */
    fun getIpAddr(request: HttpServletRequest): String {
        var ip = request.getHeader("x-forwarded-for")
        if (ip.isNullOrEmpty() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("Proxy-Client-IP")
        }
        if (ip.isNullOrEmpty() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("WL-Proxy-Client-IP")
        }
        if (ip.isNullOrEmpty() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.remoteAddr
            if (ip == "127.0.0.1") {
                // 根据网卡取本机配置的IP
                val inet: InetAddress?
                try {
                    inet = InetAddress.getLocalHost()
                    ip = inet.hostAddress
                } catch (ignored: UnknownHostException) {
                }
            }
        }
        // 对于通过多个代理的情况，第一个IP为客户端真实IP,多个IP按照','分割
        if (ip != null && ip.length > 15) {
            if (ip.indexOf(",") > 0) {
                ip = ip.substringBefore(",")
            }
        }
        return ip
    }
}
