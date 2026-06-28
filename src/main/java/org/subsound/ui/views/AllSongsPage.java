package org.subsound.ui.views;

import org.gnome.gio.ListStore;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColumnView;
import org.gnome.gtk.ColumnViewColumn;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.SortListModel;
import org.gnome.gtk.Sorter;
import org.gnome.pango.EllipsizeMode;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Ordering;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.Icons;
import org.subsound.ui.models.GSongInfo;
import org.subsound.utils.Utils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class AllSongsPage extends Box {
    private static final Logger log = LoggerFactory.getLogger(AllSongsPage.class);

    private final AppManager appManager;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ListStore<GSongInfo> listStore = new ListStore<>(GSongInfo.getType());
    private final SortListModel<GSongInfo> sortModel;
    private final ColumnView columnView;
    private final Label statusLabel;
    private final Button refreshButton;

    public AllSongsPage(
            AppManager appManager,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.onAction = onAction;
        this.setHexpand(true);
        this.setVexpand(true);

        // --- Header ---
        var header = new Box(HORIZONTAL, 8);
        header.setMarginStart(12);
        header.setMarginEnd(12);
        header.setMarginTop(8);
        header.setMarginBottom(8);

        var titleLabel = new Label("Songs");
        titleLabel.addCssClass(Classes.titleLarge.className());
        titleLabel.setHalign(Align.START);
        titleLabel.setHexpand(true);
        header.append(titleLabel);

        this.statusLabel = new Label("");
        this.statusLabel.addCssClass(Classes.caption.className());
        this.statusLabel.addCssClass(Classes.labelDim.className());
        this.statusLabel.setHalign(Align.END);
        this.statusLabel.setMarginEnd(8);
        header.append(statusLabel);

        this.refreshButton = Button.builder()
                .setIconName(Icons.RefreshView.getIconName())
                .setTooltipText("Sync all songs from server")
                .build();
        this.refreshButton.onClicked(() -> loadSongsAsync());
        header.append(refreshButton);
        this.append(header);

        // --- ColumnView ---
        this.columnView = ColumnView.builder()
                .setShowRowSeparators(false)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .setSingleClickActivate(true)
                .build();

        // Set up sorting: SortListModel wraps the list store with the column view's sorter
        this.sortModel = new SortListModel<>(this.listStore, this.columnView.getSorter());

        var selectionModel = new SingleSelection<>(this.sortModel);
        selectionModel.setAutoselect(false);
        selectionModel.setCanUnselect(true);

        // Create columns
        setupColumns();

        this.columnView.setModel(selectionModel);

        // Handle row activation (play song)
        this.columnView.onActivate(index -> {
            var selected = selectionModel.getItem(index);
            if (selected instanceof GSongInfo gSong) {
                var songInfo = gSong.getSongInfo();
                log.info("AllSongsPage: play song {}", songInfo.title());
                onAction.apply(new PlayerAction.PlaySong(songInfo));
            }
        });

        var scrolledWindow = ScrolledWindow.builder()
                .setChild(this.columnView)
                .setHexpand(true)
                .setVexpand(true)
                .build();
        this.append(scrolledWindow);

        // Initial load
        loadFromDb();
    }

    private void setupColumns() {
        // Title + Artist column
        var titleSorter = new GSongInfoFieldSorter(GSongInfoFieldSorter.Field.TITLE);
        var titleFactory = new SignalListItemFactory();
        titleFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setActivatable(true);
            listItem.setChild(new SongTitleCell());
        });
        titleFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var item = (GSongInfo) listItem.getItem();
            if (listItem.getChild() instanceof SongTitleCell cell) {
                cell.bind(item);
            }
        });
        var titleCol = new ColumnViewColumn("Title", titleFactory);
        titleCol.setExpand(true);
        titleCol.setSorter(titleSorter);
        this.columnView.appendColumn(titleCol);

        // Artist column
        var artistSorter = new GSongInfoFieldSorter(GSongInfoFieldSorter.Field.ARTIST);
        var artistFactory = new SignalListItemFactory();
        artistFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setActivatable(true);
            var label = new Label("");
            label.setHalign(Align.START);
            label.setEllipsize(EllipsizeMode.END);
            label.setSingleLineMode(true);
            label.addCssClass(Classes.labelDim.className());
            listItem.setChild(label);
        });
        artistFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var item = (GSongInfo) listItem.getItem();
            if (listItem.getChild() instanceof Label label) {
                label.setLabel(item.getSongInfo().artistName());
            }
        });
        var artistCol = new ColumnViewColumn("Artist", artistFactory);
        artistCol.setFixedWidth(200);
        artistCol.setSorter(artistSorter);
        this.columnView.appendColumn(artistCol);

        // Album column
        var albumSorter = new GSongInfoFieldSorter(GSongInfoFieldSorter.Field.ALBUM);
        var albumFactory = new SignalListItemFactory();
        albumFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setActivatable(true);
            var label = new Label("");
            label.setHalign(Align.START);
            label.setEllipsize(EllipsizeMode.END);
            label.setSingleLineMode(true);
            label.addCssClass(Classes.labelDim.className());
            listItem.setChild(label);
        });
        albumFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var item = (GSongInfo) listItem.getItem();
            if (listItem.getChild() instanceof Label label) {
                label.setLabel(item.getSongInfo().album());
            }
        });
        var albumCol = new ColumnViewColumn("Album", albumFactory);
        albumCol.setFixedWidth(220);
        albumCol.setSorter(albumSorter);
        this.columnView.appendColumn(albumCol);

        // Duration column
        var durationSorter = new GSongInfoFieldSorter(GSongInfoFieldSorter.Field.DURATION);
        var durationFactory = new SignalListItemFactory();
        durationFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setActivatable(true);
            var label = new Label("");
            label.setHalign(Align.END);
            label.addCssClass(Classes.labelNumeric.className());
            listItem.setChild(label);
        });
        durationFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var item = (GSongInfo) listItem.getItem();
            if (listItem.getChild() instanceof Label label) {
                var d = item.getSongInfo().duration();
                label.setLabel("%d:%02d".formatted(d.toMinutes(), d.toSecondsPart()));
            }
        });
        var durationCol = new ColumnViewColumn("Duration", durationFactory);
        durationCol.setFixedWidth(90);
        durationCol.setSorter(durationSorter);
        this.columnView.appendColumn(durationCol);

        // Year column
        var yearSorter = new GSongInfoFieldSorter(GSongInfoFieldSorter.Field.YEAR);
        var yearFactory = new SignalListItemFactory();
        yearFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setActivatable(true);
            var label = new Label("");
            label.setHalign(Align.END);
            label.addCssClass(Classes.labelDim.className());
            listItem.setChild(label);
        });
        yearFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var item = (GSongInfo) listItem.getItem();
            if (listItem.getChild() instanceof Label label) {
                label.setLabel(item.getSongInfo().year().map(String::valueOf).orElse(""));
            }
        });
        var yearCol = new ColumnViewColumn("Year", yearFactory);
        yearCol.setFixedWidth(70);
        yearCol.setSorter(yearSorter);
        this.columnView.appendColumn(yearCol);
    }

    private void loadFromDb() {
        var songs = appManager.getAllSongsFromDb();
        if (!songs.isEmpty()) {
            updateSongList(songs);
            statusLabel.setLabel("%d songs (cached)".formatted(songs.size()));
            // Also sync in background to refresh
            syncInBackground();
        } else {
            statusLabel.setLabel("Loading...");
            loadSongsAsync();
        }
    }

    private void loadSongsAsync() {
        refreshButton.setSensitive(false);
        statusLabel.setLabel("Syncing songs from server...");
        appManager.syncAllSongsAsync()
                .thenAccept(songs -> Utils.runOnMainThread(() -> {
                    updateSongList(songs);
                    statusLabel.setLabel("%d songs".formatted(songs.size()));
                    refreshButton.setSensitive(true);
                }))
                .exceptionally(ex -> {
                    log.error("Failed to sync songs", ex);
                    Utils.runOnMainThread(() -> {
                        statusLabel.setLabel("Sync failed");
                        refreshButton.setSensitive(true);
                        // Fall back to DB
                        var cached = appManager.getAllSongsFromDb();
                        if (!cached.isEmpty()) {
                            updateSongList(cached);
                            statusLabel.setLabel("%d songs (cached)".formatted(cached.size()));
                        }
                    });
                    return null;
                });
    }

    private void syncInBackground() {
        appManager.syncAllSongsAsync()
                .thenAccept(songs -> Utils.runOnMainThread(() -> {
                    updateSongList(songs);
                    statusLabel.setLabel("%d songs".formatted(songs.size()));
                    refreshButton.setSensitive(true);
                }))
                .exceptionally(ex -> {
                    log.warn("Background sync failed (using cached songs)", ex);
                    return null;
                });
    }

    private void updateSongList(List<SongInfo> songs) {
        var gSongStore = appManager.getSongStore();
        // Preserve existing GSongInfo instances by reusing the store
        listStore.removeAll();
        for (var song : songs) {
            var gSong = gSongStore.newInstance(song);
            listStore.append(gSong);
        }
    }

    // --- Custom Sorter for GSongInfo fields ---
    static class GSongInfoFieldSorter extends Sorter {
        enum Field { TITLE, ARTIST, ALBUM, DURATION, YEAR }
        private final Field field;

        GSongInfoFieldSorter(Field field) {
            super();
            this.field = field;
        }

        @Override
        public @NonNull Ordering compare(@NonNull GObject a, @NonNull GObject b) {
            if (a == b) return Ordering.EQUAL;
            var aa = (GSongInfo) a;
            var bb = (GSongInfo) b;
            return switch (field) {
                case TITLE -> fromCompare(aa.getSongInfo().title().compareToIgnoreCase(bb.getSongInfo().title()));
                case ARTIST -> fromCompare(aa.getSongInfo().artistName().compareToIgnoreCase(bb.getSongInfo().artistName()));
                case ALBUM -> fromCompare(aa.getSongInfo().album().compareToIgnoreCase(bb.getSongInfo().album()));
                case DURATION -> fromCompare(aa.getSongInfo().duration().compareTo(bb.getSongInfo().duration()));
                case YEAR -> {
                    int ay = aa.getSongInfo().year().orElse(0);
                    int by = bb.getSongInfo().year().orElse(0);
                    yield fromCompare(Integer.compare(ay, by));
                }
            };
        }

        private static @NonNull Ordering fromCompare(int cmp) {
            if (cmp == 0) return Ordering.EQUAL;
            return cmp < 0 ? Ordering.SMALLER : Ordering.LARGER;
        }
    }

    // --- Title + Artist cell widget ---
    static class SongTitleCell extends Box {
        private final Label titleLabel;

        SongTitleCell() {
            super(VERTICAL, 2);
            this.setHalign(Align.START);
            this.setValign(Align.CENTER);
            this.setHexpand(true);
            this.setMarginStart(4);
            this.setMarginEnd(4);

            this.titleLabel = new Label("");
            this.titleLabel.setHalign(Align.START);
            this.titleLabel.setEllipsize(EllipsizeMode.END);
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setMaxWidthChars(40);
            this.append(titleLabel);
        }

        void bind(GSongInfo gSong) {
            var info = gSong.getSongInfo();
            this.titleLabel.setLabel(info.title());
        }
    }
}
