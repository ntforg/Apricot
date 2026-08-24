/*
 *  This file is part of the Apricot client.
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 */

package haven;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONObject;

/**
 * Client-wide string translation.
 *
 * <p>Every translatable string is looked up in a {@link Bundle}, keyed by the
 * original English text (or, for resource-derived strings, by the resource
 * name). A miss returns the original text unchanged, so an incomplete
 * translation degrades to English rather than to blanks.
 *
 * <p>Dictionaries are plain JSON objects loaded from two layers, the later
 * winning:
 * <ol>
 *   <li>{@code /l10n/<lang>/<bundle>.json} inside the client jar -- the
 *       official translations, replaced wholesale by client updates.</li>
 *   <li>{@code <gameDir>/Translations/<lang>/<bundle>.json} on disk -- player
 *       corrections and additions. The updater never writes there, so these
 *       survive updates.</li>
 * </ol>
 *
 * <p>A language exists as soon as a directory for it exists in either layer;
 * removing the directory removes it from the menu. Nothing else needs
 * changing to add or drop a language.
 */
public class L10N {
    public static final String DEFAULT_LANGUAGE = "en";
    private static final String USERDIR = "Translations";
    /* Missed strings are batched rather than written per-miss; the client
     * hits this path thousands of times while a session warms up. */
    private static final long DUMPDELAY = 3000;
    /* Keeps a runaway regex bundle from growing the memo without bound. */
    private static final int MEMOCAP = 8192;

    /** How a bundle's keys are matched against the text being translated. */
    public enum Mode {
	/** The key is the whole string, matched exactly. */
	SIMPLE,
	/** The key may be a regex with capture groups. */
	MATCH,
	/** The key is a phrase replaced wherever it appears inside the text. */
	PHRASE;
    }

    /** Which dictionary a string is looked up in. Each maps to one JSON file
     *  per language, so translators can work on one kind of text at a time. */
    public enum Bundle {
	/** Push-button captions. */
	BUTTON("button"),
	/** Static text: labels, headings, column titles. Pattern-matched. */
	LABEL("label", Mode.MATCH),
	/** Window titles. Pattern-matched. */
	WINDOW("window", Mode.MATCH),
	/** Stat lines inside item tooltips -- "Damage", "Armor penetration".
	 *  Phrase-replaced, since the numbers around them vary. */
	ITEMTIP("itemtip", Mode.PHRASE),
	/** Item and object names, and hover tooltips. */
	TOOLTIP("tooltip"),
	/** Menu-grid actions, keyed by resource name. */
	ACTION("action"),
	/** Item descriptions ("pagina" layers), keyed by resource name. */
	PAGINA("pagina"),
	/** Petals of the right-click flower menu. Pattern-matched. */
	FLOWER("flower", Mode.MATCH),
	/** Ingredient names appearing inside crafted-item tooltips. */
	INGREDIENT("ingredient"),
	/** Minimap biome names. */
	BIOME("biome"),
	/** System and login messages. */
	MSG("msg");

	public final String name;
	public final Mode mode;

	Bundle(String name, Mode mode) {
	    this.name = name;
	    this.mode = mode;
	}

	Bundle(String name) {
	    this(name, Mode.SIMPLE);
	}
    }

    /** A regex key and the format string it translates to. */
    private static class Rule {
	final Pattern pat;
	final String fmt;

	Rule(Pattern pat, String fmt) {
	    this.pat = pat;
	    this.fmt = fmt;
	}
    }

    /** One language's dictionary for one bundle. */
    private static class Dict {
	final Map<String, String> literal = new HashMap<>();
	/* Only keys that actually look like regexes end up here, so the
	 * linear scan stays short even for a big bundle. */
	final List<Rule> rules = new ArrayList<>();
	/* Phrase bundles only: keys longest first, so that a translation of
	 * "Demolition Damage" is applied before one of "Damage". */
	final List<Map.Entry<String, String>> phrases = new ArrayList<>();
	final Map<String, String> memo = new HashMap<>();
    }

