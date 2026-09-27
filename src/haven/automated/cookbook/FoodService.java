package haven.automated.cookbook;

import haven.*;
import haven.res.ui.tt.q.qbuff.QBuff;
import haven.resutil.FoodInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Contributes food data to the public cookbook at cookbook.kittenrider.com.
 *
 * The warehouse only accepts its v2 format: raw, unrounded values exactly as the game sent them,
 * posted with a contributor token. Public (non Kitten Rider) clients get that token from
 * POST /register, which needs no credentials. The token is kept in the cookBookToken pref and
 * reused across sessions, as the warehouse asks.
 */
public class FoodService {
    public static final String API = "https://api.kittenrider.com/cookbook";
    public static final String ENABLED_PREF = "cookBookContribute";
    public static final String TOKEN_PREF = "cookBookToken";

    private static final Map<String, Boolean> cachedItems = new ConcurrentHashMap<>();
    private static final Queue<Observation> sendQueue = new ConcurrentLinkedQueue<>();
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private static final boolean cookbookDebug = Boolean.getBoolean("haven.cookbookdebug");

    /* Warehouse limits: 200 observations per request. The queue is bounded so a long outage cannot
     * grow it forever. */
    private static final int MAX_BATCH = 200;
    private static final int MAX_QUEUE = 500;
    private static final int MAX_EXTRA_FIELDS = 12;
    private static final int MAX_EXTRA_LEN = 128;
    /* How many refused tokens a session may replace before it stops trying. */
    private static final int MAX_REREGISTER = 3;
    private static int reregistered = 0;
    private static volatile boolean refused = false;
    private static long retryAfter = 0;

    static {
        scheduler.scheduleAtFixedRate(FoodService::sendItems, 10L, 10, TimeUnit.SECONDS);
    }

    public static boolean isEnabled() {
        return Utils.getprefb(ENABLED_PREF, true);
    }

    private static class Observation {
        String res, name, world;
        double quality = 10.0;
        double end, glut, cons, sev;
        final JSONArray feps = new JSONArray();
        final JSONArray ingredients = new JSONArray();
        final List<String> tips = new ArrayList<>();
        final JSONArray effects = new JSONArray();
        final JSONArray types = new JSONArray();
        final JSONArray extra = new JSONArray();

        JSONObject toJson() {
            JSONObject j = new JSONObject();
            j.put("res", res);
            j.put("name", name);
            j.put("quality", quality);
            JSONObject food = new JSONObject();
            food.put("end", end);
            food.put("glut", glut);
            food.put("cons", cons);
            food.put("sev", sev);
            j.put("food", food);
            j.put("feps", feps);
            j.put("ingredients", ingredients);
            JSONArray tipList = new JSONArray();
            for (String t : tips) tipList.put(t);
            j.put("tips", tipList);
            j.put("effects", effects);
            j.put("types", types);
            j.put("extra", extra);
            return j;
        }
    }

