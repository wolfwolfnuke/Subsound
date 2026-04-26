package org.subsound.ui.views;

import org.gnome.gobject.GObject;
import org.gnome.gtk.Ordering;
import org.gnome.gtk.Sorter;
import org.gnome.gtk.SorterChange;
import org.jspecify.annotations.NonNull;
import org.subsound.ui.views.PlaylistListViewV2.GPlaylistEntry;
import org.subsound.utils.Utils;

public class TitleArtistColumnSorter extends Sorter {
    // Because of the built-in sorting in the ColumnView,
    // it will always be flipping ASC --> DESC and back to ASC --> DESC.
    // This means we have to have an even number of states to flip between
    // or find a way to disable the built-in sorting.
    public enum States {
        NONE,
        ARTIST_ASC,
        ARTIST_DESC,
        TITLE_ASC,
        TITLE_DESC,
    }

    private States state = States.NONE;
    public TitleArtistColumnSorter() {
        super();
    }

    @Override
    public @NonNull Ordering compare(@NonNull GObject a, @NonNull GObject b) {
        if (a == b) {
            return Ordering.EQUAL;
        }
        GPlaylistEntry aa = (GPlaylistEntry) a;
        GPlaylistEntry bb = (GPlaylistEntry) b;
        var res = switch (state) {
            case NONE -> Ordering.EQUAL;
            case TITLE_ASC -> fromCompare(aa.gSong().getSongInfo().title().compareTo(bb.gSong().getSongInfo().title()));
            // the built-in sorting in the ColumnView handles the reversing, so we need to return same result here
            case TITLE_DESC -> fromCompare(aa.gSong().getSongInfo().title().compareTo(bb.gSong().getSongInfo().title()));
            case ARTIST_ASC -> fromCompare(aa.gSong().getSongInfo().artist().compareTo(bb.gSong().getSongInfo().artist()));
            // the built-in sorting in the ColumnView handles the reversing, so we need to return same result here
            case ARTIST_DESC -> fromCompare(aa.gSong().getSongInfo().artist().compareTo(bb.gSong().getSongInfo().artist()));
        };
        //var sor = this.getOrder();
        //System.out.printf("compare (%s|%s): %s vs %s%n", sor.name(), res.name(), aa.gSong().getSongInfo().title(), bb.gSong().getSongInfo().title());
        return res;
    }

    public static @NonNull Ordering fromCompare(int compare) {
        if (compare == 0) {
            return Ordering.EQUAL;
        } else if (compare < 0) {
            return Ordering.SMALLER;
        } else {
            return Ordering.LARGER;
        }
    }

    private final States[] ALL = States.values();
    public States next() {
        var next = state.ordinal() + 1;
        if (next >= ALL.length) {
            next = 0;
        }
        state = ALL[next];
        // need to defer run to main thread:
        // calling changed() is a synchronous call, so calling it eagerly immediately creates a loop
        // back to the change handler we are already calling from
        Utils.runOnMainThread(() -> {
            this.changed(SorterChange.DIFFERENT);
        });
        return state;
    }

    public void resetSorter() {
        this.state = States.NONE;
    }
}
