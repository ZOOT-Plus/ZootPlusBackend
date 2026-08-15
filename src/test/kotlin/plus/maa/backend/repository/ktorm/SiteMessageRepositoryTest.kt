package plus.maa.backend.repository.ktorm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import plus.maa.backend.repository.TestDbSupport
import plus.maa.backend.repository.entity.SiteMessageEntity
import plus.maa.backend.service.model.SiteMessageType
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * SiteMessageRepository 的真实数据库集成测试（zonky embedded-postgres + Flyway 迁移）。
 *
 * 覆盖范围：repository 全部 public 方法（含继承的 findAll/save/count）。
 *
 * 服务层 SiteMessageService 仅做空 title 兜底与分页参数规整，其唯一 DB 逻辑已由本测试逐方法覆盖。
 *
 * 数据准备 helper 独立于被测 repository：直接经 jdbi 手写 SQL 写表，避免跨模块测试耦合。
 *
 * 基线行为记录：
 * 1. insertCopilotPublishedNotifications 对"作者特关自己"不做特殊处理 —— 若作者把自己加为特关粉丝，
 *    也会给自己插入一条通知（INSERT...SELECT 无 self 排除）。见 insertNotifications_senderSpecialFollowsSelf_keepsBaseline。
 * 2. created_at / read_at 为 timestamp(3)，毫秒以下精度被数据库截断（jdbi 与原生 JDBC 路径一致）。
 * 3. markRead 幂等：已读后再次调用返回 false 且不覆盖原 read_at。
 */
class SiteMessageRepositoryTest : TestDbSupport() {

    private val repository = SiteMessageRepository(jdbi)

    // ------------------------------------------------------------------
    // 数据准备 helpers（独立于被测 repository，经 jdbi 手写 SQL 直接写表）
    // ------------------------------------------------------------------

