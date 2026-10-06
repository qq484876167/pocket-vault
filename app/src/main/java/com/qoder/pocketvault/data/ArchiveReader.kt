package com.qoder.pocketvault.data

import com.qoder.pocketvault.core.FileKind
import com.qoder.pocketvault.core.VaultPaths
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

data class ArchiveItem(
    val storedName: String,
    val displayName: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val encrypted: Boolean,
    /** 符号链接 / 设备文件等为 false：列得出来，但绝不落盘（链接可以指向库外）。 */
    val regularFile: Boolean = true,
    /** 包内声明的修改时间；0 表示没有。 */
    val modifiedAtMillis: Long = 0,
)

/** 归档能力三态，每个取值都有真实实现，不存在"标了状态却跑不通"。 */
enum class ArchiveCapability {
    /** 能列目录，也能解压 */
    EXTRACTABLE,

    /** 内容被密码保护：给出正确密码之前解不出来（zip 能列出目录，7z 连目录都在密文里） */
    PASSWORD_REQUIRED,

    /** 本应用处理不了（rar / cbr / iso / zst / lz4 等），界面必须给出"用其他应用打开" */
    UNSUPPORTED,

    /** 格式是支持的，但这个文件本身读不出来（损坏 / 截断）：不能报成"格式不支持" */
    BROKEN,
}

/** 按扩展名归类。注意 .tar.gz 必须在 .gz 之前判断，否则会被当成单文件 gzip。 */
enum class ArchiveFormat {
    ZIP, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ, SEVEN_Z, UNSUPPORTED;

    companion object {
        fun detect(name: String): ArchiveFormat {
            val n = name.lowercase()
            fun has(vararg suffixes: String) = suffixes.any { n.endsWith(it) }
            return when {
                has(".zip", ".jar", ".apk", ".cbz") -> ZIP
                has(".tar.gz", ".tgz") -> TAR_GZ
                has(".tar.bz2", ".tbz2", ".tbz") -> TAR_BZ2
                has(".tar.xz", ".txz") -> TAR_XZ
                has(".tar") -> TAR
                has(".7z") -> SEVEN_Z
                has(".gz") -> GZ
                has(".bz2") -> BZ2
                has(".xz") -> XZ
                else -> UNSUPPORTED
            }
        }

        /** 这些后缀明确知道"有格式但本应用不接"，给准确文案而不是笼统未知。 */
        val knownButUnsupported = setOf("rar", "cbr", "iso", "zst", "lz4", "br", "cab", "lzma", "ear", "tar.zst")

        fun unsupportedReason(name: String): String {
            val ext = FileKind.extensionOf(name).lowercase()
            return when {
                ext == "rar" || ext == "cbr" -> "RAR 的解压授权与本项目许可冲突，所以没有内置支持；可以用其他应用打开"
                ext in knownButUnsupported -> "暂不支持在应用内打开 .$ext"
                else -> "这个文件看起来不是本应用支持的压缩包"
            }
        }
    }
}

/**
 * 读取层：只负责"列出条目"和"把某个条目的字节流出来"。
 * 不碰文件库、不碰 Android，因此可以脱离设备在 JVM 上直接对真归档跑测试。
 */
interface ArchiveReader : Closeable {

    val capability: ArchiveCapability

    suspend fun entries(): List<ArchiveItem>

    /**
     * 单遍遍历给定条目并给出内容流；回调返回后流被关闭。
     * 随机访问型格式（zip / 7z）逐条取流；顺序型（tar 家族）只重扫一遍，
     * 所以回调必须在返回前把该条目读完，不能在返回后继续持有流。
     * 回调是 suspend 的：上层要在里面走文件库的挂起写入。
     */
    suspend fun forEachContent(
        items: List<ArchiveItem>,
        onContent: suspend (ArchiveItem, InputStream) -> Unit,
    )

    /** 只取一个条目的内容（顺序型格式会从头重扫到它）。 */
    fun content(entry: ArchiveItem): InputStream

    /**
     * 用当前密码真的读出一点点字节来验证密码。zip 的中央目录不加密，"能列出条目名"
     * 完全证明不了密码对；7z 才是连目录都在密文里。密码不对时抛 [WrongArchivePasswordException]。
     * 没有加密内容的格式直接放过。
     */
    fun probeReadable(): Unit = Unit

