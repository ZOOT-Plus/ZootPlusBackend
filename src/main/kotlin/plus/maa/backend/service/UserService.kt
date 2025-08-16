package plus.maa.backend.service

import org.ktorm.database.Database
import org.ktorm.dsl.desc
import org.ktorm.dsl.eq
import org.ktorm.dsl.like
import org.ktorm.entity.drop
import org.ktorm.entity.filter
import org.ktorm.entity.firstOrNull
import org.ktorm.entity.sortedBy
import org.ktorm.entity.take
import org.ktorm.entity.toList
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import plus.maa.backend.common.MaaStatusCode
import plus.maa.backend.common.extensions.toMaaUser
import plus.maa.backend.controller.request.user.LoginDTO
import plus.maa.backend.controller.request.user.PasswordResetDTO
import plus.maa.backend.controller.request.user.RegisterDTO
import plus.maa.backend.controller.request.user.SendRegistrationTokenDTO
import plus.maa.backend.controller.request.user.UserInfoUpdateDTO
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.controller.response.user.MaaLoginRsp
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.users
import plus.maa.backend.repository.ktorm.UserKtormRepository
import plus.maa.backend.service.jwt.JwtExpiredException
import plus.maa.backend.service.jwt.JwtInvalidException
import plus.maa.backend.service.jwt.JwtService
import java.time.Instant
import java.time.temporal.ChronoUnit
import plus.maa.backend.cache.InternalComposeCache as Cache

/**
 * @author AnselYuki
 */