    /** Regex metacharacters that make a key worth compiling as a pattern. */
    private static final Pattern METACHARS = Pattern.compile("[\\\\\\[\\]{}()*+?^$|.]");
    /** {@code @1} -- substitute capture group 1 verbatim. */
    private static final String SUB_DIRECT = "(?<!\\\\)@%d";
    /** {@code $1} -- substitute capture group 1, itself run through INGREDIENT. */
    private static final String SUB_TRANSLATED = "(?<!\\\\)\\$%d";
    /** Group text worth trying to translate: words, not numbers or symbols. */
    private static final Pattern TRANSLATABLE = Pattern.compile("[\\p{L}][\\p{L}\\s'-]*");
    /* Labels carry a lot of pure data -- quantities, coordinates, timers.
     * None of it is translatable, and letting it through would bury a
     * translator's missing-string dump under thousands of numbers. */
    private static final Pattern HASLETTER = Pattern.compile("\\p{L}");

    private static final Map<Bundle, Dict> dicts = new EnumMap<>(Bundle.class);
    private static final Map<Bundle, Map<String, String>> missing = new EnumMap<>(Bundle.class);
    private static final Object dumplock = new Object();
    private static boolean dumppending = false;

    /** Every language with a dictionary directory, "en" first. */
    public static List<String> languages = Collections.singletonList(DEFAULT_LANGUAGE);
    /** Human-readable name per language code, for the settings menu. */
    private static Map<String, String> langnames = new HashMap<>();

    private static volatile String basedir = "";
    private static volatile String language = DEFAULT_LANGUAGE;
    private static volatile boolean deflang = true;
    private static volatile boolean debug = false;

    static {
	try {
	    language = Utils.getpref("language", DEFAULT_LANGUAGE);
	    debug = Utils.getprefb("languageDebug", false);
	} catch(Exception e) {
	    new Warning(e, "could not read language preference").issue();
	}
	scan();
	load();
    }

    /** Currently selected language code. */
    public static String language() {
	return(language);
    }

    /** True when no translation is in effect and lookups are pass-through. */
    public static boolean isDefaultLanguage() {
	return(deflang);
    }

    /** Whether untranslated strings are being recorded to disk. */
    public static boolean debug() {
	return(debug);
    }

    public static void debug(boolean on) {
	debug = on;
	Utils.setprefb("languageDebug", on);
    }

    /** Display name for a language code, e.g. "Russian (Русский)". */
    public static String langname(String lang) {
	String nm = langnames.get(lang);
	if(nm != null)
	    return(nm);
	try {
	    Locale loc = Locale.forLanguageTag(lang);
	    String eng = loc.getDisplayName(Locale.ENGLISH);
	    String own = loc.getDisplayName(loc);
	    if(!eng.isEmpty() && !eng.equals(lang))
		return(eng.equals(own) ? eng : String.format("%s (%s)", eng, own));
	} catch(Exception e) {
	    /* Fall through to the bare code. */
	}
	return(lang);
    }

    /**
     * Switch language and reload every dictionary. Widgets that have already
     * rendered their text keep it, so callers should tell the player to
     * restart for the change to reach the whole UI.
     */
    public static void language(String lang) {
	if(lang == null)
	    lang = DEFAULT_LANGUAGE;
	language = lang;
	Utils.setpref("language", lang);
	reload();
    }

    /** Re-read every dictionary, picking up edits made while the client runs. */
    public static void reload() {
	scan();
	load();
    }

    /* ---- lookups ------------------------------------------------------ */

    public static String button(String text) {
	return(get(Bundle.BUTTON, text, text));
    }

    public static String label(String text) {
	return(get(Bundle.LABEL, text, text));
    }

    public static String window(String text) {
	return(get(Bundle.WINDOW, text, text));
    }

    public static String tooltip(String text) {
	return(get(Bundle.TOOLTIP, text, text));
    }

