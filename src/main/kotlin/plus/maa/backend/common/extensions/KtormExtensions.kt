package plus.maa.backend.common.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.ktorm.entity.EntitySequence
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.ktorm.expression.ScalarExpression
import org.ktorm.schema.BooleanSqlType
import org.ktorm.schema.ColumnDeclaring
import org.ktorm.schema.SqlType
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.controller.response.user.MaaUserInfo
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity

@Serializable
data class PageResult<T>(
    val data: List<T>,
    val total: Long,
    val page: Int,
    val size: Int,
    val hasNext: Boolean = (page * size) < total,
)

fun <E : Any> EntitySequence<E, *>.paginate(pageable: Pageable): Page<E> {
    val total = this.count()
    val data = this.drop(pageable.offset.toInt()).take(pageable.pageSize).toList()
    return PageImpl(data, pageable, total.toLong())
}

fun <E : Any> EntitySequence<E, *>.paginate(page: Int, size: Int): PageResult<E> {
    val total = this.count()
    val offset = (page - 1) * size
    val data = this.drop(offset).take(size).toList()
    return PageResult(data, total.toLong(), page, size)
}

fun <E : Any> EntitySequence<E, *>.limitAndOffset(limit: Int, offset: Int): List<E> {
    return this.drop(offset).take(limit).toList()
}

// Entity转换扩展函数
fun UserEntity.toMaaUser(): MaaUser {
    return MaaUser(
        userId = this.userId.toString(),
        userName = this.userName,
        email = this.email,
        password = this.password,
        status = this.status,
        pwdUpdateTime = this.pwdUpdateTime,
        followingCount = this.followingCount,
        fansCount = this.fansCount,
    )
}

fun UserEntity.toMaaUserInfo(): MaaUserInfo {
    return MaaUserInfo(
        id = this.userId.toString(),
        userName = this.userName,
        followingCount = this.followingCount,
        fansCount = this.fansCount,
    )
}

inline infix fun <reified T : Any> ColumnDeclaring<*>.containsJson(list: Collection<T>): JsonbContainsExpression {
    val json = defaultJson
    return JsonbContainsExpression(
        left = this.asExpression(),
        right = json.encodeToString(list),
        notFlag = false,
    )
}

infix fun ColumnDeclaring<String>.containsJson(jsonValue: String): JsonbContainsExpression {
    return JsonbContainsExpression(
        left = this.asExpression(),
        right = jsonValue,
        notFlag = false,
    )
}

/**
 * JSONB 包含运算符表达式 (@>)
 */
class JsonbContainsExpression(
    val left: ScalarExpression<*>,
    val right: String,
    val notFlag: Boolean = false,
) : ScalarExpression<Boolean>() {
    override val sqlType: SqlType<Boolean> = BooleanSqlType
    override val isLeafNode: Boolean = false
    override val extraProperties: Map<String, Any>
        get() = mapOf("left" to left, "right" to right, "notFlag" to notFlag)
}
