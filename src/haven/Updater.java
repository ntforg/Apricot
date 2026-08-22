package haven;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.*;

/*
 * Self-updating for the packaged client. Downloading and unpacking a
 * new release happen inside the running client (see UpdateWindow); the
 * swap itself cannot, since this JVM holds hafen.jar open and Windows
 * will not let an open file be replaced. It is therefore handed to a
 * second JVM, started from a scratch copy of the new jar, which waits
 * for the client to exit, copies the staged files over the install and
 * starts the client again -- see main() below.
 *
 * Only installs that came from a release package and were started
 * through their own Play script update themselves. Steam keeps its own
 * copy current, and a client started any other way (a developer build,
 * the Haven launcher) is left alone.
 */
public class Updater {
    public static final String repo = "ntforg/Apricot";
    private static final String workdir = "update";
    private static final String prefname = "autoUpdate";
    private static Path install = null;
    private static boolean installck = false;
    private static boolean skipped = false;

    public static class Cancelled extends IOException {
	public Cancelled() {
	    super("update cancelled");
	}
    }

    public interface Progress {
	void status(String text, double frac);
	boolean cancelled();
    }

    /* The runtime-less package: the install already has a jre/ that an
     * update must not clobber, and it is a fraction of the size of the
     * packages that bundle one. */
    public static String asseturl(String tag) {
	return("https://github.com/" + repo + "/releases/download/" + tag + "/Apricot-" + tag + ".zip");
    }

    /* -Dhaven.autoupdate=false turns self-updating off for installs
     * that are kept current some other way. */
    public static boolean enabled() {
	return(!"false".equals(Utils.getprop("haven.autoupdate", "true")) && Utils.getprefb(prefname, true));
    }

    public static void enabled(boolean on) {
	Utils.setprefb(prefname, on);
    }

    /* Set when an update is turned down, so that logging out and back
     * to the login screen does not start the download over again. */
    public static synchronized boolean skipped() {
	return(skipped);
    }

    public static synchronized void skipped(boolean skipped) {
	Updater.skipped = skipped;
    }

    /* The install to update: the directory the running hafen.jar sits
     * in, provided it also holds the Play script the update will use to
     * start the client again. Anything else -- classes rather than a
     * jar, an install managed by the Haven launcher -- gives null. */
    public static synchronized Path install() {
	if(!installck) {
	    install = findinstall();
	    installck = true;
	}
	return(install);
    }

