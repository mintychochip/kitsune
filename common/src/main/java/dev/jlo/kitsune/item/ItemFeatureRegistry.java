package dev.jlo.kitsune.item;

import dev.jlo.kitsune.api.item.ItemFeatureProvider;
import dev.jlo.kitsune.model.ItemDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ItemFeatureRegistry {
    private final List<ItemFeatureProvider> providers;
    private final Consumer<Throwable> failureReporter;

    public ItemFeatureRegistry(
        Iterable<ItemFeatureProvider> providers,
        Consumer<Throwable> failureReporter
    ) {
        Objects.requireNonNull(providers, "Providers must not be null");
        this.providers = new ArrayList<>();
        for (ItemFeatureProvider provider : providers) {
            this.providers.add(Objects.requireNonNull(provider, "Provider must not be null"));
        }
        this.failureReporter = Objects.requireNonNull(
            failureReporter,
            "Failure reporter must not be null"
        );
    }

    public List<ItemFeatureProvider> providers() {
        return List.copyOf(providers);
    }

    public void contribute(ItemDescriptor item, ItemDescriptor.Builder baseline) {
        Objects.requireNonNull(item, "Item must not be null");
        Objects.requireNonNull(baseline, "Baseline must not be null");
        for (ItemFeatureProvider provider : providers) {
            try {
                applyContribution(
                    baseline,
                    isolated -> provider.contribute(item, isolated)
                );
            } catch (Exception failure) {
                failureReporter.accept(failure);
            }
        }
    }

    public static void applyContribution(
        ItemDescriptor.Builder baseline,
        Consumer<ItemDescriptor.Builder> providerCall
    ) {
        Objects.requireNonNull(baseline, "Baseline must not be null");
        Objects.requireNonNull(providerCall, "Provider call must not be null");

        ItemDescriptor.Builder isolated = ItemDescriptor.builder()
            .materialKey("kitsune:placeholder")
            .amount(1);
        providerCall.accept(isolated);
        ItemDescriptor contribution = isolated.buildBounded(256, 256);
        baseline.appendMissing(contribution);
    }
}
