package org.subsound.ui.components;

import org.gnome.gdk.Cursor;
import org.gnome.gtk.Box;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Inscription;
import org.gnome.gtk.InscriptionOverflow;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PickFlags;
import org.gnome.gtk.Widget;
import org.gnome.pango.Layout;
import org.javagi.base.Out;
import org.jspecify.annotations.Nullable;
import org.subsound.integration.ServerClient;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ArtistsLinkLabelV2 extends Box {
    private static final int MAX_ARTISTS = 5;

    private final Consumer<String> onClick;
    private final Inscription[] inscriptions = new Inscription[MAX_ARTISTS];
    private final Label[] separators = new Label[MAX_ARTISTS - 1];
    // Indices of inscription[] keyed by Inscription identity, so a click can resolve
    // back to the slot in O(1) without scanning the array.
    private final Map<Inscription, Integer> slotByInscription = new IdentityHashMap<>(MAX_ARTISTS);
    private final String[] idBySlot = new String[MAX_ARTISTS];
    private @Nullable Layout measureLayout;

    public ArtistsLinkLabelV2(Consumer<String> onClick) {
        super(Orientation.HORIZONTAL, 0);
        this.onClick = onClick;
        this.addCssClass("artist-link");

        var pointer = Cursor.fromName("pointer", null);
        for (int i = 0; i < MAX_ARTISTS; i++) {
            var ins = new Inscription((String) null);
            ins.setTextOverflow(InscriptionOverflow.ELLIPSIZE_END);
            ins.addCssClass("artist-link-item");
            ins.setCursor(pointer);
            ins.setVisible(false);
            inscriptions[i] = ins;
            slotByInscription.put(ins, i);
            this.append(ins);

            if (i < MAX_ARTISTS - 1) {
                var sep = new Label(" • ");
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
                if (hit instanceof Inscription ins) {
                    Integer slot = slotByInscription.get(ins);
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
        if (n > 0 && measureLayout == null) {
            measureLayout = new Layout(this.getPangoContext());
        }
        var widthOut = new Out<Integer>();
        var heightOut = new Out<Integer>();
        for (int i = 0; i < MAX_ARTISTS; i++) {
            if (i < n) {
                var artist = artists.get(i);
                var ins = inscriptions[i];
                ins.setText(artist.name());
                // Inscription's setNatChars is in *average* char widths and so either
                // underestimates (text gets ellipsized mid-row) or, with a buffer,
                // overestimates (dead space at the end producing uneven gaps).
                // Measure the actual rendered pixel width via Pango and pin the
                // inscription to it.
                measureLayout.setText(artist.name(), -1);
                measureLayout.getPixelSize(widthOut, heightOut);
                // +2 horizontal slack for sub-pixel rounding; +4 vertical so descenders
                // (g, j, y) and the hover-underline don't get clipped by Inscription's
                // tight content-box allocation.
                ins.setSizeRequest(widthOut.get() + 2, heightOut.get() + 6);
                ins.setVisible(true);
                idBySlot[i] = artist.id();
            } else {
                inscriptions[i].setVisible(false);
                idBySlot[i] = null;
            }
            if (i < MAX_ARTISTS - 1) {
                separators[i].setVisible(i < n - 1);
            }
        }
    }
}