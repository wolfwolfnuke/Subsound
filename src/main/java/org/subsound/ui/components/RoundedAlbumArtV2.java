package org.subsound.ui.components;

import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Picture;
import org.jspecify.annotations.Nullable;
import org.subsound.app.state.AppManager;
import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.utils.Utils;

import java.util.Objects;

/**
 * Lightweight list-row variant of {@link RoundedAlbumArt}.
 *
 * <p>{@link #update} applies the texture synchronously on cache hit (no deferred dispatch:
 * the always-deferred version showed no measurable splice win in practice). On cache miss,
 * the placeholder is set inline and the real texture is loaded asynchronously, with the
 * final {@code setPaintable} call dispatched onto the main thread once the load completes.
 * Re-entry while a load is in flight is guarded by {@link #currentArtwork} identity.
 */
public class RoundedAlbumArtV2 extends Picture {
    private final AppManager thumbLoader;
    private final int size;

    @Nullable private CoverArt currentArtwork;
    @Nullable private String appliedCoverArtId; // null = placeholder currently shown

    public RoundedAlbumArtV2(AppManager thumbLoader, int size) {
        this.thumbLoader = thumbLoader;
        this.size = size;
        setContentFit(ContentFit.COVER);
        setSizeRequest(size, size);
        setOverflow(Overflow.HIDDEN);
        setName("RoundedAlbumArtV2");
    }

    public void update(@Nullable CoverArt newArtwork) {
        var newId = newArtwork != null ? newArtwork.coverArtId() : null;
        if (Objects.equals(appliedCoverArtId, newId)) {
            currentArtwork = newArtwork;
            return;
        }
        currentArtwork = newArtwork;
        if (newArtwork == null) {
            setPaintable(RoundedAlbumArt.getPlaceholderTexture());
            appliedCoverArtId = null;
            return;
        }
        var cached = thumbLoader.getThumbnailCache().getCachedTexture(newArtwork, this.size);
        if (cached.isPresent()) {
            setPaintable(cached.get().texture());
            appliedCoverArtId = newId;
            return;
        }
        // Cache miss: show placeholder so a slow load doesn't leave the previous row's art
        // visible. The async load applies the real texture once decoded; we still need
        // runOnMainThread there because the future completes off the GTK thread.
        setPaintable(RoundedAlbumArt.getPlaceholderTexture());
        appliedCoverArtId = null;
        var snapshot = newArtwork;
        thumbLoader.getThumbnailCache().loadPixbuf(newArtwork, this.size)
                .thenAccept(stored -> {
                    if (currentArtwork != snapshot) {
                        return;
                    }
                    Utils.runOnMainThread(() -> {
                        if (currentArtwork != snapshot) {
                            return;
                        }
                        setPaintable(stored.texture());
                        appliedCoverArtId = snapshot.coverArtId();
                    });
                })
                .exceptionally(e -> null);
    }
}