    public static String flower(String text) {
	return(get(Bundle.FLOWER, text, text));
    }

    public static String ingredient(String text) {
	return(get(Bundle.INGREDIENT, text, text));
    }

    public static String biome(String text) {
	return(get(Bundle.BIOME, text, text));
    }

    public static String msg(String text) {
	return(get(Bundle.MSG, text, text));
    }

    /**
     * Translate the English phrases inside an assembled item-tooltip line.
     *
     * <p>Lines like {@code $col[128,128,255]{Armor penetration}: 20.0%} are
     * put together by server-supplied code, with the numbers and the markup
     * already baked in, so there is no whole string to look up. Each phrase
     * in {@code itemtip.json} is instead replaced wherever it appears.
     */
    public static String tipline(String text) {
	if(deflang || (text == null) || text.isEmpty())
	    return(text);
	Dict dict;
	synchronized(dicts) {
	    dict = dicts.get(Bundle.ITEMTIP);
	}
	if(dict == null)
	    return(text);
	if(dict.phrases.isEmpty()) {
	    if(debug)
		report(Bundle.ITEMTIP, generalize(text), text);
	    return(text);
	}
	synchronized(dict.memo) {
	    String hit = dict.memo.get(text);
	    if(hit != null)
		return(hit);
	}
	String ret = text;
	for(Map.Entry<String, String> phrase : dict.phrases) {
	    if(ret.contains(phrase.getKey()))
		ret = ret.replace(phrase.getKey(), phrase.getValue());
	}
	if(debug && ret.equals(text))
	    report(Bundle.ITEMTIP, generalize(text), text);
	synchronized(dict.memo) {
	    if(dict.memo.size() >= MEMOCAP)
		dict.memo.clear();
	    dict.memo.put(text, ret);
	}
	return(ret);
    }

    /* One stat line differs from the next only in its numbers, so the digits
     * are collapsed before reporting; otherwise every quality of every item
     * would be its own entry in the translator's dump. */
    private static String generalize(String text) {
	return(text.replaceAll("[\\d]+([.,][\\d]+)?", "#"));
    }

    /** A translated rich-text hover tooltip, wrapped to {@code width}. */
    public static RichText richtip(String text, int width, Object... extra) {
	return(RichText.render(tooltip(text), width, extra));
    }

    /** A translated plain-text hover tooltip. */
    public static Text richtip(String text) {
	return(Text.render(tooltip(text)));
    }

    /** Resource-keyed tooltip; falls back to the ACTION bundle, then to {@code def}. */
    public static String tooltip(String resnm, String def) {
	return(get(Bundle.TOOLTIP, resnm, def));
    }

    public static String action(String resnm, String def) {
	return(get(Bundle.ACTION, resnm, def));
    }

    public static String pagina(String resnm, String def) {
	return(get(Bundle.PAGINA, resnm, def));
    }

    /* ---- machinery ---------------------------------------------------- */

    private static String get(Bundle bundle, String key, String def) {
	if(deflang || (key == null) || key.isEmpty() || !HASLETTER.matcher(key).find())
	    return(def);
	Dict dict;
	synchronized(dicts) {
	    dict = dicts.get(bundle);
	}
	if(dict == null)
	    return(def);
	String ret = dict.literal.get(key);
	if((ret == null) && !dict.rules.isEmpty())
	    ret = rulematch(dict, key);
	if((ret == null) && (bundle == Bundle.TOOLTIP))
	    /* Menu actions and their hover tooltips share wording, so a
	     * tooltip miss is worth retrying against the action bundle. */
	    ret = get(Bundle.ACTION, key, null);
	/* Some captions are padded with spaces to reserve room for a longer
	 * one later -- "Options            " and the like. A translator
	 * cannot be expected to count those, so match on the trimmed text
	 * and hand the padding back afterwards. */
	int lead = 0, trail = key.length();
	while((lead < trail) && Character.isWhitespace(key.charAt(lead)))
	    lead++;
	while((trail > lead) && Character.isWhitespace(key.charAt(trail - 1)))
	    trail--;
	boolean padded = (lead > 0) || (trail < key.length());
	if((ret == null) && padded) {
	    String sub = get(bundle, key.substring(lead, trail), null);
	    if(sub != null)
		ret = key.substring(0, lead) + sub + key.substring(trail);
	}
	if(ret == null) {
	    if(debug && (def != null))
		/* Report the trimmed key: padding in a dictionary file is
		 * invisible and impossible to get right by hand. */
		report(bundle, key.substring(lead, trail), def.trim());
	    return(def);
	}
	return(ret);
    }