    public static void checkFood(List<ItemInfo> ii, Resource res, String genus) {
        /* Food values belong to a world; a sighting without one cannot be filed and is refused. */
        if ((genus == null) || genus.trim().isEmpty())
            return;
        List<ItemInfo> infoList = new ArrayList<>(ii);
        Defer.later(() -> {
            try {
                FoodInfo foodInfo = ItemInfo.find(FoodInfo.class, infoList);
                if (foodInfo == null)
                    return (null);

                Observation o = new Observation();
                o.res = res.name;
                o.world = genus.trim();
                QBuff qBuff = ItemInfo.find(QBuff.class, infoList);
                o.quality = (qBuff != null) ? qBuff.q : 10.0;
                o.end = foodInfo.end;
                o.glut = foodInfo.glut;
                o.cons = foodInfo.cons;
                o.sev = foodInfo.sev;

                for (FoodInfo.Event ev : foodInfo.evs) {
                    JSONObject f = new JSONObject();
                    /* Keyed on the resource: the display name is localised. */
                    String evres = "";
                    try {
                        evres = ev.ev.getres().name;
                    } catch (Exception ignored) {
                    }
                    f.put("res", evres);
                    f.put("name", ev.ev.nm);
                    if (ev.ev.col != null)
                        f.put("col", String.format("#%02X%02X%02X", ev.ev.col.getRed(), ev.ev.col.getGreen(), ev.ev.col.getBlue()));
                    f.put("a", ev.a);
                    o.feps.put(f);
                }
                for (FoodInfo.Effect ef : foodInfo.efs) {
                    JSONObject e = new JSONObject();
                    e.put("name", effectName(ef));
                    e.put("p", ef.p);
                    o.effects.put(e);
                }
                if (foodInfo.types != null) {
                    for (int t : foodInfo.types) o.types.put(t);
                }

                for (ItemInfo info : infoList) {
                    if (info instanceof ItemInfo.Name) {
                        o.name = ((ItemInfo.Name) info).original;
                    } else if (info instanceof ItemInfo.AdHoc) {
                        /* Verbatim; the warehouse decides which lines are preparation (truffles,
                         * pepper, ...). */
                        o.tips.add(((ItemInfo.AdHoc) info).str.text);
                    } else if (info.getClass().getName().contains("Ingredient")
                            || info.getClass().getName().contains("Smoke")) {
                        String name = (String) info.getClass().getField("name").get(info);
                        Double value = (Double) info.getClass().getField("val").get(info);
                        if ((name != null) && (value != null)) {
                            JSONObject c = new JSONObject();
                            c.put("name", name);
                            c.put("val", value);
                            o.ingredients.put(c);
                        }
                    } else if (!(info instanceof FoodInfo) && !(info instanceof QBuff)
                            && !(info instanceof ItemInfo.Pagina) && !(info instanceof ItemInfo.ResourceName)) {
                        JSONObject u = describe(info);
                        if (u != null) o.extra.put(u);
                    }
                }

                if ((o.name != null) && (o.feps.length() > 0))
                    queue(o);
            } catch (Exception exception) {
                if (cookbookDebug)
                    System.out.println("[Cookbook] cannot read food info: " + exception);
            }
            return (null);
        });
    }

    /* An ItemInfo we do not recognise, reported by class name and its simple public fields. */
    private static JSONObject describe(ItemInfo info) {
        try {
            JSONObject fields = new JSONObject();
            for (Field f : info.getClass().getFields()) {
                if (fields.length() >= MAX_EXTRA_FIELDS) break;
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v;
                try {
                    v = f.get(info);
                } catch (Exception ignored) {
                    continue;
                }
                if (v == null) continue;
                if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof Text))
                    continue;
                String s = (v instanceof Text) ? ((Text) v).text : String.valueOf(v);
                if ((s == null) || s.isEmpty()) continue;
                fields.put(f.getName(), (s.length() > MAX_EXTRA_LEN) ? s.substring(0, MAX_EXTRA_LEN) : s);
            }
            JSONObject u = new JSONObject();
            u.put("cls", info.getClass().getName());
            u.put("fields", fields);
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static String effectName(FoodInfo.Effect ef) {
        if (ef.info != null) {
            for (ItemInfo i : ef.info) {
                if (i instanceof ItemInfo.Name) return ((ItemInfo.Name) i).original;
                if (i instanceof ItemInfo.AdHoc) return ((ItemInfo.AdHoc) i).str.text;
            }
        }
        return "";
    }

    /* Deduplicated on everything that can differ between two sightings, quality and FEP values
     * included, as the warehouse asks. */
    private static void queue(Observation o) {
        String key = hash(o);
        if ((key == null) || (cachedItems.putIfAbsent(key, Boolean.TRUE) != null))
            return;
        if (sendQueue.size() < MAX_QUEUE)
            sendQueue.add(o);
    }