    /** 非目录条目的字节总数；拿不到返回 -1，UI 显示不确定进度。 */
    fun totalBytes(items: List<ArchiveItem>): Long {
        val total = items.filter { !it.isDirectory && it.sizeBytes >= 0 }.sumOf { it.sizeBytes }
        return if (items.any { !it.isDirectory && it.sizeBytes < 0 }) -1 else total
    }
}

class WrongArchivePasswordException(message: String) : IOException(message)

/** 打开失败统一抛可读消息，不让上层靠枚举猜。 */
class ArchiveOpenException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** "读不出来"到底是哪一类：缺密码 / 密码不对（含不支持的加密方式）/ 包本身坏了。 */
internal sealed interface ArchiveFailure {
    data object NeedsPassword : ArchiveFailure
    data object WrongPassword : ArchiveFailure
    data class Broken(val reason: String) : ArchiveFailure
}

/**
 * 判定只看异常类型和"这个包里有没有加密内容"，不看第三方库的文案——
 * 靠文案匹配会在换版本时静默失效，还会把坏包说成"要密码"、把加密包说成"格式不支持"。
 */
internal fun classifyArchiveFailure(
    e: Throwable,
    password: String?,
    knownEncrypted: Boolean,
): ArchiveFailure {
    if (e is WrongArchivePasswordException) return ArchiveFailure.WrongPassword
    if (e is org.apache.commons.compress.PasswordRequiredException) return ArchiveFailure.NeedsPassword
    when ((e as? net.lingala.zip4j.exception.ZipException)?.type) {
        net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD,
        net.lingala.zip4j.exception.ZipException.Type.UNSUPPORTED_ENCRYPTION,
        -> return ArchiveFailure.WrongPassword
        else -> Unit
    }
    // 已经知道包里有加密内容：没给密码就是要密码，给了密码还读不出就是密码不对
    if (knownEncrypted) {
        return if (password.isNullOrEmpty()) ArchiveFailure.NeedsPassword else ArchiveFailure.WrongPassword
    }
    return ArchiveFailure.Broken(e.messageSafe())
}

/**
 * 把底层异常翻译成本应用自己的两种后果之一：密码问题（[WrongArchivePasswordException]）
 * 或包打不开（[ArchiveOpenException]）。上层只看类型，不读文案。
 */
internal fun archiveFailureOf(
    e: Throwable,
    password: String?,
    knownEncrypted: Boolean,
    subject: String,
): IOException = when (val failure = classifyArchiveFailure(e, password, knownEncrypted)) {
    ArchiveFailure.NeedsPassword -> WrongArchivePasswordException("${subject}加了密码，请先输入")
    ArchiveFailure.WrongPassword -> WrongArchivePasswordException("密码不对，或者${subject}用了不支持的加密方式")
    is ArchiveFailure.Broken -> ArchiveOpenException("${subject}打不开：${failure.reason}", e)
}

/**
 * 打开一个包的结论，五种状态彼此独立：界面对"还没给密码"和"密码不对"要给不同提示，
 * 不能再挤进一个 error 字符串里靠文案猜。
 */
sealed interface ArchiveAccess {
    /** 能列能解：本来就没加密，或密码已经真读出过字节 */
    data class Readable(val items: List<ArchiveItem>) : ArchiveAccess

    /** 包是加密的，还没拿到密码 */
    data object NeedsPassword : ArchiveAccess

    /** 给了密码还是解不开：输入框内容要留着让用户改，不要让他重打 */
    data object WrongPassword : ArchiveAccess

    /** 本应用不接的格式（rar / iso / zst 等），要给"用其他应用打开" */
    data class Unsupported(val reason: String) : ArchiveAccess

    /** 包本身坏了：说"打不开"，不能骗用户去输密码，也不能说成格式不支持 */
    data class Broken(val reason: String) : ArchiveAccess
}

/**
 * 开包结果 → 界面状态。判定只看异常类型和"给没给密码"，不读异常文案；
 * 单独成函数是为了让这张表能在纯 JVM 探针里逐条断言。
 */
