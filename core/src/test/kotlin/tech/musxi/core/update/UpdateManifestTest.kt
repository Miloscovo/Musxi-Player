package tech.musxi.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun parsesStrictSchemaAndUniqueOfficialAsset() {
        val manifest = parse()

        assertEquals(1, manifest.schemaVersion)
        assertEquals("tech.musxi.player", manifest.packageName)
        assertEquals("0.2.0", manifest.versionName)
        assertEquals(4L, manifest.versionCode)
        assertEquals("Musxi-Player-0.2.0.apk", manifest.assetName)
        assertEquals(27_000_000L, manifest.bytes)
    }

    @Test
    fun versionBindingAcceptsSemanticVersionWithVOrVTagAndEnforcesOneGiBLimit() {
        for (candidateTag in listOf("v0.2.0", "V0.2.0")) {
            val asset = validAsset().copy(
                url = assetUrl.replace("/v0.2.0/", "/$candidateTag/"),
            )
            assertEquals(
                "0.2.0",
                UpdateManifestParser.parse(manifestBytes(), candidateTag, listOf(asset)).versionName,
            )
        }

        val maximum = UpdateManifestParser.parse(
            manifestBytes().toString(Charsets.UTF_8)
                .replace("\"bytes\":27000000", "\"bytes\":${UpdateManifestParser.MAX_APK_BYTES}")
                .toByteArray(Charsets.UTF_8),
            tag,
            listOf(validAsset().copy(bytes = UpdateManifestParser.MAX_APK_BYTES)),
        )
        assertEquals(UpdateManifestParser.MAX_APK_BYTES, maximum.bytes)

        assertInvalid(
            manifestBytes().toString(Charsets.UTF_8)
                .replace("\"bytes\":27000000", "\"bytes\":${UpdateManifestParser.MAX_APK_BYTES + 1}")
                .toByteArray(Charsets.UTF_8),
        )
    }

    @Test
    fun rejectsOversizedAndNonUtf8Manifest() {
        assertInvalid(ByteArray(4097) { ' '.code.toByte() })
        assertInvalid(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28))
    }

    @Test
    fun rejectsMissingDuplicateUnknownAndNonIntegerFields() {
        val valid = validFields()
        val invalidFields = listOf(
            valid.replace("\"bytes\":27000000,", ""),
            valid.replace("\"minSdk\":26,", "\"minSdk\":26,\"minSdk\":26,"),
            valid.replace("\"bytes\":27000000,", "\"bytes\":27000000,\"extra\":1,"),
            valid.replace("\"versionCode\":4", "\"versionCode\":4.0"),
            valid.replace("\"versionCode\":4", "\"versionCode\":\"4\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
        )

        invalidFields.forEach { assertInvalid(fieldsJson(it)) }
    }

    @Test
    fun enforcesSchemaPackageTagVersionHashesAndAssetName() {
        val invalidFields = listOf(
            validFields().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            validFields().replace("tech.musxi.player", "com.example.musxi"),
            validFields().replace("\"versionName\":\"0.2.0\"", "\"versionName\":\"0.2.1\""),
            validFields().replace("\"versionName\":\"0.2.0\"", "\"versionName\":\"latest\""),
            validFields().replace(validSha, "A".repeat(64)),
            validFields().replace(validSigner, "b".repeat(63)),
            validFields().replace(
                "Musxi-Player-0.2.0.apk",
                "Musxi-Player-..%2F0.2.0.apk",
            ),
            validFields().replace("\"bytes\":27000000", "\"bytes\":0"),
            validFields().replace("\"minSdk\":26", "\"minSdk\":0"),
        )

        invalidFields.forEach { assertInvalid(fieldsJson(it), includeAsset = false) }
    }

    @Test
    fun requiresExactlyOneUploadedAssetWithMatchingSizeAndUrl() {
        val asset = validAsset()
        assertFailure(UpdateFailure.DOWNLOAD_UNAVAILABLE) {
            UpdateManifestParser.parse(manifestBytes(), tag, emptyList())
        }
        assertFailure(UpdateFailure.DOWNLOAD_UNAVAILABLE) {
            UpdateManifestParser.parse(manifestBytes(), tag, listOf(asset.copy(uploaded = false)))
        }
        assertFailure(UpdateFailure.INVALID_MANIFEST) {
            UpdateManifestParser.parse(manifestBytes(), tag, listOf(asset, asset.copy(id = 8)))
        }
        assertFailure(UpdateFailure.INVALID_MANIFEST) {
            UpdateManifestParser.parse(manifestBytes(), tag, listOf(asset.copy(bytes = 1)))
        }
        assertFailure(UpdateFailure.INVALID_MANIFEST) {
            UpdateManifestParser.parse(
                manifestBytes(),
                tag,
                listOf(asset.copy(url = asset.url.replace("github.com", "example.com"))),
            )
        }
    }

    @Test
    fun officialReleaseAndAssetUrlsAreExact() {
        assertTrue(OfficialUpdateSource.isReleasePage(releasePage, tag))
        assertTrue(OfficialUpdateSource.isAsset(asset.url, tag, asset.name))

        listOf(
            releasePage.replace("https://", "http://"),
            releasePage.replace("github.com", "github.com.evil.test"),
            "$releasePage?download=1",
            "$releasePage#fragment",
            releasePage.replace("qwt1011/", "qwt1011@evil.test/"),
        ).forEach { assertFalse(OfficialUpdateSource.isReleasePage(it, tag)) }

        listOf(
            asset.url.replace("https://", "http://"),
            asset.url.replace("github.com", "github.com.evil.test"),
            "${asset.url}?download=1",
            "${asset.url}#fragment",
            asset.url.replace("Musxi-Player-0.2.0.apk", "..%2FMusxi-Player-0.2.0.apk"),
            asset.url.replace("qwt1011/Musxi-Player-Android", "other/project"),
        ).forEach { assertFalse(OfficialUpdateSource.isAsset(it, tag, asset.name)) }
    }

    @Test
    fun redirectsAllowOnlyOfficialHttpsHostsAndRepositoryDownloads() {
        assertTrue(OfficialUpdateSource.isRedirect("https://release-assets.githubusercontent.com/a?signature=x"))
        assertTrue(OfficialUpdateSource.isRedirect("https://objects.githubusercontent.com/a"))
        assertTrue(OfficialUpdateSource.isRedirect("https://github.com:443/qwt1011/Musxi-Player-Android/releases/download/v0.2.0/Musxi-Player-0.2.0.apk"))
        assertTrue(OfficialUpdateSource.isRedirect(asset.url))

        listOf(
            "http://release-assets.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com.evil.test/a",
            "https://user@release-assets.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com:444/a",
            "https://release-assets.githubusercontent.com/a#fragment",
            "https://github.com/qwt1011/Musxi-Player-Android/releases/download/../v0.2.0/a.apk",
            "https://github.com/other/project/releases/download/v0.2.0/a.apk",
        ).forEach { assertFalse(it, OfficialUpdateSource.isRedirect(it)) }
    }

    @Test
    fun apkPolicyAcceptsManifestApkAndInstalledSignerMatch() {
        val manifest = parse()

        ApkUpdatePolicy.verify(
            manifest = manifest,
            currentPackage = "tech.musxi.player",
            currentVersionCode = 3L,
            archivePackage = "tech.musxi.player",
            archiveVersionCode = 4L,
            archiveVersionName = "0.2.0",
            archiveSigners = setOf(validSigner),
            installedSigners = setOf(validSigner),
            sdk = 35,
        )
    }

    @Test
    fun apkPolicyRejectsPackageVersionSignatureAndSdkMismatch() {
        val manifest = parse()

        assertPolicyFailure(UpdateFailure.PACKAGE_MISMATCH, archivePackage = "evil.package")
        assertPolicyFailure(UpdateFailure.PACKAGE_MISMATCH, currentPackage = "tech.musxi.player.dev")
        assertPolicyFailure(UpdateFailure.VERSION_NOT_NEWER, currentVersionCode = 4L)
        assertPolicyFailure(UpdateFailure.VERSION_NOT_NEWER, archiveVersionCode = 5L)
        assertPolicyFailure(UpdateFailure.VERSION_NOT_NEWER, archiveVersionName = "0.2.1")
        assertPolicyFailure(
            UpdateFailure.SIGNATURE_MISMATCH,
            archiveSigners = setOf("c".repeat(64)),
        )
        assertPolicyFailure(
            UpdateFailure.SIGNATURE_MISMATCH,
            installedSigners = setOf("c".repeat(64)),
        )
        assertPolicyFailure(UpdateFailure.UNSUPPORTED_SDK, sdk = 25)
    }

    private fun parse(): UpdateManifest =
        UpdateManifestParser.parse(manifestBytes(), tag, listOf(validAsset()))

    private fun manifestBytes(): ByteArray = fieldsJson(validFields())

    private fun fieldsJson(fields: String): ByteArray = "{$fields}".toByteArray(Charsets.UTF_8)

    private fun assertInvalid(bytes: ByteArray, includeAsset: Boolean = true) {
        assertFailure(UpdateFailure.INVALID_MANIFEST) {
            UpdateManifestParser.parse(
                bytes,
                tag,
                if (includeAsset) listOf(validAsset()) else emptyList(),
            )
        }
    }

    private fun assertPolicyFailure(
        reason: UpdateFailure,
        currentPackage: String = "tech.musxi.player",
        currentVersionCode: Long = 3L,
        archivePackage: String = "tech.musxi.player",
        archiveVersionCode: Long = 4L,
        archiveVersionName: String? = "0.2.0",
        archiveSigners: Set<String> = setOf(validSigner),
        installedSigners: Set<String> = setOf(validSigner),
        sdk: Int = 35,
    ) {
        assertFailure(reason) {
            ApkUpdatePolicy.verify(
                manifest = parse(),
                currentPackage = currentPackage,
                currentVersionCode = currentVersionCode,
                archivePackage = archivePackage,
                archiveVersionCode = archiveVersionCode,
                archiveVersionName = archiveVersionName,
                archiveSigners = archiveSigners,
                installedSigners = installedSigners,
                sdk = sdk,
            )
        }
    }

    private fun assertFailure(reason: UpdateFailure, block: () -> Unit) {
        val error = assertThrows(UpdateException::class.java, block)
        assertEquals(reason, error.reason)
    }

    private fun validFields(): String =
        "\"schemaVersion\":1," +
            "\"packageName\":\"tech.musxi.player\"," +
            "\"versionName\":\"0.2.0\"," +
            "\"versionCode\":4," +
            "\"minSdk\":26," +
            "\"assetName\":\"Musxi-Player-0.2.0.apk\"," +
            "\"bytes\":27000000," +
            "\"sha256\":\"$validSha\"," +
            "\"signerSha256\":\"$validSigner\""

    private fun validAsset(): ReleaseAsset = ReleaseAsset(
        id = 7L,
        name = "Musxi-Player-0.2.0.apk",
        bytes = 27_000_000L,
        url = assetUrl,
    )

    private companion object {
        const val tag = "v0.2.0"
        const val releasePage = "https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.2.0"
        const val assetUrl = "https://github.com/qwt1011/Musxi-Player-Android/releases/download/v0.2.0/Musxi-Player-0.2.0.apk"
        const val validSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val validSigner = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val asset: ReleaseAsset
            get() = ReleaseAsset(
                id = 7L,
                name = "Musxi-Player-0.2.0.apk",
                bytes = 27_000_000L,
                url = assetUrl,
            )
    }
}
