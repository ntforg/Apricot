package haven.error;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/*
 * Sends crash reports to a Discord webhook: a summary embed with the
 * top of the stack trace, plus the full report attached as a text
 * file. Duplicate traces are only sent once per session, and at most
 * MAXREPORTS reports are sent per session, so a crash loop cannot
 * flood the channel.
 */
public class DiscordReporter {
    private static final int MAXREPORTS = 5;
    private static final int EMBEDTRACE = 3000;
    private static final Set<Integer> seen = new HashSet<>();
    private static int sent = 0;

    public static void send(URL webhook, Report r) throws IOException {
	String trace = trace(r.t);
	synchronized(DiscordReporter.class) {
	    if(!seen.add(trace.hashCode()) || (sent >= MAXREPORTS))
		return;
	    sent++;
	}
	String version = str(r.props.get("jar.config")) + " " + haven.Config.clientVersion;
	String title = r.t.getClass().getName() + (r.t.getMessage() != null ? ": " + r.t.getMessage() : "");
	if(title.length() > 250)
	    title = title.substring(0, 250) + "…";
	String shorttrace = trace.length() > EMBEDTRACE ? trace.substring(0, EMBEDTRACE) + "\n… (full trace attached)" : trace;

	StringBuilder json = new StringBuilder();
	json.append("{\"embeds\":[{");
	json.append("\"title\":").append(quote(title)).append(',');
	json.append("\"description\":").append(quote("```\n" + shorttrace + "\n```")).append(',');
	json.append("\"color\":15548997,");
	json.append("\"fields\":[");
	json.append(field("Version", version)).append(',');
	json.append(field("OS", str(r.props.get("os.name")) + " " + str(r.props.get("os.version")) + " (" + str(r.props.get("os.arch")) + ")")).append(',');
	json.append(field("Java", str(r.props.get("java.version")) + " (" + str(r.props.get("java.vendor")) + ")")).append(',');
	json.append(field("Thread", str(r.props.get("thnm"))));
	json.append("]}]}");

	String boundary = "----thunder" + Long.toHexString(System.nanoTime());
	HttpURLConnection c = (HttpURLConnection)webhook.openConnection();
	c.setConnectTimeout(10000);
	c.setReadTimeout(10000);
	c.setDoOutput(true);
	c.setRequestMethod("POST");
	c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
	try(OutputStream out = c.getOutputStream()) {
	    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
	    w.write("--" + boundary + "\r\n");
	    w.write("Content-Disposition: form-data; name=\"payload_json\"\r\n");
	    w.write("Content-Type: application/json\r\n\r\n");
	    w.write(json.toString());
	    w.write("\r\n--" + boundary + "\r\n");
	    w.write("Content-Disposition: form-data; name=\"files[0]\"; filename=\"thunder-crash-" + r.time + ".txt\"\r\n");
	    w.write("Content-Type: text/plain\r\n\r\n");
	    w.write(fullreport(r, trace));
	    w.write("\r\n--" + boundary + "--\r\n");
	    w.flush();
	}
	int code = c.getResponseCode();
	if((code < 200) || (code >= 300))
	    throw(new IOException("Discord webhook returned HTTP " + code));
	c.getInputStream().close();
    }

    private static String fullreport(Report r, String trace) {
	StringBuilder buf = new StringBuilder();
	buf.append("Thunder crash report — ").append(new Date(r.time)).append('\n');
	buf.append('\n');
	List<String> keys = new ArrayList<>();
	for(Object k : r.props.keySet())
	    keys.add(String.valueOf(k));
	Collections.sort(keys);
	for(String k : keys)
	    buf.append(k).append(" = ").append(str(r.props.get(k))).append('\n');
	buf.append('\n').append(trace);
	return(buf.toString());
    }

    private static String trace(Throwable t) {
	StringWriter buf = new StringWriter();
	t.printStackTrace(new PrintWriter(buf));
	return(buf.toString().replace("\r\n", "\n"));
    }

    private static String str(Object o) {
	return((o == null) ? "?" : String.valueOf(o));
    }

    private static String field(String name, String value) {
	return("{\"name\":" + quote(name) + ",\"value\":" + quote(value) + ",\"inline\":true}");
    }

    private static String quote(String s) {
	StringBuilder buf = new StringBuilder("\"");
	for(int i = 0; i < s.length(); i++) {
	    char ch = s.charAt(i);
	    switch(ch) {
	    case '"':  buf.append("\\\""); break;
	    case '\\': buf.append("\\\\"); break;
	    case '\n': buf.append("\\n"); break;
	    case '\r': buf.append("\\r"); break;
	    case '\t': buf.append("\\t"); break;
	    default:
		if(ch < 0x20)
		    buf.append(String.format("\\u%04x", (int)ch));
		else
		    buf.append(ch);
	    }
	}
	return(buf.append('"').toString());
    }
}
