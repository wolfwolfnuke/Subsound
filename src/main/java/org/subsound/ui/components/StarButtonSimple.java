package org.subsound.ui.components;

import org.gnome.gtk.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.utils.Utils;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.subsound.utils.Utils.cssClasses;

/**
 * Minimal-cost variant of {@link StarButton} for hot binding paths (e.g. ColumnView cells).
 * Drops the hover icon-swap preview affordance — visual hover feedback comes from CSS only
 * (see {@code .starred:hover} in {@code main.css}). Per-instance cost: just {@code Button.onClicked}.
 */
public class StarButtonSimple extends Button {
    private static final Logger log = LoggerFactory.getLogger(StarButtonSimple.class);
    private Optional<Instant> starredAt;
    private final Function<Boolean, CompletableFuture<Void>> onChanged;

    public StarButtonSimple(Optional<Instant> starredAt, Function<Boolean, CompletableFuture<Void>> onChanged) {
        super();
        this.starredAt = starredAt;
        this.onChanged = onChanged;
        this.onClicked(this::toggle);
        this.setLabel(getIcon(starredAt));
        this.setCssClasses(cssClasses("starred", "flat", "circular"));
    }

    private void toggle() {
        var oldValue = this.starredAt;
        Optional<Instant> newValue = oldValue.isPresent() ? Optional.empty() : Optional.of(Instant.now());
        this.setStarredAt(newValue);
        this.onChanged.apply(newValue.isPresent()).whenComplete((a, ex) -> {
            if (ex != null) {
                log.warn("StarButtonSimple.onChanged failed, reverting", ex);
                this.setStarredAt(oldValue);
            }
        });
    }

    public void setStarredAt(Optional<Instant> starredAt) {
        this.starredAt = starredAt;
        Utils.runOnMainThread(() -> this.setLabel(getIcon(this.starredAt)));
    }

    public boolean isStarred() {
        return starredAt != null && starredAt.isPresent();
    }

    private static String getIcon(Optional<Instant> starredAt) {
        return starredAt.isPresent() ? "★" : "☆";
    }
}
