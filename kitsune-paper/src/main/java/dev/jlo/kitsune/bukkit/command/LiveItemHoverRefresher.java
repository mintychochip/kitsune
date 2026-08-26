package dev.jlo.kitsune.command;

import dev.jlo.kitsune.model.ContainerDraft;
import dev.jlo.kitsune.model.ItemDraft;
import dev.jlo.kitsune.model.ItemPath;
import dev.jlo.kitsune.search.ItemMatch;
import dev.jlo.kitsune.search.RootMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Replaces persisted item hover snapshots with matching validated live snapshots. */
final class LiveItemHoverRefresher {
    private LiveItemHoverRefresher() {}

    static List<ItemMatch> refresh(
        RootMatch indexed,
        Optional<ContainerDraft> liveDraft
    ) {
        Objects.requireNonNull(indexed, "Indexed root must not be null");
        Objects.requireNonNull(liveDraft, "Live draft optional must not be null");
        if (liveDraft.isEmpty()) {
            return indexed.itemMatches();
        }

        ContainerDraft draft = liveDraft.get();
        if (!draft.key().equals(indexed.key())
            || !draft.blockType().equals(indexed.identity().blockType())
            || !Arrays.equals(draft.fingerprint(), indexed.identity().fingerprint())) {
            return indexed.itemMatches();
        }

        Map<ItemPath, ItemDraft> byPath = new LinkedHashMap<>();
        for (ItemDraft item : draft.items()) {
            if (byPath.put(item.path(), item) != null) {
                return indexed.itemMatches();
            }
        }

        boolean changed = false;
        List<ItemMatch> refreshed = new ArrayList<>(indexed.itemMatches().size());
        for (ItemMatch match : indexed.itemMatches()) {
            ItemDraft live = byPath.get(match.path());
            if (live == null) {
                refreshed.add(match);
                continue;
            }
            ItemMatch replacement = new ItemMatch(
                live.descriptor(),
                match.path(),
                match.score(),
                live.amount()
            );
            refreshed.add(replacement);
            changed |= !replacement.equals(match);
        }
        return changed ? List.copyOf(refreshed) : indexed.itemMatches();
    }
}
