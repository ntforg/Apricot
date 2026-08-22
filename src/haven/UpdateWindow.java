package haven;

import java.nio.file.Path;

/*
 * Installs a new release from the login screen: downloads the package
 * in the background, unpacks it beside the install and restarts the
 * client into it. LoginScreen shows this when a newer version is out
 * and Updater.possible(); when it is not -- Steam, or an install we
 * must not rewrite -- it falls back to a notice telling the player to
 * update by hand.
 */
public class UpdateWindow extends Window {
    private static final int width = UI.scale(340);
    /* A moment between "done" and the client vanishing, so that the
     * restart is never a surprise mid-keystroke. */
    private static final double delay = 5.0;
    private final String tag;
    private final Label status;
    private final Button action;
    private volatile String stext = "Starting download...";
    private volatile double frac = 0.0;
    private volatile String error = null;
    private volatile Path staged = null;
    private volatile boolean cancelled = false;
    private String shown = "", shownbtn = "";
    private double left = delay;
    private boolean restarting = false;

    private final Updater.Progress prog = new Updater.Progress() {
	    public void status(String text, double frac) {
		stext = text;
		UpdateWindow.this.frac = frac;
	    }

	    public boolean cancelled() {
		return(cancelled);
	    }
	};

    public UpdateWindow(String tag) {
	super(Coord.z, "Update Available!", true);
	this.tag = tag;
	Widget prev;
	prev = add(new Label("Apricot " + tag + " is available."), Coord.z);
	prev = add(new Progress(width).val(() -> (float)frac).percent(), prev.pos("bl").adds(0, 8).x(0));
	prev = add(status = new Label(stext), prev.pos("bl").adds(0, 6).x(0));
	prev = add(new CheckBox("Install updates automatically") {
		{a = Updater.enabled();}

		public void changed(boolean val) {
		    Updater.enabled(val);
		    if(!val)
			skip();
		}
	    }, prev.pos("bl").adds(0, 10).x(0));
	action = add(new Button(UI.scale(140), "Skip", false) {
		public void click() {
		    skip();
		}
	    }, prev.pos("bl").adds(0, 10).x(0));
	pack();
	reqclose(this::skip);
	Thread w = new HackThread(this::run, "Client updater");
	w.setDaemon(true);
	w.start();
    }

    private void run() {
	try {
	    staged = Updater.unpack(Updater.download(tag, prog), prog);
	} catch(Updater.Cancelled e) {
	    Updater.discard();
	} catch(Exception e) {
	    error = errmsg(e);
	    new Warning(e, "could not install client update").issue();
	}
    }

    private static String errmsg(Throwable t) {
	String ret = (t.getMessage() == null) ? t.getClass().getSimpleName() : t.getMessage();
	return((ret.length() > 48) ? (ret.substring(0, 48) + "...") : ret);
    }

    private void skip() {
	cancelled = true;
	staged = null;
	Updater.skipped(true);
	reqdestroy();
    }

    public void destroy() {
	/* Also covers logging in while the download is running: the
	 * login screen takes this window with it, and a client that is
	 * in the world must not restart itself. */
	cancelled = true;
	super.destroy();
    }

    private void say(String text, String btn) {
	if(!text.equals(shown)) {
	    status.settext(text);
	    shown = text;
	}
	if(!btn.equals(shownbtn)) {
	    action.change(btn);
	    shownbtn = btn;
	}
    }

    public void tick(double dt) {
	super.tick(dt);
	if(error != null) {
	    frac = 0.0;
	    say("Update failed: " + error, "Close");
	} else if(restarting) {
	    say("Restarting...", "Close");
	} else if(staged != null) {
	    frac = 1.0;
	    if((left -= dt) <= 0) {
		restart();
	    } else {
		say(String.format("Ready. Restarting in %d...", (int)Math.ceil(left)), "Skip");
	    }
	} else {
	    say(stext, "Skip");
	}
    }

    private void restart() {
	restarting = true;
	try {
	    Updater.restart(tag, staged);
	} catch(Exception e) {
	    restarting = false;
	    staged = null;
	    error = errmsg(e);
	    new Warning(e, "could not start the client updater").issue();
	    return;
	}
	System.exit(0);
    }
}
