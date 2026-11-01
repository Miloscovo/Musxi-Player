package tech.musxi.core.update

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class ReleaseAsset(
    val id: Long,
    val name: String,
    val bytes: Long,
    val url: String,
    val uploaded: Boolean = true,
)

data class UpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val assetName: String,
    val bytes: Long,
    val sha256: String,
    val signerSha256: String,
)

object UpdateManifestParser {
    const val MAX_APK_BYTES: Long = 1_073_741_824L

    fun parse(data: ByteArray, tag: String, assets: List<ReleaseAsset>): UpdateManifest {
        if (data.isEmpty() || data.size > ManifestRules.MAX_MANIFEST_BYTES) {
            ManifestRules.invalid()
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            ManifestRules.invalid()
        }

        val fields = FlatJsonObject(text).parse()
        if (fields.keys != ManifestRules.FIELDS) {
            ManifestRules.invalid()
        }

        val schemaVersion = fields.positiveInt("schemaVersion")
        val packageName = fields.string("packageName")
        val versionName = fields.string("versionName")
        val versionCode = fields.positiveLong("versionCode")
        val minSdk = fields.positiveInt("minSdk")
        val assetName = fields.string("assetName")
        val bytes = fields.positiveLong("bytes")
        val sha256 = fields.string("sha256")
        val signerSha256 = fields.string("signerSha256")

        ManifestRules.requireSchema(schemaVersion)
        ManifestRules.requireOfficialPackage(packageName)
        ManifestRules.requireVersionBinding(versionName, tag)
        ManifestRules.requireApkSize(bytes)
        ManifestRules.requireAssetName(assetName, versionName)
        ManifestRules.requireHash(sha256, UpdateFailure.INVALID_MANIFEST)
        ManifestRules.requireHash(signerSha256, UpdateFailure.INVALID_MANIFEST)

        val namedAssets = assets.filter { it.name == assetName }
        if (namedAssets.size > 1) {
            ManifestRules.invalid()
        }
        val asset = namedAssets.singleOrNull()
            ?: throw UpdateException(UpdateFailure.DOWNLOAD_UNAVAILABLE)
        if (!asset.uploaded) {
            throw UpdateException(UpdateFailure.DOWNLOAD_UNAVAILABLE)
        }
        if (asset.id <= 0L || asset.bytes != bytes ||
            !OfficialUpdateSource.isAsset(asset.url, tag, assetName)
        ) {
            ManifestRules.invalid()
        }

        return UpdateManifest(
            schemaVersion = schemaVersion,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            assetName = assetName,
            bytes = bytes,
            sha256 = sha256,
            signerSha256 = signerSha256,
        )
    }
}

object OfficialUpdateSource {
    const val REPOSITORY: String = "qwt1011/Musxi-Player-Android"

    private const val GITHUB_HOST = "github.com"
    private const val RELEASE_ASSET_HOST = "release-assets.githubusercontent.com"
    private const val OBJECTS_HOST = "objects.githubusercontent.com"
    private val repositoryDownloadPath = Regex(
        "^/qwt1011/Musxi-Player-Android/releases/download/" +
            "${ManifestRules.SAFE_SEGMENT_PATTERN}/${ManifestRules.SAFE_SEGMENT_PATTERN}$",
    )