@Service
class UserService(
    private val database: Database,
    private val userKtormRepository: UserKtormRepository,
    private val emailService: EmailService,
    private val passwordEncoder: PasswordEncoder,
    private val userDetailService: UserDetailServiceImpl,
    private val jwtService: JwtService,
) {
    private val log = KotlinLogging.logger { }
    /**
     * 登录方法
     *
     * @param loginDTO 登录参数
     * @return 携带了token的封装类
     */
    fun login(loginDTO: LoginDTO): MaaLoginRsp {
        val user = userKtormRepository.findByEmail(loginDTO.email)
        if (user == null || !passwordEncoder.matches(loginDTO.password, user.password)) {
            throw MaaResultException(401, "用户不存在或者密码错误")
        }
        // 未激活的用户
        if (user.status == 0) {
            throw MaaResultException(MaaStatusCode.MAA_USER_NOT_ENABLED)
        }

        val maaUser = user.toMaaUser()
        val authorities = userDetailService.collectAuthoritiesFor(maaUser)
        val authToken = jwtService.issueAuthToken(user.userId, null, authorities)
        val refreshToken = jwtService.issueRefreshToken(user.userId, null)

        return MaaLoginRsp(
            authToken.value,
            authToken.expiresAt,
            authToken.notBefore,
            refreshToken.value,
            refreshToken.expiresAt,
            refreshToken.notBefore,
            MaaUserInfo(maaUser),
        )
    }

    /**
     * 修改密码
     *
     * @param userId      当前用户
     * @param rawPassword 新密码
     */
    fun modifyPassword(userId: String, rawPassword: String, originPassword: String? = null, verifyOriginPassword: Boolean = true) {
        val userEntity = userKtormRepository.findById(userId) ?: return
        val maaUser = userEntity.toMaaUser()
        if (verifyOriginPassword) {
            check(!originPassword.isNullOrEmpty()) {
                "请输入原密码"
            }
            check(passwordEncoder.matches(originPassword, maaUser.password)) {
                "原密码错误"
            }
            // 通过原密码修改密码不能过于频繁
            check(ChronoUnit.MINUTES.between(maaUser.pwdUpdateTime, Instant.now()) >= 10L) {
                "密码修改过于频繁"
            }
        }
        // 修改密码的逻辑，应当使用与 authentication provider 一致的编码器
        userEntity.password = passwordEncoder.encode(rawPassword)
        // 更新密码时，如果用户未启用则自动启用
        if (userEntity.status == 0) {
            userEntity.status = 1
        }
        userEntity.pwdUpdateTime = Instant.now()
        userKtormRepository.save(userEntity)
        Cache.invalidateMaaUserById(userId)
    }

    /**
     * 用户注册
     *
     * @param registerDTO 传入用户参数
     * @return 返回注册成功的用户摘要（脱敏）
     */
    fun register(registerDTO: RegisterDTO): MaaUserInfo {
        val userName = registerDTO.userName.trim()
        check(userName.length >= 4) { "用户名长度应在4-24位之间" }
        check(!userKtormRepository.existsByUserName(userName)) {
            "用户名已存在，请重新取个名字吧"
        }

        // 校验验证码
        emailService.verifyVCode(registerDTO.email, registerDTO.registrationToken)

        val encoded = passwordEncoder.encode(registerDTO.password)

        val user = MaaUser(
            userName = userName,
            email = registerDTO.email,
            password = encoded,
            status = 1,
            pwdUpdateTime = Instant.now(),
        )
        return try {
            val userEntity = userKtormRepository.createFromMaaUser(user)
            userKtormRepository.save(userEntity)
            MaaUserInfo(user).also {
                Cache.invalidateMaaUserById(it.id)
            }
        } catch (_: DuplicateKeyException) {
            throw MaaResultException(MaaStatusCode.MAA_USER_EXISTS)
        }
    }

    /**
     * 更新用户信息
     *
     * @param userId    用户id
     * @param updateDTO 更新参数
     */
    fun updateUserInfo(userId: String, updateDTO: UserInfoUpdateDTO) {
        val userEntity = userKtormRepository.findById(userId) ?: return
        val newName = updateDTO.userName.trim()
        check(newName.length >= 4) { "用户名长度应在4-24位之间" }
        if (newName == userEntity.userName) {
            // 暂时只支持修改用户名，如果有其他字段修改需要同步修改该逻辑
            return
        }
        // 用户名需要trim
        check(!userKtormRepository.existsByUserName(newName)) {
            "用户名已存在，请重新取个名字吧"
        }
        userEntity.userName = newName
        userKtormRepository.save(userEntity)
        Cache.invalidateMaaUserById(userId)
    }

    /**
     * 刷新token
     *
     * @param token token
     */
    fun refreshToken(token: String): MaaLoginRsp {
        try {
            val old = jwtService.verifyAndParseRefreshToken(token)

            val userId = old.subject
            val userEntity = userKtormRepository.findById(userId) ?: throw NoSuchElementException()
            val user = userEntity.toMaaUser()
            if (old.issuedAt.isBefore(user.pwdUpdateTime)) {
                throw MaaResultException(401, "invalid token")
            }

            // 刚签发不久的 refreshToken 重新使用
            val refreshToken = if (ChronoUnit.MINUTES.between(old.issuedAt, Instant.now()) < 5) {
                old
            } else {
                jwtService.issueRefreshToken(userId, null)
            }
            val authorities = userDetailService.collectAuthoritiesFor(user)
            val authToken = jwtService.issueAuthToken(userId, null, authorities)

            return MaaLoginRsp(
                authToken.value,
                authToken.expiresAt,
                authToken.notBefore,
                refreshToken.value,
                refreshToken.expiresAt,
                refreshToken.notBefore,
                MaaUserInfo(user),
            )
        } catch (e: JwtInvalidException) {
            throw MaaResultException(401, e.message)
        } catch (e: JwtExpiredException) {
            throw MaaResultException(401, e.message)
        } catch (e: NoSuchElementException) {
            throw MaaResultException(401, e.message)
        }
    }

    /**
     * 通过邮箱激活码更新密码
     *
     * @param passwordResetDTO 通过邮箱修改密码请求
     */
    fun modifyPasswordByActiveCode(passwordResetDTO: PasswordResetDTO) {
        emailService.verifyVCode(passwordResetDTO.email, passwordResetDTO.activeCode)
        val userEntity = userKtormRepository.findByEmail(passwordResetDTO.email)
        modifyPassword(userEntity!!.userId, passwordResetDTO.password, verifyOriginPassword = false)
    }

    /**
     * 根据邮箱校验用户是否存在
     *
     * @param email 用户邮箱
     */
    fun checkUserExistByEmail(email: String) {
        if (null == userKtormRepository.findByEmail(email)) {
            throw MaaResultException(MaaStatusCode.MAA_USER_NOT_FOUND)
        }
    }

    /**
     * 注册时发送验证码
     */
    fun sendRegistrationToken(regDTO: SendRegistrationTokenDTO) {
        // 判断用户是否存在
        val userEntity = userKtormRepository.findByEmail(regDTO.email)
        if (userEntity != null) {
            // 用户已存在
            log.info { "send registration token: user exists for email: ${regDTO.email}" }
            throw MaaResultException(MaaStatusCode.MAA_USER_EXISTS)
        }
        // 发送验证码
        emailService.sendVCode(regDTO.email)
    }

    fun findByUserIdOrDefault(id: String) = database.users.filter { it.userId eq id }.firstOrNull() ?: UserEntity.UNKNOWN

    fun findByUserIdOrDefaultInCache(id: String): UserEntity {
        return Cache.getMaaUserCache(id, ::findByUserIdOrDefault)
    }

    fun findByUsersId(ids: Iterable<String>): UserDict {
        return userKtormRepository.findAllById(ids).map { it.toMaaUser() }.let { UserDict(it) }
    }

    class UserDict(users: List<MaaUser>) {
        private val userMap = users.associateBy { it.userId!! }
        fun entries() = userMap.entries
        operator fun get(id: String): MaaUser? = userMap[id]
        fun getOrDefault(id: String) = get(id) ?: MaaUser.UNKNOWN
    }

    fun get(userId: String): MaaUserInfo? = database.users.filter { it.userId eq userId }.firstOrNull()?.run(::MaaUserInfo)

    /**
     * 用户模糊搜索
     */
    fun search(userName: String, offset: Int, limit: Int): List<UserEntity> {
        return database.users.filter { it.userName like "%$userName%" }
            .sortedBy { it.fansCount.desc() }
            .drop(offset)
            .take(limit)
            .toList()
    }

    @Suppress("unused")
    private fun isAllChinese(input: String): Boolean {
        return input.all { it in '\u4e00'..'\u9fa5' }
    }
}