    private static String rulematch(Dict dict, String key) {
	synchronized(dict.memo) {
	    if(dict.memo.containsKey(key))
		return(dict.memo.get(key));
	}
	String ret = null;
	for(Rule rule : dict.rules) {
	    Matcher m = rule.pat.matcher(key);
	    if(!m.matches())
		continue;
	    ret = substitute(rule.fmt, m);
	    break;
	}
	synchronized(dict.memo) {
	    if(dict.memo.size() >= MEMOCAP)
		dict.memo.clear();
	    dict.memo.put(key, ret);
	}
	return(ret);
    }

    /* Fill @n and $n placeholders from the match. Groups are walked highest
     * first so that @10 is not eaten by the pattern for @1. */
    private static String substitute(String fmt, Matcher m) {
	String ret = fmt;
	for(int i = m.groupCount(); i >= 1; i--) {
	    String grp = m.group(i);
	    if(grp == null)
		grp = "";
	    ret = ret.replaceAll(String.format(SUB_DIRECT, i), Matcher.quoteReplacement(grp));
	    String xl = TRANSLATABLE.matcher(grp).matches() ? ingredient(grp) : grp;
	    ret = ret.replaceAll(String.format(SUB_TRANSLATED, i), Matcher.quoteReplacement(xl));
	}
	return(ret.replace("\\@", "@").replace("\\$", "$"));
    }

    /* ---- loading ------------------------------------------------------ */

    /**
     * Point the player-editable dictionaries at the client's data directory.
     * Called once the install location is known; until then the working
     * directory is used, which is where a non-Steam install has them anyway.
     *
     * <p>The directory is pushed in rather than read out of {@link Client} so
     * that loading a dictionary cannot drag the whole client into being.
     */
    public static void basedir(String dir) {
	String set = (dir == null) ? "" : dir;
	if(set.equals(basedir))
	    return;
	basedir = set;
	reload();
    }

    /** Base directory for player-editable dictionaries. */
    public static Path userdir() {
	return(Paths.get(basedir, USERDIR));
    }

    private static Path userdir(String lang) {
	return(userdir().resolve(lang));
    }

    /** Rebuild the language list from both layers. */
    private static void scan() {
	Set<String> langs = new LinkedHashSet<>();
	langs.add(DEFAULT_LANGUAGE);
	List<String> found = new ArrayList<>();
	found.addAll(jarlangs());
	found.addAll(fslangs());
	found.sort(String::compareTo);
	langs.addAll(found);
	languages = new ArrayList<>(langs);

	Map<String, String> names = new HashMap<>();
	names.put(DEFAULT_LANGUAGE, "English");
	for(String lang : languages) {
	    if(lang.equals(DEFAULT_LANGUAGE))
		continue;
	    JSONObject meta = merge(lang, "meta");
	    if(meta == null)
		continue;
	    String own = meta.optString("name", "");
	    String eng = meta.optString("english", "");
	    if(!own.isEmpty() && !eng.isEmpty() && !own.equals(eng))
		names.put(lang, String.format("%s (%s)", eng, own));
	    else if(!own.isEmpty())
		names.put(lang, own);
	    else if(!eng.isEmpty())
		names.put(lang, eng);
	}
	langnames = names;
    }

