package tech.musxi.loginpoc;

import java.io.IOException;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed read-only schema probe. Reports counts/types only, never response values or credentials. */
public final class KugouPermissionReadProbe {
    private KugouPermissionReadProbe() {}
    public static JSONArray read(KugouApi api, KugouApi.Session session) throws IOException {
        JSONArray result = new JSONArray();
        for (int size : new int[] {100, 30}) {
            Object previousVersion = null;
            for (int page : new int[] {1, 2}) {
                String body = "{\"userid\":" + JSONObject.quote(session.userId) + ",\"token\":" +
                    JSONObject.quote(session.token) + ",\"total_ver\":979,\"type\":2,\"page\":" + page + ",\"pagesize\":" + size + "}";
                JSONObject root = api.libraryCall("/v7/get_all_list", session,
                    Collections.singletonMap("plat", "1"), body, false);
                JSONObject data = root.optJSONObject("data");
                try {
                    JSONObject row = new JSONObject().put("size", size).put("page", page)
                        .put("hasData", data != null);
                    if (data != null) {
                        Object version = data.opt("total_ver");
                        row.put("versionPresent", version instanceof Number)
                            .put("versionMatchesPrevious", page == 1 || (previousVersion != null && previousVersion.equals(version)));
                        previousVersion = version;
                        row.put("totalPresent", data.has("total")).put("countPresent", data.has("count"));
                        for (String field : new String[] {"info", "lists"}) {
                            Object value = data.opt(field);
                            row.put(field + "Type", value == null ? "missing" : value == JSONObject.NULL ? "null" :
                                value instanceof JSONArray ? "array" : "other");
                            if (value instanceof JSONArray) {
                                JSONArray values = (JSONArray)value;
                                row.put(field + "Length", values.length());
                                if (page == 1) {
                                    JSONArray flags = new JSONArray();
                                    for (int index = 0; index < values.length(); index++) {
                                        JSONObject item = values.optJSONObject(index);
                                        if (item == null) continue;
                                        flags.put(new JSONObject().put("ordinal", index)
                                            .put("editFlag", flag(item, "is_edit"))
                                            .put("defaultFlag", flag(item, "is_def"))
                                            .put("creatorPresent", item.has("list_create_userid"))
                                            .put("creatorMatchesSession", session.userId.equals(String.valueOf(item.opt("list_create_userid"))))
                                            .put("sourceListPresent", item.has("list_create_listid"))
                                            .put("sourceListMatchesSelf", String.valueOf(item.opt("listid")).equals(String.valueOf(item.opt("list_create_listid"))))
                                            .put("hasRenameMetadata", item.has("sort") && item.opt("tags") instanceof String && item.opt("intro") instanceof String));
                                    }
                                    row.put(field + "Flags", flags);
                                }
                            }
                        }
                    }
                    result.put(row);
                } catch (org.json.JSONException invalid) { throw new IOException("Probe serialization failed"); }
            }
        }
        return result;
    }
    private static String flag(JSONObject row, String field) {
        Object value = row.opt(field);
        if (value == null) return "missing";
        if (value == JSONObject.NULL) return "null";
        for (String known : new String[] {"0", "1", "2"}) {
            if (known.equals(String.valueOf(value))) return known;
        }
        return value instanceof Number ? "other_number" : value instanceof String ? "other_string" : "other_type";
    }
}
