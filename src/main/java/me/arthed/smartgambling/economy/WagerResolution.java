package me.arthed.smartgambling.economy;

import java.util.Objects;

public sealed interface WagerResolution
        permits WagerResolution.Loss, WagerResolution.Refund, WagerResolution.Payout {
    record Loss() implements WagerResolution {}
    record Refund() implements WagerResolution {}
    record Payout(Money amount) implements WagerResolution {
        public Payout {
            Objects.requireNonNull(amount, "amount");
        }
    }

    static Loss loss() {
        return new Loss();
    }

    static Refund refund() {
        return new Refund();
    }

    static Payout payout(Money amount) {
        return new Payout(amount);
    }
}