    private static List<String> jarlangs() {
	URL url = L10N.class.getResource("/l10n");
	if(url == null)
	    return(Collections.emptyList());
	try {
	    URI uri = url.toURI();
	    if("file".equals(uri.getScheme()))
		/* Running from build/classes rather than a packed jar. */
		return(dirnames(Paths.get(uri)));
	    /* Zip filesystems are per-jar and shared, so an already-open one
	     * must be reused rather than opened a second time. */
	    FileSystem fs;
	    boolean own = false;
	    try {
		fs = FileSystems.getFileSystem(uri);
	    } catch(FileSystemNotFoundException e) {
		fs = FileSystems.newFileSystem(uri, Collections.<String, Object>emptyMap());
		own = true;
	    }
	    try {
		return(dirnames(fs.getPath("/l10n")));
	    } finally {
		if(own)
		    fs.close();
	    }
	} catch(Exception e) {
	    return(Collections.emptyList());
	}
    }

    private static List<String> fslangs() {
	return(dirnames(userdir()));
    }

    private static List<String> dirnames(Path dir) {
	if((dir == null) || !Files.isDirectory(dir))
	    return(Collections.emptyList());
	try(Stream<Path> ls = Files.list(dir)) {
	    return(ls.filter(Files::isDirectory)
		   .map(p -> p.getFileName().toString())
		   .map(s -> s.endsWith("/") ? s.substring(0, s.length() - 1) : s)
		   /* "missing" holds a dump, and an underscore marks a
		    * directory that is not a language -- _template. */
		   .filter(s -> !s.equals("missing") && !s.startsWith("_"))
		   .collect(Collectors.toList()));
	} catch(Exception e) {
	    return(Collections.emptyList());
	}
    }

    private static void load() {
	String lang = language;
	boolean def = DEFAULT_LANGUAGE.equals(lang) || !languages.contains(lang);
	Map<Bundle, Dict> loaded = new EnumMap<>(Bundle.class);
	for(Bundle bundle : Bundle.values()) {
	    Dict dict = new Dict();
	    if(!def)
		fill(dict, bundle, merge(lang, bundle.name));
	    loaded.put(bundle, dict);
	}
	synchronized(dicts) {
	    dicts.clear();
	    dicts.putAll(loaded);
	}
	synchronized(missing) {
	    missing.clear();
	}
	deflang = def;
    }

    private static void fill(Dict dict, Bundle bundle, JSONObject json) {
	if(json == null)
	    return;
	for(String key : json.keySet()) {
	    String val = json.optString(key, null);
	    if((val == null) || val.isEmpty())
		continue;
	    if(bundle.mode == Mode.PHRASE) {
		dict.phrases.add(new AbstractMap.SimpleImmutableEntry<>(key, val));
		continue;
	    }
	    if((bundle.mode == Mode.MATCH) && METACHARS.matcher(key).find()) {
		try {
		    dict.rules.add(new Rule(Pattern.compile("^" + key + "$"), val));
		} catch(Exception e) {
		    /* Not a pattern at all, just literal text that happens to
		     * contain a bracket or a dollar sign -- "Chat
		     * ($col[255,255,0]{Ctrl+C})" and the like. The literal
		     * entry below still catches it. */
		}
	    }
	    /* Registered literally even in a pattern bundle. "Framerate limit
	     * (active window)" is a perfectly valid regex, but as one it
	     * matches the text without its parentheses -- that is, never the
	     * label it was written for. Since an exact match is tried before
	     * any pattern, this makes a key mean what it looks like it means,
	     * and leaves escaping to translators who actually want a regex. */
	    dict.literal.put(key, val);
	}
	dict.phrases.sort((a, b) -> b.getKey().length() - a.getKey().length());
    }

    /* Keys in pattern-matched bundles are read back as regexes, so literal
     * text has to come back out escaped. */
    private static String escape(Bundle bundle, String key) {
	if(bundle.mode != Mode.MATCH)
	    return(key);
	return(METACHARS.matcher(key).replaceAll("\\\\$0"));
    }

