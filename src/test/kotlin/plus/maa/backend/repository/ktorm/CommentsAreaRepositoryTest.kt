package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.springframework.data.domain.PageRequest
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.CommentsAreaEntity
import java.time.LocalDateTime

/**
 * [CommentsAreaRepository] 基线集成测试（真实数据库：zonky embedded-postgres）。
 *
 * 通用场景（自增 ID 回填、NULL 往返、删除影响行数、唯一约束冲突、分页边界）同样覆盖。
 * 测试数据经基类 [TestDbSupport] 的 TRUNCATE ... RESTART IDENTITY 隔离，每用例自增从 1 开始。
 *
 * 覆盖方法清单：
 * - 本类：findByMainCommentId(Long)、findByMainCommentId(List<Long>)、
 *   findByCopilotIdAndDeleteAndMainCommentIdExists、findByCopilotId(Collection, Boolean)、
 *   countByCopilotId、findById、deleteById、existsById、insertEntity、updateEntity
 * - 继承：findAll、save、count
 *
 * 未覆盖点及原因：
 * - 复合主键：comments_area 只有单列自增主键 id；复合主键场景（user_follow 表）属 user 模块。
 */
class CommentsAreaRepositoryTest : TestDbSupport() {

    private val repository = CommentsAreaRepository(jdbi)

    // ---------- 测试数据工厂 ----------

    private fun newComment(block: CommentsAreaEntity.() -> Unit = {}): CommentsAreaEntity {
        val entity = CommentsAreaEntity(
            copilotId = 1L,
            uploaderId = 100L,
            message = "comment-message",
            likeCount = 0L,
            dislikeCount = 0L,
            uploadTime = LocalDateTime.of(2024, 1, 1, 12, 0, 0),
            topping = false,
            delete = false,
            deleteTime = null,
            mainCommentId = null,
            notification = false,
        )
        entity.block()
        return entity
    }

    private fun insertComment(block: CommentsAreaEntity.() -> Unit = {}): CommentsAreaEntity = repository.insertEntity(newComment(block))

