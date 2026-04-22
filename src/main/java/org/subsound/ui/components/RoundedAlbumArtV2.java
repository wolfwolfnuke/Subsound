package org.subsound.ui.components;

import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Picture;
import org.jspecify.annotations.Nullable;
import org.subsound.app.state.AppManager;
import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.utils.Utils;

/**
 * Lightweight list-row variant of {@link RoundedAlbumArt}.
 *
 * Designed for hot ColumnView/ListView binding paths where row activation is already handled
 * by the row itself. Skips click/hover controllers, the outer Box + Clamp wrapper, and the
 * onMap/onDestroy signal plumbing that make V1 expensive to construct hundreds of times.
 */
public class RoundedAlbumArtV2 extends Picture {
    private final AppManager thumbLoader;
    private final int size;
    @Nullable private CoverArt artwork;

    public RoundedAlbumArtV2(AppManager thumbLoader, int size) {
        this.thumbLoader = thumbLoader;
        this.size = size;
        setContentFit(ContentFit.COVER);
        setSizeRequest(size, size);
        setOverflow(Overflow.HIDDEN);
        setName("RoundedAlbumArtV2");
        //addCssClass("rounded");
        //setPaintable(RoundedAlbumArt.getPlaceholderTexture());
    }

    public void update(@Nullable CoverArt newArtwork) {
        var old = this.artwork;
        if (old == newArtwork) {
            return;
        }
        if (old != null && newArtwork != null && old.coverArtId().equals(newArtwork.coverArtId())) {
            return;
        }
        this.artwork = newArtwork;
        if (newArtwork == null) {
            setPaintable(RoundedAlbumArt.getPlaceholderTexture());
            return;
        }
        var cached = thumbLoader.getThumbnailCache().getCachedTexture(newArtwork, this.size);
        if (cached.isPresent()) {
            setPaintable(cached.get().texture());
            return;
        }
        // Placeholder on miss so slow loads do not leave the previous row's art visible.
        setPaintable(RoundedAlbumArt.getPlaceholderTexture());
        var snapshot = newArtwork;
        thumbLoader.getThumbnailCache().loadPixbuf(newArtwork, this.size)
                .thenAccept(stored -> {
                    if (this.artwork != snapshot) {
                        return;
                    }
                    Utils.runOnMainThread(() -> setPaintable(stored.texture()));
                })
                .exceptionally(e -> null);
    }
}
