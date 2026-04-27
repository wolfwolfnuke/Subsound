package org.subsound.ui.components;

import org.gnome.gdk.Cursor;
import org.gnome.gtk.Box;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PickFlags;
import org.gnome.gtk.Widget;
import org.subsound.integration.ServerClient;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Alternative to {@link ArtistsLinkLabelV2} using {@link Label}s instead of
 * {@link org.gnome.gtk.Inscription}. Labels self-size to text content and honor
 * CSS {@code text-decoration: underline}, so we drop both the Pango pixel
 * measurement and the manual {@code setSizeRequest} calls. Same single
 * {@link GestureClick} + {@code pick()} dispatch model as V2.
 */
public class ArtistsLinkLabelV3 extends Box {
    private static final int MAX_ARTISTS = 5;

    private final Consumer<String> onClick;
    private final Label[] artistLabels = new Label[MAX_ARTISTS];
    private final Label[] separators = new Label[MAX_ARTISTS - 1];
    private final Map<Label, Integer> slotByLabel = new IdentityHashMap<>(MAX_ARTISTS);
    private final String[] idBySlot = new String[MAX_ARTISTS];

    public ArtistsLinkLabelV3(Consumer<String> onClick) {
        super(Orientation.HORIZONTAL, 0);
        this.onClick = onClick;
        this.addCssClass("artist-link");

        var pointer = Cursor.fromName("pointer", null);
        for (int i = 0; i < MAX_ARTISTS; i++) {
            var lbl = new Label((String) null);
            // Explicit plain-text mode: artist names containing &, <, > render literally
            // and no markup parser runs on bind. Matches codebase convention.
            lbl.setUseMarkup(false);
            lbl.setSingleLineMode(true);
            lbl.addCssClass("artist-link-item");
            lbl.setCursor(pointer);
            lbl.setVisible(false);
            artistLabels[i] = lbl;
            slotByLabel.put(lbl, i);
            this.append(lbl);

            if (i < MAX_ARTISTS - 1) {
                var sep = new Label(" • ");
                sep.setUseMarkup(false);
                sep.addCssClass("artist-link-sep");
                sep.setVisible(false);
                separators[i] = sep;
                this.append(sep);
            }
        }

        var gestureClick = new GestureClick();
        this.addController(gestureClick);
        var signal = gestureClick.onReleased((nPress, x, y) -> {
            Widget hit = this.pick(x, y, PickFlags.DEFAULT);
            while (hit != null && !hit.equals(this)) {
                if (hit instanceof Label lbl) {
                    Integer slot = slotByLabel.get(lbl);
                    if (slot != null) {
                        String id = idBySlot[slot];
                        if (id != null) {
                            this.onClick.accept(id);
                        }
                        return;
                    }
                }
                hit = hit.getParent();
            }
        });
        this.onDestroy(signal::disconnect);
    }

    public void setArtists(List<ServerClient.ArtistId> artists) {
        int n = Math.min(artists.size(), MAX_ARTISTS);
        for (int i = 0; i < MAX_ARTISTS; i++) {
            if (i < n) {
                var artist = artists.get(i);
                var lbl = artistLabels[i];
                lbl.setLabel(artist.name());
                lbl.setVisible(true);
                idBySlot[i] = artist.id();
            } else {
                artistLabels[i].setVisible(false);
                idBySlot[i] = null;
            }
            if (i < MAX_ARTISTS - 1) {
                separators[i].setVisible(i < n - 1);
            }
        }
    }
}