internal fun archiveAccessOf(password: String?, outcome: Result<List<ArchiveItem>>): ArchiveAccess =
    outcome.fold(
        onSuccess = { ArchiveAccess.Readable(it) },
        onFailure = { e ->
            when {
                e is WrongArchivePasswordException && password.isNullOrEmpty() -> ArchiveAccess.NeedsPassword
                e is WrongArchivePasswordException -> ArchiveAccess.WrongPassword
                else -> ArchiveAccess.Broken(e.messageSafe())
            }
        },
    )

/** 包内条目名属于外部不可信输入，tar / 7z 与 zip 同样要过这道清洗。 */
object ArchiveEntryNames {

    /** 返回安全的相对路径；null 表示该条目必须跳过（绝对路径、盘符、`..`、保留名）。 */
    fun sanitize(raw: String): String? {
        val unified = raw.replace('\\', '/').trimStart()
        if (unified.isEmpty() || unified.startsWith("/")) return null
        if (unified.matches(Regex("^[A-Za-z]:.*"))) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        val joined = segments.map { VaultPaths.sanitizeName(it) }.joinToString("/")
        return try {
            VaultPaths.normalizeRelative(joined).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal fun Throwable.messageSafe(): String = message ?: javaClass.simpleName

/** 密码验证只需要读出一点点字节就够判定，不消费整个条目。 */
private const val PROBE_BYTES = 16

/** 压缩包 → 同名新文件夹的命名规则：去扩展名（含复合后缀），再过一遍安全名清洗。 */
object ArchiveNames {

    private val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.lzma")

    fun stem(name: String): String {
        val lower = name.lowercase()
        val withoutExt = compound.firstOrNull { lower.endsWith(it) }
            ?.let { name.dropLast(it.length) }
            ?: name.substringBeforeLast('.', name)
        return VaultPaths.sanitizeName(withoutExt)
    }
}

/** 只读够声明的字节数就结束，防止顺序流越过条目边界读进下一个条目。 */
internal class LimitedInputStream(
    private val source: InputStream,
    private var remaining: Long,
    /** 遍历顺序流期间不能关父流：关了之后 nextEntry 会在已关闭的流上空转。 */
    private val closeSource: Boolean = true,
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = source.read()
        if (value < 0) return -1
        remaining -= 1
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        // 读尽必须返回 -1：返回 0 会让 copyTo 这类只认负数的循环原地空转
        if (remaining <= 0L) return -1
        val want = minOf(len.toLong(), remaining, Int.MAX_VALUE.toLong()).toInt()
        val count = source.read(b, off, want)
        if (count > 0) remaining -= count
        return count
    }

    override fun available(): Int =
        minOf(source.available().toLong(), maxOf(remaining, 0L)).toInt()

    override fun close() {
        if (closeSource) source.close()
    }
}

/** zip / jar / apk / cbz：zip4j，条目可随机访问。 */
internal class ZipArchiveReader(
    private val file: File,
    private val password: String?,
) : ArchiveReader {

    private val zip: ZipFile = try {
        if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password.toCharArray())
    } catch (e: Exception) {
        throw archiveFailureOf(e, password, knownEncrypted = false, subject = "这个压缩包")
    }

    private val headers: List<FileHeader> = try {
        zip.fileHeaders
    } catch (e: Exception) {
        throw archiveFailureOf(e, password, knownEncrypted = false, subject = "这个压缩包")
    }

    /** 有没有加密条目：密码验证只挑一个真条目试读，代价是几十毫秒级。 */
    private val encryptedHeader: FileHeader? = headers.firstOrNull { it.isEncrypted }

    override val capability: ArchiveCapability
        get() = if (password.isNullOrEmpty() && encryptedHeader != null) {
            ArchiveCapability.PASSWORD_REQUIRED
        } else {
            ArchiveCapability.EXTRACTABLE
        }

    override suspend fun entries(): List<ArchiveItem> = headers.mapNotNull { it.toItem() }
        .sortedWith(compareBy({ !it.isDirectory }, { it.displayName }))

    override suspend fun forEachContent(
        items: List<ArchiveItem>,
        onContent: suspend (ArchiveItem, InputStream) -> Unit,
    ) {
        val wanted = items.map { it.storedName }.toHashSet()
        val byName = items.associateBy { it.storedName }
        headers.filter { it.fileName in wanted }.forEach { header ->
            val item = byName[header.fileName] ?: return@forEach
            streamOf(header).use { onContent(item, it) }
        }
    }

    override fun content(entry: ArchiveItem): InputStream {
        val header = headers.firstOrNull { it.fileName == entry.storedName }
            ?: throw ArchiveOpenException("包里已经找不到 ${entry.displayName}")
        return streamOf(header)
    }

    /**
     * zip 的中央目录不加密，所以"列得出来"不等于"密码对"：
     * 这里真读第一个加密条目的前若干字节，密码错 zip4j 会立刻抛 WRONG_PASSWORD。
     */
    override fun probeReadable() {
        val header = encryptedHeader ?: return
        streamOf(header).use { stream -> stream.read(ByteArray(PROBE_BYTES)) }
    }

    private fun streamOf(header: FileHeader): InputStream = try {
        zip.getInputStream(header)
    } catch (e: Exception) {
        throw archiveFailureOf(e, password, knownEncrypted = header.isEncrypted, subject = "这个压缩包")
    }

    /** 清洗不过就整条丢掉，绝不回退成原始名。 */
    private fun FileHeader.toItem(): ArchiveItem? {
        val safe = ArchiveEntryNames.sanitize(fileName) ?: return null
        return ArchiveItem(
            storedName = fileName,
            displayName = safe,
            isDirectory = isDirectory,
            sizeBytes = uncompressedSize,
            compressedBytes = compressedSize,
            encrypted = isEncrypted,
            regularFile = !isDirectory,
        )
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

/** tar / tar.gz / tar.bz2 / tar.xz：只能顺序读，随机访问靠重扫。 */
internal class TarArchiveReader(
    private val file: File,
    private val format: ArchiveFormat,
) : ArchiveReader {

    override val capability: ArchiveCapability = ArchiveCapability.EXTRACTABLE

    /** 每次调用给一条全新的解压链；关闭顺序：外层 Tar 流关掉内层解压流与文件流。 */
    private fun chain(): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(FileInputStream(file), 64 * 1024)
        // xz 传 allowMultiFrame=true：连续拼接的多个流也要读完，默认单流模式会提前收尾
        val wrapped: InputStream = try {
            when (format) {
                ArchiveFormat.TAR -> raw
                ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw)
                ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
                ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw, true)
                else -> throw ArchiveOpenException("内部错误：$format 不该走 tar 读取器")
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw ArchiveOpenException("打不开这个包：${e.messageSafe()}", e)
        }
        return try {
            TarArchiveInputStream(wrapped)
        } catch (e: Exception) {
            runCatching { wrapped.close() }
            throw ArchiveOpenException("打不开这个包：${e.messageSafe()}", e)
        }
    }

    private suspend fun forEachEntry(action: suspend (TarArchiveInputStream, TarArchiveEntry) -> Unit) {
        chain().use { stream ->
            while (true) {
                val entry = try {
                    stream.nextEntry
                } catch (e: Exception) {
                    throw ArchiveOpenException("读取包内目录失败：${e.messageSafe()}", e)
                } ?: break
                action(stream, entry)
            }
        }
    }

    override suspend fun entries(): List<ArchiveItem> {
        val out = ArrayList<ArchiveItem?>()
        forEachEntry { _, entry -> out += entry.toItem() }
        return out.filterNotNull().sortedWith(compareBy({ !it.isDirectory }, { it.displayName }))
    }

    override suspend fun forEachContent(
        items: List<ArchiveItem>,
        onContent: suspend (ArchiveItem, InputStream) -> Unit,
    ) {
        val byName = items.associateBy { it.storedName }
        forEachEntry { stream, entry ->
            val item = byName[entry.name] ?: return@forEachEntry
            onContent(item, LimitedInputStream(stream, entry.size, closeSource = false))
        }
    }

    override fun content(entry: ArchiveItem): InputStream {
        // 顺序格式没有随机访问：重扫到那个条目，再把限长流交给上层
        val stream = chain()
        try {
            var next = stream.nextEntry
            while (next != null && next.name != entry.storedName) {
                next = stream.nextEntry
            }
            if (next == null) {
                runCatching { stream.close() }
                throw ArchiveOpenException("包里已经找不到 ${entry.displayName}")
            }
            return LimitedInputStream(stream, next.size)
        } catch (e: Exception) {
            runCatching { stream.close() }
            throw e
        }
    }

    /** 清洗不过就整条丢掉：绝不能把原始名当后备，那等于把 zip-slip 防线关掉。 */
    private fun TarArchiveEntry.toItem(): ArchiveItem? {
        val safe = ArchiveEntryNames.sanitize(name) ?: return null
        return ArchiveItem(
            storedName = name,
            displayName = safe,
            isDirectory = isDirectory,
            sizeBytes = size,
            // tar 头里只有未压缩大小：给 -1，界面就不致于拿原大小冒充"压缩后"
            compressedBytes = -1,
            encrypted = false,
            // 符号链接 / 硬链接 / 设备文件：列出来，但绝不落盘（链接可以指向库外）
            regularFile = isPlainFile,
        )
    }

    /**
     * commons 的 `isFile()` 只把目录和名字以 / 结尾的排除掉，**符号链接、硬链接、设备文件
     * 一样返回 true**，所以"绝不落盘"的门禁必须自己把非普通文件列全。
     */
    private val TarArchiveEntry.isPlainFile: Boolean
        get() = isFile && !isSymbolicLink && !isLink && !isCharacterDevice && !isBlockDevice && !isFIFO &&
            !isSparse && !isPaxHeader && !isGlobalPaxHeader && !isGNULongNameEntry && !isGNULongLinkEntry

    override fun close() = Unit
}

/** 7z：SevenZFile 可随机访问；LZMA2 / AES 解码由 org.tukaani:xz 提供。 */
internal class SevenZArchiveReader(file: File, private val password: String?) : ArchiveReader {

