package tech.musxi.loginpoc;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only real transport observer. Reports fixed structure/category values, never response text. */
public final class KugouAudioDiagnostic {
    public static JSONObject inspect(KugouApi api, KugouApi.Session session, String songId) throws Exception {
        KugouAuthorizedAudio original = api.authorizedAudio();
        Field transportField = KugouAuthorizedAudio.class.getDeclaredField("transport");
        Field deviceField = KugouAuthorizedAudio.class.getDeclaredField("device");
        transportField.setAccessible(true); deviceField.setAccessible(true);
        KugouAuthorizedAudio.Transport transport = (KugouAuthorizedAudio.Transport) transportField.get(original);
        Field basicField = KugouApi.class.getDeclaredField("transport"); basicField.setAccessible(true);
        Field basicDevice = KugouApi.class.getDeclaredField("device"); basicDevice.setAccessible(true);
        KugouApi.Transport basicTransport = (KugouApi.Transport) basicField.get(api);
        JSONArray results = new JSONArray();
        for (String quality : new String[]{"kugou:320", "kugou:flac", "kugou:high"}) {
            JSONObject row = new JSONObject().put("quality", quality);
            JSONArray stages = new JSONArray(); row.put("stages", stages); results.put(row);
            KugouApi basic = new KugouApi((KugouApi.Device)basicDevice.get(api), request -> {
                JSONObject body = basicTransport.execute(request);
                if (request.endpoint.endsWith("/v5/url")) {
                    JSONObject data = body.optJSONObject("data"); if (data == null) data = body;
                    try {
                        String echoed = data.optString("hash", "");
                        row.put("basicHashMatchesRequested", songId.substring(0, 32).equalsIgnoreCase(echoed))
                            .put("basicHashMatchesStandard", echoed.equalsIgnoreCase(data.optString("std_hash", "")))
                            .put("basicHashPresent", !echoed.isEmpty());
                    } catch (org.json.JSONException impossible) { throw new IOException("Diagnostic structure failure"); }
                }
                return body;
            });
            KugouAuthorizedAudio observed = new KugouAuthorizedAudio((KugouApi.Device) deviceField.get(original), request -> {
                String path = URI.create(request.endpoint).getPath();
                String stage = path.contains("get_res_privilege") ? "PRIVILEGE" : path.contains("user_verify")
                    ? "USER_VERIFY" : path.contains("authorization") ? "AUTHORIZATION" : "URL";
                JSONObject point = new JSONObject(); stages.put(point);
                try {
                    point.put("stage", stage);
                    JSONObject body = transport.execute(request);
                    Object data = body.opt("data");
                    point.put("dataType", type(data)).put("statusSuccess", body.optInt("status", -1) == 1)
                        .put("hasErrorCode", body.has("error_code"));
                    if (data instanceof JSONObject) {
                        JSONObject obj = (JSONObject) data;
                        point.put("validType", type(obj.opt("valid"))).put("authType", type(obj.opt("auth")))
                            .put("resourceArray", obj.opt("resource") instanceof JSONArray)
                            .put("resourcesArray", obj.opt("resources") instanceof JSONArray);
                    }
                    return body;
                } catch (IOException error) {
                    try { point.put("failure", category(error)); } catch (Exception ignored) { }
                    throw error;
                } catch (org.json.JSONException impossible) { throw new IOException("Diagnostic structure failure"); }
            });
            try {
                new KugouLibraryApi(basic, observed).audio(session, songId, android.os.SystemClock.elapsedRealtime(), quality, true);
                row.put("result", "SUCCESS");
            } catch (IOException error) {
                row.put("result", category(error));
                JSONArray suppressed = new JSONArray();
                for (Throwable cause : error.getSuppressed()) suppressed.put(category(cause));
                row.put("suppressedCategories", suppressed);
            }
            // Inspect the official authorization path independently when the basic endpoint
            // returns another quality's hash. This does not publish or play that response.
            if ("kugou:flac".equals(quality) || stages.length() == 0) {
                try {
                    KugouAuthorizedAudio.Resolved resolved = observed.resolve(session, songId, quality);
                    KugouLibraryApi.Audio payload = KugouLibraryApi.parseAuthorizedAudio(resolved.body, resolved.hash,
                        android.os.SystemClock.elapsedRealtime(), quality);
                    row.put("independentAuthorized", "PAYLOAD_CONFIRMED");
                    JSONArray probes = new JSONArray(); row.put("cdnProbes", probes);
                    java.util.List<String> candidates = new java.util.ArrayList<>(payload.candidates);
                    JSONObject source = resolved.body.optJSONObject("data"); if (source == null) source = resolved.body;
                    int officialHttp = 0;
                    for (String key : new String[]{"url", "backupUrl"}) {
                        Object values=source.opt(key);
                        JSONArray list=values instanceof JSONArray ? (JSONArray)values : new JSONArray().put(values);
                        for(int i=0;i<list.length();i++) {
                            URI target;
                            try { target=URI.create(list.optString(i)); } catch(Exception invalid) { continue; }
                            if("http".equals(target.getScheme()) && java.util.Arrays.asList("fs.youthandroid2.kugou.com",
                                "fs.youthandroid.kugou.com").contains(target.getHost()) && target.getPort()==-1 &&
                                target.getRawUserInfo()==null && target.getRawFragment()==null && target.getRawAuthority().equals(target.getHost())) {
                                officialHttp++; candidates.add(target.toString());
                            }
                        }
                    }
                    row.put("officialReturnedAllowlistedHttpCount",officialHttp);
                    for (String candidate : candidates) {
                        JSONObject probe = new JSONObject(); probes.put(probe);
                        java.net.HttpURLConnection connection = null;
                        try {
                            connection = (java.net.HttpURLConnection)new java.net.URL(candidate).openConnection();
                            probe.put("scheme",URI.create(candidate).getScheme());
                            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
                            connection.setRequestProperty("Range", "bytes=0-0");
                            probe.put("status", connection.getResponseCode());
                        } catch (IOException e) {
                            probe.put("transport", e instanceof javax.net.ssl.SSLPeerUnverifiedException ? "ssl_peer"
                                : e instanceof javax.net.ssl.SSLException ? "ssl" : e instanceof java.net.UnknownHostException ? "dns"
                                : e instanceof java.net.SocketTimeoutException ? "timeout" : "io");
                        } finally { if (connection != null) connection.disconnect(); }
                    }
                } catch (IOException error) { row.put("independentAuthorized", category(error)); }
            }
        }
        return new JSONObject().put("qualities", results);
    }
    private static String category(Throwable e) {
        String code = e instanceof KugouApi.ApiException ? ((KugouApi.ApiException)e).code
            : e instanceof java.net.SocketTimeoutException ? "timeout" : "network";
        return java.util.Arrays.asList("permission","unavailable","protocol","identity","risk","rate","expired",
            "http","network","timeout","preview","source_expired","endpoint").contains(code) ? code : "other";
    }
    private static String type(Object v) {
        return v == null || v == JSONObject.NULL ? "missing" : v instanceof String ? "string" : v instanceof Number
            ? "number" : v instanceof Boolean ? "boolean" : v instanceof JSONArray ? "array" : v instanceof JSONObject ? "object" : "other";
    }
}
