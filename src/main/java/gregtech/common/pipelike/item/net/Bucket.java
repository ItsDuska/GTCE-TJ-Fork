package gregtech.common.pipelike.item.net;

public class Bucket {
    private static final int TICKS_PER_SECOND = 20;

    private long window = Long.MIN_VALUE;
    private int used;

    public int available(long now, int itemsPerSecond) {
        rollWindow(now);
        return Math.max(0, itemsPerSecond - used);
    }

    public void consume(long now, int amount) {
        rollWindow(now);
        used += amount;
    }

    private void rollWindow(long now) {
        long current = now / TICKS_PER_SECOND;
        if (current == window) {
            return;
        }

        window = current;
        used = 0;
    }
}