    /** 直接执行 SQL（用于模拟实体感知之外的并发外部修改）。 */
    private fun execUpdate(sql: String, vararg args: Any?) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { index, arg -> ps.setObject(index + 1, arg) }
                ps.executeUpdate()
            }
        }
    }

    /** 在异常链中查找 PSQLException（ktorm 经 Spring 转换器/直接 JDBC 抛出时可能包装多层）。 */
    private fun findPsqlException(t: Throwable): PSQLException? =
        generateSequence(t) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()

    /** 唯一约束/主键冲突：PG SQLState 23505（duplicate key）。 */
    private fun assertUniqueConstraintViolation(block: () -> Unit) {
        val ex = assertThrows(Exception::class.java) { block() }
        val psql = findPsqlException(ex)
        assertNotNull(psql, "异常链中应包含 PSQLException，实际=${ex::class.simpleName}: ${ex.message}")
        assertEquals("23505", psql!!.sqlState)
    }

    private fun assertCommentFields(expected: CommentsAreaEntity, actual: CommentsAreaEntity) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.copilotId, actual.copilotId)
        assertEquals(expected.fromCommentId, actual.fromCommentId)
        assertEquals(expected.uploaderId, actual.uploaderId)
        assertEquals(expected.message, actual.message)
        assertEquals(expected.likeCount, actual.likeCount)
        assertEquals(expected.dislikeCount, actual.dislikeCount)
        assertEquals(expected.uploadTime, actual.uploadTime)
        assertEquals(expected.topping, actual.topping)
        assertEquals(expected.delete, actual.delete)
        assertEquals(expected.deleteTime, actual.deleteTime)
        assertEquals(expected.mainCommentId, actual.mainCommentId)
        assertEquals(expected.notification, actual.notification)
    }

    // ---------- 通用 CRUD ----------

    @Test
    fun insertEntityBackfillsAutoIncrementIdAndRoundTripsAllColumns() {
        val e1 = insertComment {
            message = "hello"
            likeCount = 7L
            dislikeCount = 3L
            topping = true
            notification = true
        }
        val e2 = insertComment()
        val e3 = insertComment {
            copilotId = 2L
            fromCommentId = 888L
            delete = true
            deleteTime = LocalDateTime.of(2024, 2, 1, 0, 0, 0)
            mainCommentId = 999L
        }

        assertEquals(1L, e1.id)
        assertEquals(2L, e2.id)
        assertEquals(3L, e3.id)

        // 全列读回一致（含可空列 NULL 往返）
        val loaded = repository.findById(e1.id)!!
        assertCommentFields(e1, loaded)
        assertNull(loaded.fromCommentId)
        assertNull(loaded.deleteTime)
        assertNull(loaded.mainCommentId)

        // 可空列非 NULL 往返
        val loaded3 = repository.findById(e3.id)!!
        assertCommentFields(e3, loaded3)
        assertEquals(2L, loaded3.copilotId)
        assertEquals(888L, loaded3.fromCommentId)
        assertEquals(999L, loaded3.mainCommentId)
        assertTrue(loaded3.delete)
        assertEquals(LocalDateTime.of(2024, 2, 1, 0, 0, 0), loaded3.deleteTime)
    }

    @Test
    fun insertEntityWithExplicitIdInsertsAsIsAndDuplicatePkConflicts() {
        val e = insertComment {
            id = 5L
            message = "explicit-id"
        }

        assertEquals(5L, e.id)
        assertTrue(repository.existsById(5L))
        assertEquals(1L, repository.count())

        // 唯一约束冲突：重复主键 → 抛异常（PG duplicate key，SQLState 23505）
        assertUniqueConstraintViolation {
            repository.insertEntity(
                newComment {
                    id = 5L
                    message = "duplicate"
                },
            )
        }
        assertEquals(1L, repository.count())

        assertEquals(1L, insertComment().id)
    }

    @Test
    fun findByIdHitsAndMisses() {
        val e = insertComment { message = "find-me" }

        val loaded = repository.findById(e.id)
        assertTrue(loaded != null)
        assertCommentFields(e, loaded!!)

        assertNull(repository.findById(e.id + 1L))
        assertNull(repository.findById(0L))
    }

    @Test
    fun findAllReturnsAllRows() {
        assertTrue(repository.findAll().isEmpty())

        val ids = (1..3).map { insertComment().id }
        assertEquals(ids.toSet(), repository.findAll().map { it.id }.toSet())
    }

    @Test
    fun countReturnsTotal() {
        assertEquals(0L, repository.count())

        insertComment()
        insertComment()
        insertComment()
        assertEquals(3L, repository.count())
    }

    @Test
    fun existsByIdTrueForExistingAndFalseForMissing() {
        val e = insertComment()
        assertTrue(repository.existsById(e.id))
        assertFalse(repository.existsById(e.id + 1L))
        assertFalse(repository.existsById(0L))
    }

    @Test
    fun deleteByIdAffectsOneRowAndIsIdempotentWithoutSequenceRollback() {
        val e1 = insertComment()
        insertComment()

        assertTrue(repository.deleteById(e1.id))
        assertFalse(repository.existsById(e1.id))
        assertEquals(1L, repository.count())

        assertFalse(repository.deleteById(e1.id))
        assertFalse(repository.deleteById(999L))

        // 删除后自增序列不回退
        assertEquals(3L, insertComment().id)
    }

    // ---------- findByMainCommentId ----------

    @Test
    fun findByMainCommentIdSingleIdReturnsAllSubCommentsIncludingDeleted() {
        val main = insertComment { message = "main" }
        val sub1 = insertComment {
            mainCommentId = main.id
            fromCommentId = main.id
            message = "sub-1"
        }
        val sub2 = insertComment {
            mainCommentId = main.id
            fromCommentId = main.id
            message = "sub-2-deleted"
            delete = true
            deleteTime = LocalDateTime.of(2024, 2, 1, 0, 0, 0)
        }
        val otherMain = insertComment { message = "other-main" }

        // 命中所有子评论，delete=true 的也返回（不过滤）
        val subs = repository.findByMainCommentId(main.id)
        assertEquals(setOf(sub1.id, sub2.id), subs.map { it.id }.toSet())

        // main_comment_id = NULL 的主评论自身不命中
        assertTrue(subs.none { it.id == main.id })
        assertTrue(subs.none { it.id == otherMain.id })

        assertTrue(repository.findByMainCommentId(otherMain.id).isEmpty())
        assertTrue(repository.findByMainCommentId(999L).isEmpty())
    }

    @Test
    fun findByMainCommentIdListIdsReturnsAllMatches() {
        val main1 = insertComment { message = "main-1" }
        val main2 = insertComment { message = "main-2" }
        val subA = insertComment {
            mainCommentId = main1.id
            message = "a"
        }
        val subB = insertComment {
            mainCommentId = main2.id
            message = "b"
        }
        val subA2 = insertComment {
            mainCommentId = main1.id
            message = "a2"
        }

        // IN 命中多个 main_comment_id（顺序不保证，按 id 集合断言）
        val found = repository.findByMainCommentId(listOf(main1.id, main2.id))
        assertEquals(setOf(subA.id, subB.id, subA2.id), found.map { it.id }.toSet())

        assertTrue(repository.findByMainCommentId(listOf(999L)).isEmpty())
    }

    @Test
    fun findByMainCommentIdEmptyListReturnsEmpty() {
        assertTrue(repository.findByMainCommentId(emptyList()).isEmpty())
    }

    // ---------- softDeleteByMainCommentId ----------

    @Test
    fun softDeleteByMainCommentIdMarksAllSubsAndPreservesOtherColumns() {
        val main = insertComment { message = "main" }
        val sub1 = insertComment {
            mainCommentId = main.id
            fromCommentId = main.id
            message = "sub-1"
            likeCount = 5L
            topping = true
        }
        val sub2 = insertComment {
            mainCommentId = main.id
            fromCommentId = main.id
            message = "sub-2"
            delete = true
            deleteTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
        }
        // 另一条主评论的子评论不应被误删
        val otherMain = insertComment { message = "other-main" }
        val otherSub = insertComment {
            mainCommentId = otherMain.id
            message = "other-sub"
        }

        val deleteTime = LocalDateTime.of(2024, 3, 1, 9, 30, 0)
        val affected = repository.softDeleteByMainCommentId(main.id, deleteTime)
        assertEquals(2, affected)

        val s1 = repository.findById(sub1.id)!!
        val s2 = repository.findById(sub2.id)!!
        assertTrue(s1.delete)
        assertEquals(deleteTime, s1.deleteTime)
        assertTrue(s2.delete)
        assertEquals(deleteTime, s2.deleteTime)
        // 其余列保持原值
        assertEquals(5L, s1.likeCount)
        assertEquals("sub-1", s1.message)
        assertTrue(s1.topping)

        // 非该主评论下的评论不受影响
        val other = repository.findById(otherSub.id)!!
        assertFalse(other.delete)
        assertNull(other.deleteTime)

        // 主评论自身不命中（main_comment_id IS NULL）
        assertFalse(repository.findById(main.id)!!.delete)

        // 不存在的 main_comment_id 返回 0
        assertEquals(0, repository.softDeleteByMainCommentId(999L, deleteTime))
    }

    // ---------- findByCopilotId (Collection) ----------

    @Test
    fun findByCopilotIdFiltersByCollectionAndDeleteFlag() {
        val a = insertComment {
            copilotId = 10L
            message = "a"
        }
        val b = insertComment {
            copilotId = 10L
            message = "b"
        }
        val c = insertComment {
            copilotId = 10L
            delete = true
            message = "c"
        }
        val d = insertComment {
            copilotId = 20L
            message = "d"
        }
        val e = insertComment {
            copilotId = 20L
            delete = true
            message = "e"
        }

        // IN 命中多 copilot_id + delete 过滤
        val notDeleted = repository.findByCopilotId(listOf(10L, 20L), false)
        assertEquals(setOf(a.id, b.id, d.id), notDeleted.map { it.id }.toSet())

        val deletedOf10 = repository.findByCopilotId(listOf(10L), true)
        assertEquals(setOf(c.id), deletedOf10.map { it.id }.toSet())

        assertTrue(repository.findByCopilotId(listOf(999L), false).isEmpty())
        assertTrue(repository.findByCopilotId(listOf(999L), true).isEmpty())
    }

    @Test
    fun findByCopilotIdEmptyCollectionReturnsEmpty() {
        assertTrue(repository.findByCopilotId(emptyList(), false).isEmpty())
    }

    // ---------- 组合条件 + paginate 分页 ----------

    @Test
    fun findByCopilotIdAndDeleteAndMainCommentIdExistsExistsTrueOnlyNonNullMainIds() {
        val main1 = insertComment {
            copilotId = 10L
            message = "main-1"
        }
        insertComment {
            copilotId = 10L
            message = "main-2"
        }
        val sub1 = insertComment {
            copilotId = 10L
            mainCommentId = main1.id
            message = "sub-1"
        }
        insertComment {
            copilotId = 10L
            mainCommentId = main1.id
            delete = true
            message = "sub-2-deleted"
        }
        insertComment {
            copilotId = 20L
            mainCommentId = main1.id
            message = "other-copilot-sub"
        }

        // exists=true：main_comment_id IS NOT NULL，且 delete / copilotId 均匹配
        val page = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            10L,
            delete = false,
            exists = true,
            pageable = PageRequest.of(0, 10),
        )
        assertEquals(1L, page.totalElements)
        assertEquals(setOf(sub1.id), page.content.map { it.id }.toSet())
    }

    @Test
    fun findByCopilotIdAndDeleteAndMainCommentIdExistsExistsFalseOnlyNullMainIds() {
        val main1 = insertComment {
            copilotId = 10L
            message = "main-1"
        }
        val main2 = insertComment {
            copilotId = 10L
            delete = true
            message = "main-2-deleted"
        }
        insertComment {
            copilotId = 10L
            mainCommentId = main1.id
            message = "sub-1"
        }
        insertComment {
            copilotId = 20L
            message = "other-copilot-main"
        }

        // exists=false：main_comment_id IS NULL + delete=false + copilotId 匹配
        val page = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            10L,
            delete = false,
            exists = false,
            pageable = PageRequest.of(0, 10),
        )
        assertEquals(1L, page.totalElements)
        assertEquals(setOf(main1.id), page.content.map { it.id }.toSet())

        // delete=true 分支：只返回已删除的主评论
        val deletedPage = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            10L,
            delete = true,
            exists = false,
            pageable = PageRequest.of(0, 10),
        )
        assertEquals(1L, deletedPage.totalElements)
        assertEquals(setOf(main2.id), deletedPage.content.map { it.id }.toSet())
    }

    @Test
    fun findByCopilotIdAndDeleteAndMainCommentIdExistsPaginationBoundaries() {
        val m1 = insertComment {
            copilotId = 10L
            message = "m1"
        }
        val m2 = insertComment {
            copilotId = 10L
            message = "m2"
        }
        val m3 = insertComment {
            copilotId = 10L
            message = "m3"
        }
        insertComment {
            copilotId = 10L
            mainCommentId = m1.id
            message = "s1"
        } // 子评论，不计入 exists=false

        val page0 = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            10L,
            false,
            false,
            PageRequest.of(0, 2),
        )
        assertEquals(3L, page0.totalElements)
        assertEquals(2, page0.content.size)
        assertEquals(setOf(m1.id, m2.id), page0.content.map { it.id }.toSet())

        val page1 = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            10L,
            false,
            false,
            PageRequest.of(1, 2),
        )
        assertEquals(3L, page1.totalElements)
        assertEquals(setOf(m3.id), page1.content.map { it.id }.toSet())

        // 越界页：空 content，total 仍正确
        val page2 = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            10L,
            false,
            false,
            PageRequest.of(2, 2),
        )
        assertEquals(0, page2.content.size)
        assertEquals(3L, page2.totalElements)

        // 无命中：空 content + total=0
        val none = repository.findByCopilotIdAndDeleteAndMainCommentIdExists(
            999L,
            false,
            false,
            PageRequest.of(0, 2),
        )
        assertEquals(0, none.content.size)
        assertEquals(0L, none.totalElements)
    }

    // ---------- countByCopilotId ----------

    @Test
    fun countByCopilotIdCountsWithDeleteFilter() {
        insertComment { copilotId = 10L }
        insertComment { copilotId = 10L }
        insertComment {
            copilotId = 10L
            delete = true
        }
        insertComment { copilotId = 20L }

        assertEquals(2L, repository.countByCopilotId(10L, false))
        assertEquals(1L, repository.countByCopilotId(10L, true))
        assertEquals(0L, repository.countByCopilotId(999L, false))
    }

    // ---------- updateEntity / save ----------

    @Test
    fun updateEntityFlushChangesWritesChangedColumnsOnly() {
        val e = insertComment {
            message = "original"
            likeCount = 1L
        }

        // 模拟并发外部修改：绕过实体直接改 like_count
        execUpdate("UPDATE comments_area SET like_count = 999 WHERE id = ?", e.id)

        val loaded = repository.findById(e.id)!!
        loaded.message = "updated"
        repository.updateEntity(loaded)

        val reloaded = repository.findById(e.id)!!
        assertEquals("updated", reloaded.message)
        assertEquals(999L, reloaded.likeCount, "脏检查只更新变化列，外部修改的 like_count 应保留")
        assertEquals(e.uploaderId, reloaded.uploaderId)
        assertEquals(e.uploadTime, reloaded.uploadTime)
        assertEquals(e.topping, reloaded.topping)
        assertFalse(reloaded.delete)
    }

    @Test
    fun updateEntityWithNoChangesIsNoOp() {
        val e = insertComment { message = "keep" }
        val loaded = repository.findById(e.id)!!

        // 无变更时 flushChanges 返回 0（不抛异常、不写库）
        repository.updateEntity(loaded)

        val reloaded = repository.findById(e.id)!!
        assertEquals("keep", reloaded.message)
        assertEquals(1L, repository.count())
    }

    @Test
    fun saveNewEntityInsertsAndBackfillsId() {
        val e = newComment { message = "via-save" }
        assertEquals(0L, e.id)

        repository.save(e)

        assertEquals(1L, e.id, "新实体（id=0）应走插入并回填自增 ID")
        assertEquals("via-save", repository.findById(e.id)!!.message)
        assertEquals(1L, repository.count())
    }

    @Test
    fun saveWithExplicitNonExistentIdInsertsThatIdBaseline() {
        val e = newComment {
            id = 5L
            message = "explicit-id"
        }

        repository.save(e)

        assertEquals(5L, e.id)
        assertEquals(5L, repository.findById(5L)!!.id)
        assertEquals(1L, repository.count())

        assertEquals(1L, insertComment().id)
    }

    @Test
    fun saveExistingEntityUpdatesInPlaceWithoutNewRow() {
        val e = insertComment {
            message = "before"
            likeCount = 1L
        }

        val loaded = repository.findById(e.id)!!
        loaded.message = "after"
        repository.save(loaded)

        assertEquals(1L, repository.count())
        assertEquals("after", repository.findById(e.id)!!.message)

        // save 是整行 UPDATE（区别于 updateEntity 的脏检查）：外部修改会被实体值覆盖
        execUpdate("UPDATE comments_area SET like_count = 999 WHERE id = ?", e.id)
        val loaded2 = repository.findById(e.id)!!
        loaded2.likeCount = 2L
        repository.save(loaded2)
        assertEquals(2L, repository.findById(e.id)!!.likeCount)
    }
}