    private val archive: SevenZFile?
    private val openError: Exception?

    init {
        var opened: SevenZFile? = null
        var error: Exception? = null
        try {
            opened = if (password.isNullOrEmpty()) SevenZFile(file) else SevenZFile(file, password.toCharArray())
        } catch (e: Exception) {
            error = e
        }
        archive = opened
        openError = error
    }

    private val items: List<SevenZArchiveEntry> = archive?.entries?.toList() ?: emptyList()

    /**
     * 7z 的条目对象没有"是否加密"标记（实测 SevenZArchiveEntry 只有
     * getName/getSize/isDirectory/hasStream）；而 commons 对**密码错误**抛的是
     * `org.tukaani.xz.CorruptedInputException("Compressed data is corrupt")`，与真正的数据损坏
     * 在类型上没法区分。所以：缺密码只信 PasswordRequiredException，
     * "给了密码还读不出"一律按密码不对处理（绝不报成格式不支持）。
     */
    override val capability: ArchiveCapability
        get() = when {
            archive != null -> ArchiveCapability.EXTRACTABLE
            classifyArchiveFailure(
                openError ?: IOException("未知原因"),
                password,
                knownEncrypted = !password.isNullOrEmpty(),
            ) is ArchiveFailure.Broken -> ArchiveCapability.BROKEN
            else -> ArchiveCapability.PASSWORD_REQUIRED
        }