    fun isReleasePage(url: String, tag: String): Boolean {
        if (!ManifestRules.isSafeSegment(tag)) return false
        val uri = parseUri(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host == GITHUB_HOST &&
            !uri.hasUserInfo() &&
            uri.port == -1 &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath == "/$REPOSITORY/releases/tag/$tag"
    }

    fun isAsset(url: String, tag: String, name: String): Boolean {
        if (!ManifestRules.isSafeSegment(tag) || !ManifestRules.isSafeSegment(name)) return false
        val uri = parseUri(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host == GITHUB_HOST &&
            !uri.hasUserInfo() &&
            uri.port == -1 &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath == "/$REPOSITORY/releases/download/$tag/$name"
    }

    fun isRedirect(url: String): Boolean {
        val uri = parseUri(url) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.hasUserInfo() ||
            (uri.port != -1 && uri.port != 443) ||
            uri.rawFragment != null ||
            uri.host == null
        ) {
            return false
        }

        val path = uri.rawPath ?: return false
        if (!path.startsWith("/") || path.contains('\\') || path.contains('%')) return false

        return when (uri.host) {
            RELEASE_ASSET_HOST, OBJECTS_HOST -> true
            GITHUB_HOST ->
                uri.rawQuery == null &&
                    repositoryDownloadPath.matches(path) &&
                    path.indexOf("//", startIndex = 1) == -1
            else -> false
        }
    }

    private fun parseUri(value: String): URI? =
        if (value.any { it <= '\u001f' || it == ' ' }) {
            null
        } else {
            runCatching { URI(value) }.getOrNull()
        }
}

object ApkUpdatePolicy {
    fun verify(
        manifest: UpdateManifest,
        currentPackage: String,
        currentVersionCode: Long,
        archivePackage: String,
        archiveVersionCode: Long,
        archiveVersionName: String?,
        archiveSigners: Set<String>,
        installedSigners: Set<String>,
        sdk: Int,
    ) {
        ManifestRules.requireManifest(manifest)
        if (currentPackage != ManifestRules.OFFICIAL_PACKAGE ||
            archivePackage != currentPackage ||
            manifest.packageName != currentPackage
        ) {
            throw UpdateException(UpdateFailure.PACKAGE_MISMATCH)
        }
        if (currentVersionCode <= 0L ||
            manifest.versionCode <= currentVersionCode ||
            archiveVersionCode != manifest.versionCode ||
            archiveVersionName != manifest.versionName
        ) {
            throw UpdateException(UpdateFailure.VERSION_NOT_NEWER)
        }

        val expectedSigners = setOf(manifest.signerSha256)
        if (archiveSigners.isEmpty() ||
            installedSigners.isEmpty() ||
            archiveSigners != expectedSigners ||
            installedSigners != expectedSigners
        ) {
            throw UpdateException(UpdateFailure.SIGNATURE_MISMATCH)
        }

        if (sdk <= 0 || manifest.minSdk > sdk) {
            throw UpdateException(UpdateFailure.UNSUPPORTED_SDK)
        }
    }
}

private object ManifestRules {
    const val MAX_MANIFEST_BYTES = 4096
    const val OFFICIAL_PACKAGE = "tech.musxi.player"
    const val SAFE_SEGMENT_PATTERN = "[0-9A-Za-z][0-9A-Za-z0-9._-]*"

    val FIELDS: Set<String> = setOf(
        "schemaVersion",
        "packageName",
        "versionName",
        "versionCode",
        "minSdk",
        "assetName",
        "bytes",
        "sha256",
        "signerSha256",
    )
    private val safeSegment = Regex("^$SAFE_SEGMENT_PATTERN$")
    private val hash = Regex("^[0-9a-f]{64}$")

    fun requireManifest(manifest: UpdateManifest) {
        requireSchema(manifest.schemaVersion)
        requireOfficialPackage(manifest.packageName)
        if (!isSafeSegment(manifest.versionName) ||
            manifest.versionCode <= 0L ||
            manifest.minSdk <= 0
        ) {
            invalid()
        }
        requireApkSize(manifest.bytes)
        requireAssetName(manifest.assetName, manifest.versionName)
        requireHash(manifest.sha256, UpdateFailure.INVALID_MANIFEST)
        requireHash(manifest.signerSha256, UpdateFailure.INVALID_MANIFEST)
    }

    fun requireSchema(schemaVersion: Int) {
        if (schemaVersion != 1) invalid()
    }

    fun requireOfficialPackage(packageName: String) {
        if (packageName != OFFICIAL_PACKAGE) invalid()
    }

    fun requireVersionBinding(versionName: String, tag: String) {
        val normalizedTag = when {
            tag.startsWith("v") || tag.startsWith("V") -> tag.substring(1)
            else -> tag
        }
        if (!isSafeSegment(tag) ||
            SemanticVersion.parse(versionName) == null ||
            normalizedTag != versionName
        ) {
            invalid()
        }
    }

    fun requireApkSize(bytes: Long) {
        if (bytes <= 0L || bytes > UpdateManifestParser.MAX_APK_BYTES) invalid()
    }

    fun requireAssetName(assetName: String, versionName: String) {
        if (!isSafeSegment(versionName) ||
            !isSafeSegment(assetName) ||
            assetName != "Musxi-Player-$versionName.apk"
        ) {
            invalid()
        }
    }

    fun requireHash(value: String, reason: UpdateFailure) {
        if (!hash.matches(value)) throw UpdateException(reason)
    }

    fun isSafeSegment(value: String): Boolean = safeSegment.matches(value)

    fun invalid(): Nothing = throw UpdateException(UpdateFailure.INVALID_MANIFEST)
}

private sealed interface FlatJsonValue {
    data class Text(val value: String) : FlatJsonValue
    data class Integer(val value: Long) : FlatJsonValue
    data object BooleanValue : FlatJsonValue
    data object NullValue : FlatJsonValue
}

private class FlatJsonObject(private val source: String) {
    private var position = 0

