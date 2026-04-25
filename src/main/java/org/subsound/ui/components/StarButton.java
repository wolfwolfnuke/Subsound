package org.subsound.ui.components;

import org.subsound.utils.Utils;
import org.gnome.gtk.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.subsound.utils.Utils.cssClasses;

public class StarButton extends Button {
    private static final Logger log = LoggerFactory.getLogger(StarButton.class);
    private Optional<Instant> starredAt;
    private final Function<Boolean, CompletableFuture<Void>> onChanged;

    public StarButton(Optional<Instant> starredAt, Function<Boolean, CompletableFuture<Void>> onChanged) {
        super();
        this.starredAt = starredAt;
        this.onChanged = onChanged;
        // Use Button's built-in clicked signal instead of a separate GestureClick controller.
        this.onClicked(this::toggle);
        // Hover preview swaps the icon to show the toggled state — uses an EventControllerMotion
        // per instance. Costs ~1 controller + 2 signal subs per instance; only suitable for
        // non-list-cell uses. For list cells, prefer StarButtonSimple.
        org.subsound.utils.Utils.addHover(
                this,
                () -> this.setLabel(getIcon(!this.starredAt.isPresent())),
                () -> this.setLabel(getIcon(this.starredAt.isPresent()))
        );
        this.setLabel(getIcon(starredAt));
        this.setCssClasses(cssClasses("starred", "flat", "circular"));
    }

    private void toggle() {
        var oldValue = this.starredAt;
        Optional<Instant> newValue = oldValue.isPresent() ? Optional.empty() : Optional.of(Instant.now());
        this.setStarredAt(newValue);
        this.onChanged.apply(newValue.isPresent()).whenComplete((a, ex) -> {
            if (ex != null) {
                log.warn("StarButton.onChanged failed, reverting", ex);
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
        return getIcon(starredAt.isPresent());
    }
    private static String getIcon(boolean isStarred) {
        return isStarred ? "★" : "☆";
    }
}
