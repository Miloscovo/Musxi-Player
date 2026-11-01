package tech.musxi.neteaseloginpoc;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseMembershipTest {
    @Test public void positiveExpiryDoesNotRequireOtherOptionalProducts() throws Exception {
        NeteaseApi.Membership member = NeteaseApi.membershipFromResponse(new JSONObject(
            "{\"data\":{\"associator\":{\"expireTime\":4102444800000}}}"));
        assertEquals(1, member.isVip);
        assertEquals(Long.valueOf(4102444800000L), member.expiresAtEpochMs);
    }

    @Test public void missingProductNeverMeansNonMember() throws Exception {
        assertEquals(-1, NeteaseApi.membershipFromResponse(new JSONObject()).isVip);
        assertEquals(-1, NeteaseApi.membershipFromResponse(new JSONObject(
            "{\"associator\":{\"expireTime\":0}}" )).isVip);
        assertEquals(0, NeteaseApi.membershipFromResponse(new JSONObject(
            "{\"redplus\":{\"expireTime\":0},\"associator\":{\"expireTime\":0},\"musicPackage\":{\"expireTime\":0}}" )).isVip);
    }
}
