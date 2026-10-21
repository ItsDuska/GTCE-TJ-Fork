package gregtech.common.pipelike.item.net;

import net.minecraft.util.math.BlockPos;

public class ItemRoutePath {
    public final BlockPos destination;
    private final Step last;

    public ItemRoutePath(BlockPos destination, Step last) {
        this.destination = destination;
        this.last = last;
    }

    public int remainingCapacity(long now) {
        int remaining = Integer.MAX_VALUE;
        for (Step step = last; step != null; step = step.parent) {
            remaining = Math.min(remaining, step.bucket.available(now, step.itemsPerSecond));
            if (remaining <= 0) {
                return 0;
            }
        }

        return remaining;
    }

    public void commit(long now, int amount) {
        for (Step step = last; step != null; step = step.parent) {
            step.bucket.consume(now, amount);
        }
    }

    static final class Step {
        final Bucket bucket;
        final int itemsPerSecond;
        final Step parent;

        Step(Bucket bucket, int itemsPerSecond, Step parent) {
            this.bucket = bucket;
            this.itemsPerSecond = itemsPerSecond;
            this.parent = parent;
        }
    }
}
