package org.subsound.debug;

import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.Colorspace;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gio.ListStore;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColumnView;
import org.gnome.gtk.ColumnViewColumn;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Picture;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.javagi.gobject.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.utils.Utils;

import java.lang.foreign.MemorySegment;

/**
 * Standalone GTK benchmark that reproduces the ColumnView playlist-swap bottleneck in isolation.
 *
 * <p>Renders a ColumnView with N rows where the first column is a {@link Picture} backed by a
 * pre-decoded {@link Texture}, plus several {@link Label} columns. A "Swap" button clears the
 * model and repopulates it, timing the {@code splice} main-thread cost.
 *
 * <p>Toggle {@link #DEFER_SET_PAINTABLE} to compare:
 * <ul>
 *   <li>{@code false} — {@code setPaintable} called synchronously inside onBind (slow path,
 *       ~150ms extra for 200 rows on a mapped ColumnView).</li>
 *   <li>{@code true} — {@code setPaintable} deferred to a {@code runOnMainThread} idle so the
 *       splice call stays at the "Label-only" floor (~290-340ms for 200 rows here).</li>
 * </ul>
 *
 * <p>Run: {@code ./gradlew test --tests ... } won't work because this needs the GTK main loop;
 * launch via {@code java -cp ... GtkColumnViewBenchmarkTest} or create a Gradle run task.
 */
public class GtkColumnViewBenchmarkTest {
    private static final Logger log = LoggerFactory.getLogger(GtkColumnViewBenchmarkTest.class);

    private static final int ROWS = 200;
    private static final int ART_SIZE = 48;
    /** Flip to compare bind-time setPaintable vs. idle-deferred setPaintable. */
    private static final boolean DEFER_SET_PAINTABLE = true;

