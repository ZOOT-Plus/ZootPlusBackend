package plus.maa.backend.service
// TODO 摸一下
//import com.mongodb.client.gridfs.GridFSFindIterable
//import jakarta.servlet.http.HttpServletResponse
//import org.apache.commons.lang3.StringUtils
//import org.bson.Document
//import org.springframework.data.mongodb.core.query.Criteria
//import org.springframework.data.mongodb.core.query.Query
//import org.springframework.data.mongodb.gridfs.GridFsCriteria
//import org.springframework.data.mongodb.gridfs.GridFsOperations
//import org.springframework.stereotype.Service
//import org.springframework.util.Assert
//import org.springframework.web.multipart.MultipartException
//import org.springframework.web.multipart.MultipartFile
//import plus.maa.backend.controller.file.ImageDownloadDTO
//import plus.maa.backend.controller.response.MaaResultException
//import plus.maa.backend.repository.RedisCache
//import java.io.IOException
//import java.text.ParseException
//import java.text.SimpleDateFormat
//import java.util.Date
//import java.util.Locale
//import java.util.UUID
//import java.util.concurrent.TimeUnit
//import java.util.regex.Pattern
//import java.util.zip.ZipEntry
//import java.util.zip.ZipOutputStream
//
///**
// * @author LoMu
// * Date  2023-04-16 23:21
// */
//@Service
//class FileService(
//    private val gridFsOperations: GridFsOperations,
//    private val redisCache: RedisCache,
//) {
//    fun uploadFile(file: MultipartFile, type: String?, version: String, classification: String?, label: String?, ip: String?) {
//        // redis持久化
//
//        var realVersion = version
//        if (redisCache.getCache("NotEnable:UploadFile", String::class.java) != null) {
//            throw MaaResultException(403, "closed uploadfile")
//        }
//
//        // 文件小于1024Bytes不接收
//        if (file.size < 1024) {
//            throw MultipartException("Minimum upload size exceeded")
//        }
//        Assert.notNull(file.originalFilename, "文件名不可为空")
//
//        var antecedentVersion: String? = null
//        if (realVersion.contains("-")) {
//            val split = realVersion.split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
//            realVersion = split[0]
//            antecedentVersion = split[1]
//        }
//
//        val document = Document()
//        document["version"] = realVersion
//        document["antecedentVersion"] = antecedentVersion
//        document["label"] = label
//        document["classification"] = classification
//        document["type"] = type
//        document["ip"] = ip
//
//        val index = file.originalFilename!!.lastIndexOf(".")
//        var fileType = ""
//        if (index != -1) {
//            fileType = file.originalFilename!!.substring(index)
//        }
//
//        val fileName = "Maa-" + UUID.randomUUID().toString().replace("-".toRegex(), "") + fileType
//
//        try {
//            gridFsOperations.store(file.inputStream, fileName, document)
//        } catch (e: IOException) {
//            throw RuntimeException(e)
//        }
//    }
//
//    fun downloadDateFile(date: String?, beLocated: String, delete: Boolean, response: HttpServletResponse) {
//        val formatter = SimpleDateFormat("yyyy-MM-dd")
//        val query: Query
//
//        val d = if (date.isNullOrBlank()) {
//            Date(System.currentTimeMillis())
//        } else {
//            try {
//                formatter.parse(date)
//            } catch (e: ParseException) {
//                throw RuntimeException(e)
//            }
//        }
//
//        query = if (StringUtils.isBlank(beLocated) || "after" == beLocated.lowercase(Locale.getDefault())) {
//            Query(Criteria.where("metadata").gte(d))
//        } else {
//            Query(Criteria.where("uploadDate").lte(d))
//        }
//        val files = gridFsOperations.find(query)
//
//        response.addHeader("Content-Disposition", "attachment;filename=" + System.currentTimeMillis() + ".zip")
//
//        gzip(response, files)
//
//        if (delete) {
//            gridFsOperations.delete(query)
//        }
//    }
//
//    fun downloadFile(imageDownloadDTO: ImageDownloadDTO, response: HttpServletResponse) {
//        val query = Query()
//        val criteriaSet: MutableSet<Criteria> = HashSet()
//
//        // 图片类型
//        criteriaSet.add(
//            GridFsCriteria.whereMetaData("type").regex(Pattern.compile(imageDownloadDTO.type, Pattern.CASE_INSENSITIVE)),
//        )
//
//        // 指定下载某个类型的图片
//        if (!imageDownloadDTO.classification.isNullOrBlank()) {
//            criteriaSet.add(
//                GridFsCriteria.whereMetaData("classification")
//                    .regex(Pattern.compile(imageDownloadDTO.classification, Pattern.CASE_INSENSITIVE)),
//            )
//        }
//
//        // 指定版本或指定范围版本
//        if (!imageDownloadDTO.version.isNullOrEmpty()) {
//            val version = imageDownloadDTO.version
//
//            if (version.size == 1) {
//                var antecedentVersion: String? = null
//                if (version[0].contains("-")) {
//                    val split = version[0].split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
//                    antecedentVersion = split[1]
//                }
//                criteriaSet.add(
//                    GridFsCriteria.whereMetaData("version").`is`(version[0]).and("antecedentVersion").`is`(antecedentVersion),
//                )
//            } else if (version.size == 2) {
//                criteriaSet.add(GridFsCriteria.whereMetaData("version").gte(version[0]).lte(version[1]))
//            }
//        }
//
//        if (!imageDownloadDTO.label.isNullOrBlank()) {
//            criteriaSet.add(
//                GridFsCriteria.whereMetaData("label").regex(Pattern.compile(imageDownloadDTO.label, Pattern.CASE_INSENSITIVE)),
//            )
//        }
//
//        val criteria = Criteria().andOperator(criteriaSet)
//        query.addCriteria(criteria)
//
//        val gridFSFiles = gridFsOperations.find(query)
//
//        response.addHeader("Content-Disposition", "attachment;filename=" + "Maa-" + imageDownloadDTO.type + ".zip")
//
//        gzip(response, gridFSFiles)
//
//        if (imageDownloadDTO.delete) {
//            gridFsOperations.delete(query)
//        }
//    }
//
//    fun disable(): String {
//        isUploadEnabled = false
//        return "已关闭"
//    }
//
//    fun enable(): String {
//        isUploadEnabled = true
//        return "已启用"
//    }
//
//    /**
//     * 上传功能状态
//     */
//    var isUploadEnabled: Boolean
//        get() = redisCache.getCache("NotEnable:UploadFile", String::class.java) == null
//        set(enabled) {
//            // Fixme: redis recovery solution should be added, or change to another storage
//            if (enabled) {
//                redisCache.removeCache("NotEnable:UploadFile")
//            } else {
//                redisCache.setCache("NotEnable:UploadFile", "1", 0, TimeUnit.DAYS)
//            }
//        }
//
//    private fun gzip(response: HttpServletResponse, files: GridFSFindIterable) {
//        try {
//            ZipOutputStream(response.outputStream).use { zipOutputStream ->
//                for (file in files) {
//                    val zipEntry = ZipEntry(file.filename)
//                    gridFsOperations.getResource(file).inputStream.use { inputStream ->
//                        // 添加压缩文件
//                        zipOutputStream.putNextEntry(zipEntry)
//
//                        val bytes = ByteArray(1024)
//                        var len: Int
//                        while ((inputStream.read(bytes).also { len = it }) != -1) {
//                            zipOutputStream.write(bytes, 0, len)
//                            zipOutputStream.flush()
//                        }
//                    }
//                }
//            }
//        } catch (e: IOException) {
//            throw RuntimeException(e)
//        }
//    }
//}