    fun parse(): Map<String, FlatJsonValue> {
        skipWhitespace()
        expect('{')
        skipWhitespace()

        val fields = linkedMapOf<String, FlatJsonValue>()
        if (peek() == '}') {
            position++
        } else {
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseValue()
                if (fields.put(key, value) != null) ManifestRules.invalid()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        position++
                        skipWhitespace()
                    }
                    '}' -> {
                        position++
                        break
                    }
                    else -> ManifestRules.invalid()
                }
            }
        }

        skipWhitespace()
        if (position != source.length) ManifestRules.invalid()
        return fields
    }

    private fun parseValue(): FlatJsonValue = when (peek()) {
        '"' -> FlatJsonValue.Text(parseString())
        '{', '[' -> ManifestRules.invalid()
        't' -> {
            expectLiteral("true")
            FlatJsonValue.BooleanValue
        }
        'f' -> {
            expectLiteral("false")
            FlatJsonValue.BooleanValue
        }
        'n' -> {
            expectLiteral("null")
            FlatJsonValue.NullValue
        }
        '-', in '0'..'9' -> FlatJsonValue.Integer(parseInteger())
        else -> ManifestRules.invalid()
    }

    private fun parseInteger(): Long {
        val start = position
        if (peek() == '-') position++
        if (position >= source.length) ManifestRules.invalid()

        when (val first = source[position++]) {
            '0' -> {
                if (position < source.length && source[position].isDigit()) {
                    ManifestRules.invalid()
                }
            }
            in '1'..'9' -> {
                while (position < source.length && source[position].isDigit()) position++
            }
            else -> {
                ManifestRules.invalid()
            }
        }
        if (position < source.length && (source[position] == '.' ||
                source[position] == 'e' || source[position] == 'E')
        ) {
            ManifestRules.invalid()
        }
        return source.substring(start, position).toLongOrNull() ?: ManifestRules.invalid()
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (position < source.length) {
            val character = source[position++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> appendEscape(result)
                character < ' ' -> ManifestRules.invalid()
                else -> result.append(character)
            }
        }
        ManifestRules.invalid()
    }

    private fun appendEscape(result: StringBuilder) {
        if (position >= source.length) ManifestRules.invalid()
        when (val escaped = source[position++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> ManifestRules.invalid()
        }
    }

    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = readHexCodeUnit()
        when {
            first in 0xD800..0xDBFF -> {
                if (position + 2 > source.length ||
                    source[position] != '\\' || source[position + 1] != 'u'
                ) {
                    ManifestRules.invalid()
                }
                position += 2
                val second = readHexCodeUnit()
                if (second !in 0xDC00..0xDFFF) ManifestRules.invalid()
                result.append(first.toChar()).append(second.toChar())
            }
            first in 0xDC00..0xDFFF -> ManifestRules.invalid()
            else -> result.append(first.toChar())
        }
    }

    private fun readHexCodeUnit(): Int {
        if (position + 4 > source.length) ManifestRules.invalid()
        var value = 0
        repeat(4) {
            val digit = source[position++].digitToIntOrNull(16)
                ?: ManifestRules.invalid()
            value = value * 16 + digit
        }
        return value
    }

    private fun expectLiteral(literal: String) {
        if (!source.startsWith(literal, position)) ManifestRules.invalid()
        position += literal.length
    }

    private fun expect(expected: Char) {
        if (peek() != expected) ManifestRules.invalid()
        position++
    }

    private fun peek(): Char {
        if (position >= source.length) ManifestRules.invalid()
        return source[position]
    }

    private fun skipWhitespace() {
        while (position < source.length) {
            when (source[position]) {
                ' ', '\t', '\r', '\n' -> position++
                else -> return
            }
        }
    }
}

private fun Map<String, FlatJsonValue>.string(name: String): String =
    (this[name] as? FlatJsonValue.Text)?.value ?: ManifestRules.invalid()

private fun Map<String, FlatJsonValue>.positiveInt(name: String): Int {
    val value = positiveLong(name)
    if (value > Int.MAX_VALUE.toLong()) ManifestRules.invalid()
    return value.toInt()
}

private fun Map<String, FlatJsonValue>.positiveLong(name: String): Long {
    val value = (this[name] as? FlatJsonValue.Integer)?.value
        ?: ManifestRules.invalid()
    if (value <= 0L) ManifestRules.invalid()
    return value
}

private fun URI.hasUserInfo(): Boolean = rawUserInfo != null
