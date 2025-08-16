package plus.maa.backend.common.extensions

import org.ktorm.entity.EntitySequence
import org.ktorm.entity.count
import org.ktorm.entity.drop
import org.ktorm.entity.take
import org.ktorm.entity.toList
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.UserEntity

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
        userId = this.userId,
        userName = this.userName,
        email = this.email,
        password = this.password,
        status = this.status,
        pwdUpdateTime = this.pwdUpdateTime,
        followingCount = this.followingCount,
        fansCount = this.fansCount,
    )
}