    private fun requireOpen(): SevenZFile = archive ?: throw archiveFailureOf(
        openError ?: IOException("未知原因"),
        password,
        // 头部加密的 7z 读不到任何条目信息：给了密码还是打不开，按密码不对处理，绝不报"格式不支持"
        knownEncrypted = !password.isNullOrEmpty(),
        subject = "这个 7z 包",
    )

    /** 头部没加密、内容加密的 7z：打开是成功的，只有真读字节才会暴露密码不对。 */
    override fun probeReadable() {
        val entry = items.firstOrNull { it.hasStream() && !it.isDirectory } ?: return
        val file = requireOpen()
        try {
            file.getInputStream(entry).use { it.read(ByteArray(PROBE_BYTES)) }
        } catch (e: Exception) {
            throw archiveFailureOf(e, password, knownEncrypted = !password.isNullOrEmpty(), subject = "这个 7z 包")
        }
    }

    override suspend fun entries(): List<ArchiveItem> {
        requireOpen()
        return items.mapNotNull { it.toItem() }
            .sortedWith(compareBy({ !it.isDirectory }, { it.displayName }))
    }

    override suspend fun forEachContent(
        items: List<ArchiveItem>,
        onContent: suspend (ArchiveItem, InputStream) -> Unit,
    ) {
        requireOpen()
        val wanted = items.map { it.storedName }.toHashSet()
        val byName = items.associateBy { it.storedName }
        this.items.filter { it.name in wanted }.forEach { entry ->
            val item = byName[entry.name] ?: return@forEach
            streamOf(entry).use { onContent(item, it) }
        }
    }