    public static void main(String[] args) {
        Application app = new Application("org.subsound.debug.ColumnViewBenchmark", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(() -> new BenchmarkWindow(app));
        app.run(args);
    }

    public static class GRow extends GObject {
        public static final Type gtype = Types.register(GRow.class);

        private int index;
        private String title;
        private String artist;
        private String album;

        public GRow(MemorySegment address) { super(address); }

        public static Type getType() { return gtype; }

        public static GRow of(int index) {
            var instance = (GRow) GObject.newInstance(getType());
            instance.index = index;
            instance.title = "Song title #" + index;
            instance.artist = "Artist #" + (index % 30);
            instance.album = "Album #" + (index % 50);
            return instance;
        }

        public int index()   { return index; }
        public String title() { return title; }
        public String artist() { return artist; }
        public String album() { return album; }
    }

    private static final int TEXTURE_PX = 96;

    private static Texture makeSolidTexture(int rgba) {
        var pixbuf = new Pixbuf(Colorspace.RGB, true, 8, TEXTURE_PX, TEXTURE_PX);
        pixbuf.fill(rgba);
        return Texture.forPixbuf(pixbuf);
    }

    /** Picture subclass that matches real {@code RoundedAlbumArtV2}'s coalesced idle dispatch. */
    public static class BenchPicture extends Picture {
        @org.jspecify.annotations.Nullable Texture pending;
        boolean applyScheduled = false;

        public BenchPicture(MemorySegment address) { super(address); }

        public BenchPicture() {
            super();
        }

        void scheduleApply() {
            if (applyScheduled) {
                return;
            }
            applyScheduled = true;
            Utils.runOnMainThread(() -> {
                applyScheduled = false;
                var tex = pending;
                if (tex != null) {
                    setPaintable(tex);
                }
            });
        }
    }

    private static class BenchmarkWindow {
        private final ListStore<GRow> model = new ListStore<>();
        // Unique texture per row — every setPaintable call targets a different paintable identity,
        // matching the real app where every cover art id is distinct.
        private final Texture[] textures;
        private int swapCount = 0;

        BenchmarkWindow(Application app) {
            this.textures = new Texture[ROWS];
            for (int i = 0; i < ROWS; i++) {
                // Spread across the hue wheel so colors are visually distinct.
                this.textures[i] = makeSolidTexture(hueToRgba(i / (float) ROWS));
            }

            var columnView = ColumnView.builder()
                    .setShowRowSeparators(false)
                    .setHexpand(true)
                    .setVexpand(true)
                    .build();

            columnView.setModel(new SingleSelection<>(model));

            // ---- Picture column (the expensive one) ----
            var artFactory = new SignalListItemFactory();
            artFactory.onSetup(obj -> {
                var item = (ListItem) obj;
                var picture = new Picture();
                picture.setContentFit(ContentFit.COVER);
                picture.setSizeRequest(ART_SIZE, ART_SIZE);
                picture.setOverflow(Overflow.HIDDEN);
                picture.setName("BenchArt");
                item.setChild(picture);
            });
            artFactory.onBind(obj -> {
                var item = (ListItem) obj;
                if (!(item.getChild() instanceof Picture pic)) {
                    return;
                }
                var row = (GRow) item.getItem();
                if (row == null) {
                    return;
                }
                var tex = textures[(swapCount + row.index()) % textures.length];
                if (DEFER_SET_PAINTABLE) {
                    Utils.runOnMainThread(() -> pic.setPaintable(tex));
                } else {
                    pic.setPaintable(tex);
                }
            });
            artFactory.onTeardown(obj -> ((ListItem) obj).setChild(null));
            var artCol = new ColumnViewColumn("", artFactory);
            artCol.setFixedWidth(60);
            columnView.appendColumn(artCol);

            columnView.appendColumn(labelColumn("Title", GRow::title, true));
            columnView.appendColumn(labelColumn("Artist", GRow::artist, false));
            columnView.appendColumn(labelColumn("Album", GRow::album, false));

            var scroll = ScrolledWindow.builder()
                    .setHexpand(true)
                    .setVexpand(true)
                    .setChild(columnView)
                    .build();

            var swapButton = new Button();
            swapButton.setLabel("Swap (splice %d items)".formatted(ROWS));
            swapButton.onClicked(this::runBenchmark);

            var primeButton = new Button();
            primeButton.setLabel("Prime (fill from empty)");
            primeButton.onClicked(() -> {
                log.info("prime: model starts empty");
                this.model.removeAll();
                runBenchmark();
            });

            var header = new Box(Orientation.HORIZONTAL, 8);
            header.append(primeButton);
            header.append(swapButton);

            var root = new Box(Orientation.VERTICAL, 0);
            root.append(header);
            root.append(scroll);

            var window = ApplicationWindow.builder()
                    .setApplication(app)
                    .setDefaultWidth(900)
                    .setDefaultHeight(700)
                    .setChild(root)
                    .build();
            window.present();

            // Pre-populate with a small set so the first real swap goes large-from-small,
            // matching what the PlaylistListViewV2 numbers were measuring.
            var seed = new GRow[5];
            for (int i = 0; i < seed.length; i++) {
                seed[i] = GRow.of(i);
            }
            this.model.splice(0, 0, seed);
        }

        private void runBenchmark() {
            swapCount++;
            long t0 = System.nanoTime();
            var items = new GRow[ROWS];
            for (int i = 0; i < ROWS; i++) {
                items[i] = GRow.of(i);
            }
            long t1 = System.nanoTime();
            int oldN = model.getNItems();
            model.splice(0, oldN, items);
            long t2 = System.nanoTime();
            log.info(
                    "benchmark: n={} oldN={} deferPaintable={} alloc={}ms splice={}ms total={}ms",
                    ROWS, oldN, DEFER_SET_PAINTABLE,
                    (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000,
                    (t2 - t0) / 1_000_000);
        }

        private static ColumnViewColumn labelColumn(String title, LabelGetter getter, boolean expand) {
            var factory = new SignalListItemFactory();
            factory.onSetup(obj -> {
                var item = (ListItem) obj;
                item.setChild(new Label());
            });
            factory.onBind(obj -> {
                var item = (ListItem) obj;
                if (!(item.getChild() instanceof Label label)) {
                    return;
                }
                var row = (GRow) item.getItem();
                if (row == null) {
                    return;
                }
                label.setLabel(getter.get(row));
            });
            factory.onTeardown(obj -> ((ListItem) obj).setChild(null));
            var col = new ColumnViewColumn(title, factory);
            if (expand) {
                col.setExpand(true);
            } else {
                col.setFixedWidth(150);
            }
            return col;
        }

        @FunctionalInterface
        private interface LabelGetter { String get(GRow row); }
    }
}
