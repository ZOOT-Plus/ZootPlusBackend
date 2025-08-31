package plus.maa.backend.config.external

data class Mail(
    var host: String = "smtp.qq.com",
    var port: Int = 465,
    var from: String = "2842775752@qq.com",
    var user: String = "2842775752",
    var pass: String = "123456789",
    var starttls: Boolean = true,
    var ssl: Boolean = false,
)