    private fun insertFollow(
        userId: Long,
        followUserId: Long,
        specialFollow: Boolean,
        updatedAt: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0, 0),
    ) {
        jdbi.withHandle<Unit, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO user_follow (user_id, follow_user_id, special_follow, updated_at)
                VALUES (:userId, :followUserId, :specialFollow, :updatedAt)
                """.trimIndent(),
            )
                .bind("userId", userId)
                .bind("followUserId", followUserId)
                .bind("specialFollow", specialFollow)
                .bind("updatedAt", updatedAt)
                .execute()
        }
    }

    /** 直接插一条站内信，返回自增 id。所有时间戳保持毫秒精度，避免 timestamp(3) 舍入歧义。 */
    private fun insertMessage(
        receiverId: Long,
        senderId: Long = 1L,
        senderName: String = "author",
        title: String = "title",
        content: String = "content",
        copilotId: Long? = null,
        readAt: LocalDateTime? = null,
        createdAt: LocalDateTime = LocalDateTime.of(2025, 3, 1, 12, 0, 0),
    ): Long {
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO site_message (receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at)
                VALUES (:receiverId, :senderId, :senderName, :type, :title, :content, :copilotId, :readAt, :createdAt)
                """.trimIndent(),
            )
                .bind("receiverId", receiverId)
                .bind("senderId", senderId)
                .bind("senderName", senderName)
                .bind("type", SiteMessageType.COPILOT_PUBLISHED)
                .bind("title", title)
                .bind("content", content)
                .bind("copilotId", copilotId)
                .bind("readAt", readAt)
                .bind("createdAt", createdAt)
                .executeAndReturnGeneratedKeys("id")
                .mapTo(Long::class.java)
                .one()
        }
    }

    /** 全表读取（数据准备断言用），按 id 升序保证确定性。 */
    private fun allMessages(): List<SiteMessageEntity> {
        return jdbi.withHandle<List<SiteMessageEntity>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, receiver_id, sender_id, sender_name, type, title, content, copilot_id, read_at, created_at
                FROM site_message
                ORDER BY id
                """.trimIndent(),
            )
                .mapTo(SiteMessageEntity::class.java)
                .list()
        }
    }

    private fun loadMessage(id: Long): SiteMessageEntity? = repository.findById(id)

    private fun assertNotificationRow(
        id: Long,
        receiverId: Long,
        senderId: Long,
        senderName: String,
        copilotId: Long,
        title: String,
        content: String,
        createdAt: LocalDateTime,
    ) {
        val row = loadMessage(id)
        assertNotNull(row)
        assertEquals(receiverId, row!!.receiverId)
        assertEquals(senderId, row.senderId)
        assertEquals(senderName, row.senderName)
        assertEquals(SiteMessageType.COPILOT_PUBLISHED, row.type)
        assertEquals(title, row.title)
        assertEquals(content, row.content)
        assertEquals(copilotId, row.copilotId)
        assertNull(row.readAt, "批量通知插入时 read_at 必须为 NULL")
        assertEquals(createdAt, row.createdAt)
    }

    // ------------------------------------------------------------------
    // insertCopilotPublishedNotifications（INSERT...SELECT 批量通知）
    // ------------------------------------------------------------------

    @Test
    fun insertNotifications_createsOneMessagePerSpecialFollower() {
        val senderId = 1L
        insertFollow(userId = 10L, followUserId = senderId, specialFollow = true)
        insertFollow(userId = 11L, followUserId = senderId, specialFollow = true)
        // 非特关粉丝：不应收到
        insertFollow(userId = 12L, followUserId = senderId, specialFollow = false)
        // 未关注作者的人：不应收到
        // 其他作者的粉丝：不应收到
        insertFollow(userId = 20L, followUserId = 99L, specialFollow = true)

        val createdAt = LocalDateTime.of(2025, 6, 1, 12, 30, 0)
        val affected = repository.insertCopilotPublishedNotifications(
            senderId = senderId,
            senderName = "maa-plus",
            copilotId = 100L,
            title = "特关作者发布了新作业",
            content = "你特关的作者 @maa-plus 发布了新作业《作业标题》",
            createdAt = createdAt,
        )

        assertEquals(2, affected, "只有 2 名特关粉丝，受影响行数应为 2")
        val ids = allMessages().map { it.id }
        assertEquals(2, ids.size)
        val receivers = ids.map { loadMessage(it)!!.receiverId }.toSet()
        assertEquals(setOf(10L, 11L), receivers)
        ids.forEach { id ->
            val row = loadMessage(id)!!
            assertTrue(row.receiverId in setOf(10L, 11L))
            assertNotificationRow(id, row.receiverId, senderId, "maa-plus", 100L, "特关作者发布了新作业", "你特关的作者 @maa-plus 发布了新作业《作业标题》", createdAt)
        }
    }

    @Test
    fun insertNotifications_noSpecialFollower_returnsZero() {
        val senderId = 1L
        insertFollow(userId = 10L, followUserId = senderId, specialFollow = false)

        val affected = repository.insertCopilotPublishedNotifications(
            senderId = senderId,
            senderName = "maa-plus",
            copilotId = 100L,
            title = "title",
            content = "content",
            createdAt = LocalDateTime.of(2025, 6, 1, 12, 0, 0),
        )

        assertEquals(0, affected)
        assertEquals(0, repository.count())
    }

    @Test
    fun insertNotifications_noFollowersAtAll_returnsZero() {
        val affected = repository.insertCopilotPublishedNotifications(
            senderId = 1L,
            senderName = "maa-plus",
            copilotId = 100L,
            title = "title",
            content = "content",
            createdAt = LocalDateTime.of(2025, 6, 1, 12, 0, 0),
        )
        assertEquals(0, affected)
        assertEquals(0, repository.count())
    }

    @Test
    fun insertNotifications_multipleSpecialFollowers_allFieldsPersisted() {
        val senderId = 1L
        insertFollow(userId = 10L, followUserId = senderId, specialFollow = true)
        insertFollow(userId = 11L, followUserId = senderId, specialFollow = true)

        val createdAt = LocalDateTime.of(2025, 6, 1, 12, 30, 45, 123_000_000)
        val affected = repository.insertCopilotPublishedNotifications(
            senderId = senderId,
            senderName = "作者甲",
            copilotId = 7L,
            title = "新作业",
            content = "快来看",
            createdAt = createdAt,
        )

        assertEquals(2, affected)
        val rows = allMessages().sortedBy { it.receiverId }
        assertEquals(listOf(10L, 11L), rows.map { it.receiverId })
        rows.forEach { row ->
            assertEquals(senderId, row.senderId)
            assertEquals("作者甲", row.senderName)
            assertEquals(SiteMessageType.COPILOT_PUBLISHED, row.type)
            assertEquals("新作业", row.title)
            assertEquals("快来看", row.content)
            assertEquals(7L, row.copilotId)
            assertNull(row.readAt)
            assertEquals(createdAt, row.createdAt)
        }
    }

    @Test
    fun insertNotifications_createdAtTruncatedToMillis() {
        insertFollow(userId = 10L, followUserId = 1L, specialFollow = true)

        // 纳秒精度超出 timestamp(3)：数据库截断到毫秒（基线记录）
        val createdAt = LocalDateTime.of(2025, 6, 1, 12, 0, 0, 123_456_000)
        repository.insertCopilotPublishedNotifications(
            senderId = 1L,
            senderName = "maa-plus",
            copilotId = 1L,
            title = "t",
            content = "c",
            createdAt = createdAt,
        )

        val row = allMessages().single()
        assertEquals(createdAt.truncatedTo(ChronoUnit.MILLIS), row.createdAt)
        assertEquals(123, row.createdAt.nano / 1_000_000)
    }

    @Test
    fun insertNotifications_senderSpecialFollowsSelf_keepsBaseline() {
        // 基线行为：作者把自己加为特关粉丝时，也会给自己发一条通知（SQL 无 self 排除）
        insertFollow(userId = 1L, followUserId = 1L, specialFollow = true)

        val affected = repository.insertCopilotPublishedNotifications(
            senderId = 1L,
            senderName = "maa-plus",
            copilotId = 100L,
            title = "title",
            content = "content",
            createdAt = LocalDateTime.of(2025, 6, 1, 12, 0, 0),
        )

        assertEquals(1, affected)
        val row = allMessages().single()
        assertEquals(1L, row.receiverId)
        assertEquals(1L, row.senderId)
    }

    // ------------------------------------------------------------------
    // findByReceiverId（receiver 过滤 + unreadOnly + created_at DESC, id DESC + 分页）
    // ------------------------------------------------------------------

    @Test
    fun findByReceiverId_filtersByReceiver_andOrdersCreatedDescIdDesc() {
        val t1 = LocalDateTime.of(2025, 1, 1, 10, 0, 0)
        val t2 = LocalDateTime.of(2025, 1, 2, 10, 0, 0)
        val t3 = LocalDateTime.of(2025, 1, 3, 10, 0, 0)
        // 插入顺序即 id 递增：id1(t1) < id2(t2) < id3(t1) < id4(t3)
        val id1 = insertMessage(receiverId = 100L, title = "m1", createdAt = t1)
        val id2 = insertMessage(receiverId = 100L, title = "m2", createdAt = t2)
        val id3 = insertMessage(receiverId = 100L, title = "m3", createdAt = t1)
        val id4 = insertMessage(receiverId = 100L, title = "m4", createdAt = t3)
        // 其他 receiver 的消息不应出现
        insertMessage(receiverId = 200L, title = "other", createdAt = t3)

        val page = repository.findByReceiverId(100L, unreadOnly = false, pageable = PageRequest.of(0, 10))

        assertEquals(4, page.totalElements)
        assertEquals(
            listOf(id4, id2, id3, id1),
            page.content.map { it.id },
            "排序应为 created_at DESC，同 created_at 时 id DESC（id 大者在前）",
        )
        assertTrue(page.content.none { it.receiverId == 200L })
    }

    @Test
    fun findByReceiverId_unreadOnly_filtersReadMessages() {
        insertMessage(receiverId = 100L, title = "unread-1")
        insertMessage(receiverId = 100L, title = "unread-2")
        insertMessage(receiverId = 100L, title = "read", readAt = LocalDateTime.of(2025, 1, 2, 8, 0, 0))

        val unread = repository.findByReceiverId(100L, unreadOnly = true, pageable = PageRequest.of(0, 10))
        val all = repository.findByReceiverId(100L, unreadOnly = false, pageable = PageRequest.of(0, 10))

        assertEquals(2, unread.totalElements)
        assertEquals(3, all.totalElements)
        assertTrue(unread.content.all { it.readAt == null })
        assertEquals(1, all.content.count { it.readAt != null })
    }

    @Test
    fun findByReceiverId_pagination_totalAndContentCorrect() {
        repeat(4) { insertMessage(receiverId = 100L, title = "m$it", createdAt = LocalDateTime.of(2025, 1, 1, 10, 0, 0, it * 1_000_000)) }
        val ids = allMessages().sortedByDescending { it.id }.map { it.id }

        val page0 = repository.findByReceiverId(100L, unreadOnly = false, pageable = PageRequest.of(0, 2))
        val page1 = repository.findByReceiverId(100L, unreadOnly = false, pageable = PageRequest.of(1, 2))
        val pageBeyond = repository.findByReceiverId(100L, unreadOnly = false, pageable = PageRequest.of(2, 2))

        assertEquals(4, page0.totalElements)
        assertEquals(ids.subList(0, 2), page0.content.map { it.id })
        assertEquals(ids.subList(2, 4), page1.content.map { it.id })
        assertTrue(pageBeyond.content.isEmpty(), "越界页内容应为空")
        assertEquals(4, pageBeyond.totalElements, "越界页 total 仍为全量")
    }

    @Test
    fun findByReceiverId_emptyResult_returnsEmptyPage() {
        val page = repository.findByReceiverId(999L, unreadOnly = false, pageable = PageRequest.of(0, 10))
        assertTrue(page.content.isEmpty())
        assertEquals(0, page.totalElements)
    }

    @Test
    fun findByReceiverId_sameCreatedAt_tieBreakByLargerIdFirst() {
        val t = LocalDateTime.of(2025, 1, 1, 10, 0, 0)
        val id1 = insertMessage(receiverId = 100L, title = "first", createdAt = t)
        val id2 = insertMessage(receiverId = 100L, title = "second", createdAt = t)

        val page = repository.findByReceiverId(100L, unreadOnly = false, pageable = PageRequest.of(0, 10))
        assertEquals(listOf(id2, id1), page.content.map { it.id })
    }

    // ------------------------------------------------------------------
    // countUnreadByReceiverId
    // ------------------------------------------------------------------

    @Test
    fun countUnreadByReceiverId_countsOnlyUnreadOfThatReceiver() {
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 100L, readAt = LocalDateTime.of(2025, 1, 2, 8, 0, 0))
        insertMessage(receiverId = 200L)

        assertEquals(2, repository.countUnreadByReceiverId(100L))
        assertEquals(1, repository.countUnreadByReceiverId(200L))
    }

    @Test
    fun countUnreadByReceiverId_noMessages_returnsZero() {
        assertEquals(0, repository.countUnreadByReceiverId(100L))
    }

    // ------------------------------------------------------------------
    // markRead（条件更新，幂等语义）
    // ------------------------------------------------------------------

    @Test
    fun markRead_unread_setsReadAtAndReturnsTrue() {
        val id = insertMessage(receiverId = 100L)
        val readAt = LocalDateTime.of(2025, 1, 2, 8, 30, 0)

        assertTrue(repository.markRead(100L, id, readAt))
        assertEquals(readAt, loadMessage(id)!!.readAt)
    }

    @Test
    fun markRead_alreadyRead_returnsFalseAndKeepsOriginalReadAt() {
        val id = insertMessage(receiverId = 100L)
        val readAt1 = LocalDateTime.of(2025, 1, 2, 8, 0, 0)
        val readAt2 = LocalDateTime.of(2025, 1, 3, 8, 0, 0)
        assertTrue(repository.markRead(100L, id, readAt1))

        assertFalse(repository.markRead(100L, id, readAt2), "已读消息再次 markRead 应返回 false")
        assertEquals(readAt1, loadMessage(id)!!.readAt, "read_at 不应被覆盖")
    }

    @Test
    fun markRead_receiverMismatch_returnsFalseAndKeepsUnread() {
        val id = insertMessage(receiverId = 100L)
        val readAt = LocalDateTime.of(2025, 1, 2, 8, 0, 0)

        assertFalse(repository.markRead(200L, id, readAt))
        assertNull(loadMessage(id)!!.readAt)
    }

    @Test
    fun markRead_nonExistentId_returnsFalse() {
        assertFalse(repository.markRead(100L, 9999L, LocalDateTime.of(2025, 1, 2, 8, 0, 0)))
    }

    // ------------------------------------------------------------------
    // markAllRead（条件批量更新）
    // ------------------------------------------------------------------

    @Test
    fun markAllRead_marksAllUnreadAndReturnsCount() {
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 100L, readAt = LocalDateTime.of(2025, 1, 1, 8, 0, 0))
        val readAt = LocalDateTime.of(2025, 1, 2, 9, 0, 0)

        val affected = repository.markAllRead(100L, readAt)

        assertEquals(3, affected)
        val rows = allMessages()
        assertEquals(4, rows.size)
        assertEquals(3, rows.count { it.readAt == readAt })
        // 原先已读的行不受影响
        assertEquals(1, rows.count { it.readAt == LocalDateTime.of(2025, 1, 1, 8, 0, 0) })
    }

    @Test
    fun markAllRead_noUnread_returnsZero() {
        insertMessage(receiverId = 100L, readAt = LocalDateTime.of(2025, 1, 1, 8, 0, 0))

        assertEquals(0, repository.markAllRead(100L, LocalDateTime.of(2025, 1, 2, 9, 0, 0)))
    }

    @Test
    fun markAllRead_onlyAffectsTargetReceiver() {
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 200L)

        assertEquals(1, repository.markAllRead(100L, LocalDateTime.of(2025, 1, 2, 9, 0, 0)))
        assertEquals(0, repository.countUnreadByReceiverId(100L))
        assertEquals(1, repository.countUnreadByReceiverId(200L), "其他 receiver 的未读不受影响")
    }

    // ------------------------------------------------------------------
    // findById / existsById / deleteById（通用 CRUD）
    // ------------------------------------------------------------------

    @Test
    fun findById_hitAndMiss() {
        val id = insertMessage(receiverId = 100L)

        val hit = repository.findById(id)
        assertNotNull(hit)
        assertEquals(id, hit!!.id)
        assertEquals(100L, hit.receiverId)
        assertEquals(SiteMessageType.COPILOT_PUBLISHED, hit.type)
        assertNull(repository.findById(9999L))
    }

    @Test
    fun existsById_trueAndFalse() {
        val id = insertMessage(receiverId = 100L)
        assertTrue(repository.existsById(id))
        assertFalse(repository.existsById(9999L))
    }

    @Test
    fun deleteById_deletesRowAndReturnsAffectedRows() {
        val id = insertMessage(receiverId = 100L)

        assertTrue(repository.deleteById(id))
        assertNull(loadMessage(id))
        assertFalse(repository.deleteById(id), "重复删除应返回 false")
        assertFalse(repository.existsById(id))
    }

    @Test
    fun deleteById_onlyDeletesTargetRow() {
        val keep = insertMessage(receiverId = 100L)
        val drop = insertMessage(receiverId = 100L)

        assertTrue(repository.deleteById(drop))
        assertNotNull(loadMessage(keep))
        assertEquals(1, repository.count())
    }

    // ------------------------------------------------------------------
    // insertEntity / updateEntity / save / findAll / count
    // ------------------------------------------------------------------

    @Test
    fun insertEntity_backfillsIdAndPersistsAllColumns() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "完整字段",
            content = "内容",
            copilotId = 42L,
            readAt = LocalDateTime.of(2025, 1, 1, 8, 0, 0),
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )

        repository.insertEntity(entity)

        assertTrue(entity.id > 0, "自增主键应回填")
        val loaded = loadMessage(entity.id)!!
        assertEquals(100L, loaded.receiverId)
        assertEquals(7L, loaded.senderId)
        assertEquals("作者甲", loaded.senderName)
        assertEquals(SiteMessageType.COPILOT_PUBLISHED, loaded.type)
        assertEquals("完整字段", loaded.title)
        assertEquals("内容", loaded.content)
        assertEquals(42L, loaded.copilotId)
        assertEquals(LocalDateTime.of(2025, 1, 1, 8, 0, 0), loaded.readAt)
        assertEquals(LocalDateTime.of(2025, 1, 1, 7, 0, 0), loaded.createdAt)
    }

    @Test
    fun insertEntity_nullableColumnsNull() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "t",
            content = "c",
            copilotId = null,
            readAt = null,
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )

        repository.insertEntity(entity)
        val loaded = loadMessage(entity.id)!!
        assertNull(loaded.copilotId)
        assertNull(loaded.readAt)
    }

    @Test
    fun insertEntity_typeEnumStoredAsName() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "t",
            content = "c",
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )
        repository.insertEntity(entity)

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT type FROM site_message WHERE id = ${entity.id}").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("COPILOT_PUBLISHED", rs.getString(1), "枚举落库应为名字而非序数")
                }
            }
        }
    }

    @Test
    fun updateEntity_flushChanges_updatesOnlyChangedColumns() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "原标题",
            content = "原内容",
            copilotId = 42L,
            readAt = null,
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )
        repository.insertEntity(entity)

        entity.title = "新标题"
        repository.updateEntity(entity)

        val loaded = loadMessage(entity.id)!!
        assertEquals("新标题", loaded.title)
        // 未变更列保持不变
        assertEquals("原内容", loaded.content)
        assertEquals(42L, loaded.copilotId)
        assertEquals(7L, loaded.senderId)
        assertEquals(LocalDateTime.of(2025, 1, 1, 7, 0, 0), loaded.createdAt)
        assertNull(loaded.readAt)
    }

    @Test
    fun updateEntity_multipleFieldsAndReadAt() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "t",
            content = "c",
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )
        repository.insertEntity(entity)

        val readAt = LocalDateTime.of(2025, 1, 2, 8, 0, 0)
        entity.title = "t2"
        entity.content = "c2"
        entity.readAt = readAt
        entity.copilotId = 99L
        repository.updateEntity(entity)

        val loaded = loadMessage(entity.id)!!
        assertEquals("t2", loaded.title)
        assertEquals("c2", loaded.content)
        assertEquals(readAt, loaded.readAt)
        assertEquals(99L, loaded.copilotId)
        assertEquals(100L, loaded.receiverId)
    }

    @Test
    fun save_newEntity_insertsAndBackfillsId() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "t",
            content = "c",
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )

        repository.save(entity)

        assertTrue(entity.id > 0)
        assertEquals(1, repository.count())
        assertNotNull(loadMessage(entity.id))
    }

    @Test
    fun save_existingEntity_updatesInsteadOfInsert() {
        val entity = SiteMessageEntity(
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "t",
            content = "c",
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )
        repository.save(entity)
        val id = entity.id

        entity.title = "updated"
        repository.save(entity)

        assertEquals(id, entity.id, "已存在实体不应重新插入")
        assertEquals(1, repository.count())
        assertEquals("updated", loadMessage(id)!!.title)
    }

    @Test
    fun save_nonexistentId_insertsNewRow() {
        val entity = SiteMessageEntity(
            id = 9999L,
            receiverId = 100L,
            senderId = 7L,
            senderName = "作者甲",
            type = SiteMessageType.COPILOT_PUBLISHED,
            title = "t",
            content = "c",
            createdAt = LocalDateTime.of(2025, 1, 1, 7, 0, 0),
        )

        repository.save(entity)

        assertEquals(9999L, entity.id)
        assertNotNull(loadMessage(9999L))
        assertEquals(1, repository.count())
    }

    @Test
    fun findAll_returnsAllRows() {
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 200L)
        insertMessage(receiverId = 300L)

        val all = repository.findAll()
        assertEquals(3, all.size)
        assertEquals(setOf(100L, 200L, 300L), all.map { it.receiverId }.toSet())
    }

    @Test
    fun count_countsAllRows() {
        assertEquals(0, repository.count())
        insertMessage(receiverId = 100L)
        insertMessage(receiverId = 200L)
        assertEquals(2, repository.count())
    }
}
