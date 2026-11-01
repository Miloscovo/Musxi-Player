package tech.musxi.loginpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/** Offline coverage for the optional Kugou avatar field. */
public final class AvatarTest {
    private static final KugouApi.Session BASE =
            new KugouApi.Session("fixture-token", "123", "Fixture");

    @Test public void verifyAcceptsOptionalStringAvatarAndPreservesRawValue() throws Exception {
        KugouApi.Session session = KugouApi.parseVerify(ok(
                "{\"userid\":123,\"nickname\":\"Fixture\",\"pic\":\"fixture-avatar\"}"), BASE);

        assertEquals("fixture-avatar", session.avatarUrl);
        assertEquals(true, session.accountIdEchoed);
    }

    @Test public void verifyTreatsMissingBlankOversizedAndNonStringAvatarAsAbsent()
            throws Exception {
        for (String field : new String[] {
                "", ",\"pic\":\"   \"", ",\"pic\":42", ",\"pic\":{}",
                ",\"pic\":\"" + "x".repeat(2049) + "\""
        }) {
            KugouApi.Session session = KugouApi.parseVerify(ok(
                    "{\"userid\":123,\"nickname\":\"Fixture\"" + field + "}"), BASE);

            assertNull(session.avatarUrl);
        }
    }

    @Test public void oldStoreRecordWithoutAvatarStillLoads() throws Exception {
        JSONObject old = new JSONObject()
                .put("version", 1)
                .put("token", "fixture-token")
                .put("userId", "123")
                .put("nickname", "Fixture");

        KugouApi.Session session = SessionStore.decodeSession(old);

        assertNull(session.avatarUrl);
    }

    @Test public void optionalAvatarFailureRetainsExistingPictureAndTrimsValidPicture() throws Exception {
        KugouApi.Session existing = new KugouApi.Session("fixture-token", "123", "Fixture", "old-picture");
        assertEquals("old-picture", KugouApi.parseVerify(ok(
                "{\"userid\":123,\"nickname\":\"Fixture\",\"pic\":false}"), existing).avatarUrl);
        assertEquals("new-picture", KugouApi.parseVerify(ok(
                "{\"userid\":123,\"nickname\":\"Fixture\",\"pic\":\" new-picture \"}"), existing).avatarUrl);
        assertNull(new KugouApi.Session("fixture-token", "123", "Fixture", "bad\u0001picture").avatarUrl);
    }

    @Test public void encryptedPayloadCodecRoundTripsAvatar() throws Exception {
        KugouApi.Session saved =
                new KugouApi.Session("fixture-token", "123", "Fixture", "fixture-avatar");

        JSONObject encoded = SessionStore.encodeSession(saved);
        KugouApi.Session loaded = SessionStore.decodeSession(encoded);

        assertEquals("fixture-avatar", loaded.avatarUrl);
    }

    @Test public void invalidStoredAvatarFallsBackWithoutInvalidatingSession()
            throws Exception {
        for (Object invalid : new Object[] {
                JSONObject.NULL, Integer.valueOf(42), "", "   ", "x".repeat(2049)
        }) {
            JSONObject stored = new JSONObject()
                    .put("version", 1)
                    .put("token", "fixture-token")
                    .put("userId", "123")
                    .put("nickname", "Fixture")
                    .put("avatarUrl", invalid);

            KugouApi.Session session = SessionStore.decodeSession(stored);

            assertNull(session.avatarUrl);
        }
    }

    private static JSONObject ok(String data) throws Exception {
        return new JSONObject("{\"status\":1,\"data\":" + data + "}");
    }
}