    /** Read a dictionary from the jar, then overlay the on-disk copy. */
    private static JSONObject merge(String lang, String name) {
	String rel = String.format("%s/%s.json", lang, name);
	JSONObject ret = parse(readjar("/l10n/" + rel));
	JSONObject fs = parse(readfs(userdir().resolve(rel)));
	if(fs != null) {
	    if(ret == null) {
		ret = fs;
	    } else {
		for(String key : fs.keySet())
		    ret.put(key, fs.get(key));
	    }
	}
	return(ret);
    }

    private static String readjar(String path) {
	try(InputStream in = L10N.class.getResourceAsStream(path)) {
	    if(in == null)
		return(null);
	    return(new String(Utils.readall(in), StandardCharsets.UTF_8));
	} catch(Exception e) {
	    return(null);
	}
    }

    private static String readfs(Path path) {
	try {
	    if(!Files.isRegularFile(path))
		return(null);
	    return(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
	} catch(Exception e) {
	    return(null);
	}
    }

    private static JSONObject parse(String json) {
	if((json == null) || json.trim().isEmpty())
	    return(null);
	try {
	    return(new JSONObject(json));
	} catch(Exception e) {
	    new Warning(e, "malformed translation file").issue();
	    return(null);
	}
    }

    /* ---- missing-string capture --------------------------------------- */

    /**
     * Record a string that had no translation. With debugging on these
     * accumulate in {@code Translations/<lang>/missing/<bundle>.json}, ready
     * to be filled in and merged into the file one directory up.
     */
    private static void report(Bundle bundle, String key, String def) {
	boolean start;
	synchronized(missing) {
	    Map<String, String> bm = missing.computeIfAbsent(bundle, b -> new TreeMap<>());
	    if(def.equals(bm.put(escape(bundle, key), def)))
		return;
	    start = !dumppending;
	    dumppending = true;
	}
	if(start)
	    dumplater();
    }

    private static void dumplater() {
	Thread th = new Thread(() -> {
		try {
		    Thread.sleep(DUMPDELAY);
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		    return;
		}
		dumpnow();
	    }, "translation-dump");
	th.setDaemon(true);
	th.start();
    }

    /* Written by hand rather than through JSONObject, whose backing map does
     * not keep insertion or sort order; a dump a translator has to work
     * through is much easier to read alphabetized. */
    private static String format(Map<String, String> map) {
	StringBuilder buf = new StringBuilder("{\n");
	int left = map.size();
	for(Map.Entry<String, String> ent : map.entrySet()) {
	    buf.append("    ").append(JSONObject.quote(ent.getKey()))
		.append(": ").append(JSONObject.quote(ent.getValue()));
	    if(--left > 0)
		buf.append(',');
	    buf.append('\n');
	}
	return(buf.append("}\n").toString());
    }

    /** Write every string seen untranslated so far. */
    public static void dumpnow() {
	Map<Bundle, Map<String, String>> snap = new EnumMap<>(Bundle.class);
	synchronized(missing) {
	    for(Map.Entry<Bundle, Map<String, String>> ent : missing.entrySet())
		snap.put(ent.getKey(), new TreeMap<>(ent.getValue()));
	    dumppending = false;
	}
	synchronized(dumplock) {
	    Path dir = userdir(language).resolve("missing");
	    try {
		Files.createDirectories(dir);
	    } catch(IOException e) {
		new Warning(e, "could not create " + dir).issue();
		return;
	    }
	    for(Map.Entry<Bundle, Map<String, String>> ent : snap.entrySet()) {
		if(ent.getValue().isEmpty())
		    continue;
		Path file = dir.resolve(ent.getKey().name + ".json");
		try {
		    Files.write(file, format(ent.getValue()).getBytes(StandardCharsets.UTF_8));
		} catch(Exception e) {
		    new Warning(e, "could not write " + file).issue();
		}
	    }
	}
    }
}