    private static String hash(Observation o) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(o.world).append(';').append(o.name).append(';').append(o.res).append(';').append(o.quality).append(';');
            sb.append(o.ingredients).append(';').append(o.tips).append(';').append(o.feps).append(';');
            sb.append(o.end).append(':').append(o.glut).append(':').append(o.cons).append(':').append(o.sev);
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return new BigInteger(1, digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8))).toString(16);
        } catch (Exception e) {
            return null;
        }
    }

    private static String token() {
        String token = Utils.getpref(TOKEN_PREF, "");
        return (token == null) ? "" : token.trim();
    }

    private static void setToken(String token) {
        Utils.setpref(TOKEN_PREF, token);
        TextEntry entry = OptWnd.cookBookTokenTextEntry;
        if (entry != null)
            entry.settext(token);
    }

    /* Mints an open contributor token. No credentials needed. */
    private static String register() {
        HttpURLConnection connection = null;
        try {
            connection = post(API + "/register", "{}", null);
            int code = connection.getResponseCode();
            if (code != 200) {
                if (cookbookDebug)
                    System.out.println("[Cookbook] register failed: HTTP " + code + " " + read(connection.getErrorStream()));
                return null;
            }
            String token = new JSONObject(read(connection.getInputStream())).optString("token", "");
            if (token.isEmpty())
                return null;
            setToken(token);
            if (cookbookDebug)
                System.out.println("[Cookbook] registered a contributor token");
            return token;
        } catch (Exception e) {
            if (cookbookDebug)
                System.out.println("[Cookbook] register failed: " + e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void sendItems() {
        try {
            if (refused || !isEnabled() || sendQueue.isEmpty())
                return;
            if (System.currentTimeMillis() < retryAfter)
                return;

            String token = token();
            if (token.isEmpty() && ((token = register()) == null))
                return;

            /* One request per world; a batch carries a single world in its envelope. */
            String world = sendQueue.peek().world;
            List<Observation> toSend = new ArrayList<>();
            for (Observation o : sendQueue) {
                if (toSend.size() >= MAX_BATCH) break;
                if (o.world.equals(world)) toSend.add(o);
            }
            if (toSend.isEmpty())
                return;

            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("world", world);
            JSONObject client = new JSONObject();
            client.put("name", "Apricot");
            client.put("version", Config.clientVersion);
            root.put("client", client);
            JSONArray list = new JSONArray();
            for (Observation o : toSend) list.put(o.toJson());
            root.put("observations", list);

            HttpURLConnection connection = null;
            try {
                connection = post(API + "/v2/observe", root.toString(), token);
                int code = connection.getResponseCode();
                if (cookbookDebug) {
                    String reply = read((code == 200) ? connection.getInputStream() : connection.getErrorStream());
                    System.out.println("[Cookbook] HTTP " + code + " (" + toSend.size() + " observations) " + reply);
                }
                if ((code == 200) || (code == 400) || (code == 413)) {
                    /* Accepted, or refused on content: resending the same batch cannot help. */
                    sendQueue.removeAll(toSend);
                } else if (code == 403) {
                    /* Token unknown or revoked (e.g. an old token from another cookbook server).
                     * Throw it away and register a fresh one; the batch stays queued. */
                    if (reregistered++ < MAX_REREGISTER) {
                        setToken("");
                    } else {
                        refused = true;
                        System.out.println("[Cookbook] the cookbook keeps refusing this client's token; pausing contributions for this session");
                    }
                } else if (code == 429) {
                    int wait = 600;
                    try {
                        wait = new JSONObject(read(connection.getErrorStream())).optInt("retryAfterSeconds", wait);
                    } catch (Exception ignored) {
                    }
                    retryAfter = System.currentTimeMillis() + (wait * 1000L);
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        } catch (Exception ex) {
            if (cookbookDebug)
                System.out.println("[Cookbook] failed to send: " + ex);
        }
    }

    private static HttpURLConnection post(String url, String body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "Apricot Client " + Config.clientVersion);
        if (token != null)
            connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        try (InputStream s = in) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
