package com.qoder.pocketvault.core

enum class FileKind(val label: String) {
    FOLDER("文件夹"),
    IMAGE("图片"),
    VIDEO("视频"),
    AUDIO("音频"),
    DOCUMENT("文档"),
    ARCHIVE("压缩包"),
    OTHER("其他");

    val isBrowseableDir: Boolean get() = this == FOLDER

    companion object {
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "avif",
            "svg", "tiff", "tif", "ico", "dng", "cr2", "nef", "arw", "psd", "jxl"
        )
        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "mov", "avi", "flv", "wmv", "webm", "m4v", "mpg", "mpeg",
            "ts", "3gp", "rm", "rmvb", "vob", "ogv"
        )
        private val AUDIO_EXT = setOf(
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus", "wma", "amr",
            "aiff", "ape", "mid", "midi", "dff", "dsf"
        )
        private val DOCUMENT_EXT = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "markdown",
            "log", "csv", "json", "xml", "yaml", "yml", "ini", "conf", "properties",
            "sql", "epub", "mobi", "azw3", "caj", "key", "pages", "numbers"
        )
        private val ARCHIVE_EXT = setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "txz", "tbz", "tbz2",
            "iso", "apk", "jar", "war", "cbz", "cbr"
        )

        fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

        /** 先按扩展名判断（SAF 返回的 mime 常常是 application/octet-stream），再回落到 mime 前缀。 */
        fun classify(fileName: String, mimeType: String?): FileKind {
            val ext = extensionOf(fileName)
            when (ext) {
                in IMAGE_EXT -> return IMAGE
                in VIDEO_EXT -> return VIDEO
                in AUDIO_EXT -> return AUDIO
                in DOCUMENT_EXT -> return DOCUMENT
                in ARCHIVE_EXT -> return ARCHIVE
            }
            val mime = mimeType?.lowercase()?.substringBefore(';') ?: return OTHER
            return when {
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime == "application/pdf" || mime.startsWith("text/") ||
                    mime.contains("word") || mime.contains("excel") || mime.contains("presentation") ||
                    mime == "application/epub+zip" -> DOCUMENT
                mime.contains("zip") || mime.contains("rar") || mime.contains("7z") ||
                    mime == "application/x-tar" || mime == "application/gzip" ||
                    mime == "application/x-7z-compressed" || mime.contains("compressed") -> ARCHIVE
                else -> OTHER
            }
        }

        fun guessMime(fileName: String): String {
            val ext = extensionOf(fileName)
            MIME_BY_EXT[ext]?.let { return it }
            return when (classify(fileName, null)) {
                IMAGE -> "image/" + (ext.ifEmpty { "jpeg" })
                VIDEO -> "video/" + (ext.ifEmpty { "mp4" })
                AUDIO -> "audio/" + (ext.ifEmpty { "mpeg" })
                else -> "application/octet-stream"
            }
        }

        /** 扩展名 -> mime 的显式表，Office/OFD/压缩包这类必须给对，否则其他应用无法识别。 */
        private val MIME_BY_EXT: Map<String, String> = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic",
            "bmp" to "image/bmp", "svg" to "image/svg+xml", "avif" to "image/avif",
            "mp4" to "video/mp4", "mkv" to "video/x-matroska", "mov" to "video/quicktime",
            "avi" to "video/x-msvideo", "webm" to "video/webm", "m4v" to "video/x-m4v",
            "3gp" to "video/3gpp", "ts" to "video/mp2t",
            "mp3" to "audio/mpeg", "wav" to "audio/x-wav", "flac" to "audio/flac",
            "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
            "opus" to "audio/opus", "amr" to "audio/amr",
            "pdf" to "application/pdf", "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
            "json" to "application/json", "xml" to "application/xml", "log" to "text/plain",
            "epub" to "application/epub+zip", "zip" to "application/zip",
            "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
            "tar" to "application/x-tar", "gz" to "application/gzip", "iso" to "application/x-iso9660-image",
            "apk" to "application/vnd.android.package-archive"
        )
    }
}