    override fun content(entry: ArchiveItem): InputStream {
        requireOpen()
        val found = items.firstOrNull { it.name == entry.storedName }
            ?: throw ArchiveOpenException("包里已经找不到 ${entry.displayName}")
        return streamOf(found)
    }

    private fun streamOf(entry: SevenZArchiveEntry): InputStream = try {
        requireOpen().getInputStream(entry)
    } catch (e: Exception) {
        if (e is WrongArchivePasswordException || e is ArchiveOpenException) throw e
        throw archiveFailureOf(e, password, knownEncrypted = !password.isNullOrEmpty(), subject = "这个 7z 包")
    }

    private fun SevenZArchiveEntry.toItem(): ArchiveItem? {
        val safe = ArchiveEntryNames.sanitize(name) ?: return null
        return ArchiveItem(
            storedName = name,
            displayName = safe,
            isDirectory = isDirectory,
            sizeBytes = size,
            // SevenZArchiveEntry 没有可靠的压缩后大小（只有 isEncrypted/hasStream），给 -1
            compressedBytes = -1,
            encrypted = false,
            regularFile = hasStream() && !isDirectory,
        )
    }

    override fun close() {
        runCatching { archive?.close() }
    }
}

/**
 * 单文件压缩（.gz / .bz2 / .xz）：里面只有一个文件，不是归档，单独分支。
 * 解出来的名字由本地文件名去掉压缩后缀得到，不属于外部不可信输入。
 */
internal class SingleCompressedReader(
    private val file: File,
    private val format: ArchiveFormat,
) : ArchiveReader {

    override val capability: ArchiveCapability = ArchiveCapability.EXTRACTABLE

    private val innerName: String = file.name.substringBeforeLast('.').ifEmpty { file.name }

    private val item = ArchiveItem(
        storedName = innerName,
        displayName = innerName,
        isDirectory = false,
        sizeBytes = -1,        // 头部没有可信的原始大小
        compressedBytes = file.length(),
        encrypted = false,
    )

    override suspend fun entries(): List<ArchiveItem> = listOf(item)

    override suspend fun forEachContent(
        items: List<ArchiveItem>,
        onContent: suspend (ArchiveItem, InputStream) -> Unit,
    ) {
        openRaw().use { onContent(item, it) }
    }

    override fun content(entry: ArchiveItem): InputStream = openRaw()

    override fun totalBytes(items: List<ArchiveItem>): Long = -1

    private fun openRaw(): InputStream {
        val raw = BufferedInputStream(FileInputStream(file), 64 * 1024)
        return try {
            when (format) {
                ArchiveFormat.GZ -> GzipCompressorInputStream(raw)
                ArchiveFormat.BZ2 -> BZip2CompressorInputStream(raw)
                ArchiveFormat.XZ -> XZCompressorInputStream(raw, true)
                else -> throw ArchiveOpenException("内部错误：$format 不是单文件压缩")
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw ArchiveOpenException("打不开这个压缩文件：${e.messageSafe()}", e)
        }
    }

    override fun close() = Unit
}

/** 打开入口：按格式给出对应的 reader；不支持的格式直接返回 null，由上层提示。 */
object ArchiveReaders {

    /** 不支持时抛 ArchiveOpenException（带 reason），让上层有唯一一条错误路径。 */
    fun open(file: File, password: String?): ArchiveReader {
        val format = ArchiveFormat.detect(file.name)
        return when (format) {
            ArchiveFormat.ZIP -> ZipArchiveReader(file, password)
            ArchiveFormat.SEVEN_Z -> SevenZArchiveReader(file, password)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ ->
                TarArchiveReader(file, format)
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ -> SingleCompressedReader(file, format)
            ArchiveFormat.UNSUPPORTED -> throw ArchiveOpenException(
                ArchiveFormat.unsupportedReason(file.name),
            )
        }
    }
}