    private static Path findinstall() {
	try {
	    java.security.CodeSource src = Updater.class.getProtectionDomain().getCodeSource();
	    if(src == null)
		return(null);
	    Path jar = Paths.get(src.getLocation().toURI());
	    if(!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar"))
		return(null);
	    Path dir = jar.getParent();
	    if((dir == null) || !Files.isRegularFile(dir.resolve(launchscript())))
		return(null);
	    return(dir);
	} catch(URISyntaxException | RuntimeException e) {
	    return(null);
	}
    }

    private static String launchscript() {
	return(Config.windows ? "Play.bat" : "Play_Linux.sh");
    }

    /* Both Play scripts set this property, and nothing else does, so an
     * install started any other way keeps its hands off itself. Steam
     * installs in particular get their updates from Steam. */
    private static boolean ownlaunch() {
	return("false".equals(System.getProperty("runningThroughSteam")));
    }

    public static boolean possible() {
	Path dir = install();
	return(ownlaunch() && (dir != null) && Files.isWritable(dir));
    }

    private static Path work() throws IOException {
	return(Files.createDirectories(install().resolve(workdir)));
    }

    private static String mb(long bytes) {
	return(String.format("%.1f MB", bytes / 1048576.0));
    }

    /* Fetch the release package into the update directory. A package
     * that is already there is kept: only a complete download is moved
     * into place under its own name, so finding one means an earlier
     * attempt got that far -- after logging in mid-download, say. */
    public static Path download(String tag, Progress prog) throws IOException {
	Path work = work(), part = work.resolve("download.part"), zip = work.resolve(tag + ".zip");
	if(Files.isRegularFile(zip)) {
	    prog.status("Already downloaded", 1.0);
	    return(zip);
	}
	URL url = new URL(asseturl(tag));
	HttpURLConnection conn = (HttpURLConnection)url.openConnection();
	conn.setConnectTimeout(15000);
	conn.setReadTimeout(60000);
	conn.setRequestProperty("User-Agent", Config.confid + "/" + Config.clientVersion);
	try {
	    int code = conn.getResponseCode();
	    if(code != HttpURLConnection.HTTP_OK)
		throw(new IOException("server returned HTTP " + code));
	    long len = conn.getContentLengthLong(), got = 0;
	    try(InputStream in = conn.getInputStream();
		OutputStream out = Files.newOutputStream(part))
	    {
		byte[] buf = new byte[65536];
		for(int rv = in.read(buf); rv >= 0; rv = in.read(buf)) {
		    if(prog.cancelled())
			throw(new Cancelled());
		    out.write(buf, 0, rv);
		    got += rv;
		    prog.status("Downloading " + mb(got) + ((len > 0) ? (" / " + mb(len)) : ""),
				(len > 0) ? ((double)got / (double)len) : 0.0);
		}
	    }
	} finally {
	    conn.disconnect();
	}
	Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
	return(zip);
    }

    /* Unpack beside the install rather than into it, so that a failed
     * or cancelled update never leaves the install half-replaced. */
    public static Path unpack(Path zip, Progress prog) throws IOException {
	Path stage = work().resolve("staging");
	rmtree(stage);
	Files.createDirectories(stage);
	try(ZipFile zf = new ZipFile(zip.toFile())) {
	    int n = Math.max(zf.size(), 1), i = 0;
	    for(Enumeration<? extends ZipEntry> ents = zf.entries(); ents.hasMoreElements();) {
		ZipEntry ent = ents.nextElement();
		if(prog.cancelled())
		    throw(new Cancelled());
		Path dst = stage.resolve(ent.getName()).normalize();
		if(!dst.startsWith(stage))
		    throw(new IOException("refusing archive entry outside the update: " + ent.getName()));
		if(ent.isDirectory()) {
		    Files.createDirectories(dst);
		} else {
		    Files.createDirectories(dst.getParent());
		    try(InputStream in = zf.getInputStream(ent)) {
			Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
		    }
		    String name = dst.getFileName().toString();
		    if(name.endsWith(".sh") || name.equals("hafen.jar"))
			setexec(dst);
		}
		prog.status("Unpacking...", (double)(++i) / (double)n);
	    }
	}
	if(!Files.isRegularFile(stage.resolve("hafen.jar")))
	    throw(new IOException("update package contains no hafen.jar"));
	return(stage);
    }

    /* Hand the swap to a second JVM, running from a scratch copy of the
     * new jar so that nothing it needs lives in the directory it is
     * about to rewrite. The client must exit right after this returns. */
    public static void restart(String tag, Path stage) throws IOException {
	Path dir = install();
	Path jar = Files.createTempDirectory("apricot-update").resolve("apricot-update.jar");
	Files.copy(stage.resolve("hafen.jar"), jar);
	List<String> cmd = new ArrayList<>(Arrays.asList(javabin(), "-cp", jar.toString(),
							 "haven.Updater", "--apply",
							 Long.toString(ProcessHandle.current().pid()),
							 dir.toString(), stage.toString(), tag));
	cmd.addAll(relaunch(dir));
	new ProcessBuilder(cmd)
	    .directory(dir.toFile())
	    .redirectErrorStream(true)
	    .redirectOutput(work().resolve("update.log").toFile())
	    .start();
    }

    /* Throw away a partial or abandoned update. */
    public static void discard() {
	try {
	    rmtree(install().resolve(workdir));
	} catch(IOException | RuntimeException e) {
	}
    }

    private static String javabin() {
	Path java = Paths.get(System.getProperty("java.home"), "bin", Config.windows ? "java.exe" : "java");
	return(Files.isRegularFile(java) ? java.toString() : "java");
    }

    /* Absolute, since cmd resolves a bare script name against PATH
     * rather than the working directory it is handed. */
    private static List<String> relaunch(Path dir) {
	Path script = dir.resolve(launchscript()).toAbsolutePath();
	if(Config.windows)
	    return(Arrays.asList("cmd", "/c", script.toString()));
	return(Arrays.asList("bash", script.toString()));
    }

    private static void setexec(Path p) {
	try {
	    Set<PosixFilePermission> perm = new HashSet<>(Files.getPosixFilePermissions(p));
	    perm.add(PosixFilePermission.OWNER_EXECUTE);
	    perm.add(PosixFilePermission.GROUP_EXECUTE);
	    perm.add(PosixFilePermission.OTHERS_EXECUTE);
	    Files.setPosixFilePermissions(p, perm);
	} catch(UnsupportedOperationException | IOException e) {
	    /* Windows, most likely; nothing to do. */
	}
    }

    private static List<Path> tree(Path root) throws IOException {
	List<Path> ret = new ArrayList<>();
	try(Stream<Path> walk = Files.walk(root)) {
	    walk.forEach(ret::add);
	}
	return(ret);
    }

    private static void copytree(Path from, Path to) throws IOException {
	Path jar = from.resolve("hafen.jar");
	List<Path> files = tree(from);
	/* The client jar goes in last, so that a copy that fails
	 * halfway -- a file locked by something else -- leaves an
	 * install that still starts, rather than a new jar sitting
	 * among old libraries. The sort is stable, so everything else
	 * keeps its parents-first order. */
	files.sort(Comparator.comparing((Path p) -> p.equals(jar)));
	for(Path src : files) {
	    Path dst = to.resolve(from.relativize(src).toString());
	    if(Files.isDirectory(src))
		Files.createDirectories(dst);
	    else
		copyfile(src, dst);
	}
    }

    private static void copyfile(Path src, Path dst) throws IOException {
	IOException last = null;
	for(int i = 0; i < 60; i++) {
	    try {
		Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		return;
	    } catch(IOException e) {
		/* The client may still be shutting down and holding its
		 * jars open; wait it out. */
		last = e;
		try {
		    Thread.sleep(500);
		} catch(InterruptedException ie) {
		    Thread.currentThread().interrupt();
		    throw(new IOException(ie));
		}
	    }
	}
	throw(last);
    }

    /* Clear out the packages once they have been installed; the log
     * beside them stays, since this process still has it open. */
    private static void droppkgs(Path work) throws IOException {
	try(Stream<Path> files = Files.list(work)) {
	    for(Path p : (Iterable<Path>)files::iterator) {
		String name = p.getFileName().toString();
		if(name.endsWith(".zip") || name.endsWith(".part"))
		    Files.deleteIfExists(p);
	    }
	}
    }

    private static void rmtree(Path root) throws IOException {
	if(!Files.exists(root))
	    return;
	List<Path> files = tree(root);
	Collections.reverse(files);
	for(Path p : files)
	    Files.deleteIfExists(p);
    }

    /* Entry point of the helper process started by restart(). It runs
     * from a bare copy of the jar, with none of the client's other jars
     * on its class path, so neither this method nor anything this class
     * initialises may reach outside the JDK. */
    public static void main(String[] args) {
	if((args.length < 6) || !args[0].equals("--apply")) {
	    System.err.println("usage: haven.Updater --apply <pid> <install> <staging> <version> <command>...");
	    System.exit(1);
	}
	try {
	    long pid = Long.parseLong(args[1]);
	    Path dir = Paths.get(args[2]), stage = Paths.get(args[3]);
	    String tag = args[4];
	    List<String> cmd = new ArrayList<>(Arrays.asList(args).subList(5, args.length));
	    Optional<ProcessHandle> proc = ProcessHandle.of(pid);
	    if(proc.isPresent()) {
		try {
		    proc.get().onExit().get(120, TimeUnit.SECONDS);
		} catch(Exception e) {
		    System.err.println("client " + pid + " did not exit: " + e);
		}
	    }
	    copytree(stage, dir);
	    Files.write(dir.resolve("launcher-version.txt"),
			(tag + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
	    rmtree(stage);
	    droppkgs(dir.resolve(workdir));
	    new ProcessBuilder(cmd)
		.directory(dir.toFile())
		.redirectOutput(ProcessBuilder.Redirect.DISCARD)
		.redirectError(ProcessBuilder.Redirect.DISCARD)
		.start();
	} catch(Exception e) {
	    e.printStackTrace();
	    System.exit(1);
	}
	System.exit(0);
    }
}
