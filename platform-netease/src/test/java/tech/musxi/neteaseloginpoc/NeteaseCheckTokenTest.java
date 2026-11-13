package tech.musxi.neteaseloginpoc;

import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseCheckTokenTest {
    @Test public void missingCompatibleV2ProviderRequiresVerification() {
        NeteaseApi.ApiException failure = assertThrows(NeteaseApi.ApiException.class,
            new NeteaseCheckToken()::freshV2);
        assertEquals("verification_required", failure.code);
        assertEquals("网易云歌单收藏需要兼容的平台验证，当前暂不可用", failure.getMessage());
        assertNull(failure.getCause());
    }
}
