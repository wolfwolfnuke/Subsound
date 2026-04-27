package org.subsound.ui.components;

import org.gnome.gtk.Label;
import org.gnome.pango.EllipsizeMode;
import org.subsound.integration.ServerClient;

import java.util.List;
import java.util.function.Consumer;

import static org.subsound.utils.javahttp.TextUtils.escapeMarkupGtkLabel;

public class ArtistsLinkLabel extends Label {
    private final Consumer<String> onClick;

    public ArtistsLinkLabel(Consumer<String> onClick) {
        this.onClick = onClick;
        this.setSingleLineMode(true);
        this.setMaxWidthChars(48);
        this.setEllipsize(EllipsizeMode.END);
        this.addCssClass("artist-link");
        var signal = this.onActivateLink(link -> {
            var parts = link.split(":");
            if (parts.length == 2) {
                onClick.accept(parts[1]);
            }
            return true;
        });
        this.onDestroy(signal::disconnect);
    }

    public void setArtists(List<ServerClient.ArtistId> artists) {
        if (artists.size() == 1) {
            var artist = artists.get(0);
            var link = "<a href=\"artist:%s\">%s</a>".formatted(artist.id(), escapeMarkupGtkLabel(artist.name()));
            this.setMarkup(link);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (var artist : artists) {
            sb.append("<a href=\"artist:%s\">%s</a>".formatted(artist.id(), escapeMarkupGtkLabel(artist.name()))).append(" • ");
        }
        this.setMarkup(sb.substring(0, sb.length() - 3));
    }
}
