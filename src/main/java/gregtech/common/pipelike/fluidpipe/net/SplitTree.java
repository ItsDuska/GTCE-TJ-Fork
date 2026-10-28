package gregtech.common.pipelike.fluidpipe.net;

import gregtech.api.pipenet.Bucket;
import gregtech.api.pipenet.RoutePath;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public final class SplitTree {
    private static final SplitTree EMPTY = new SplitTree(-1, new BlockPos[0], new Bucket[0], new int[0],
            new int[0], new int[0], new boolean[0]);

    private final int root;
    private final BlockPos[] positions;
    private final Bucket[] buckets;
    private final int[] limits;

    private final int[] firstChild;
    private final int[] nextSibling;
    private final boolean[] destination;

    private SplitTree(int root, BlockPos[] positions, Bucket[] buckets, int[] limits, int[] firstChild, int[] nextSibling, boolean[] destination) {
        this.root = root;
        this.positions = positions;
        this.buckets = buckets;
        this.limits = limits;
        this.firstChild = firstChild;
        this.nextSibling = nextSibling;
        this.destination = destination;
    }

    public boolean isEmpty() {
        return root < 0;
    }

    public int root() {
        return root;
    }

    public BlockPos pos(int node) {
        return positions[node];
    }

    boolean isDestination(int node) {
        return destination[node];
    }


    public int firstChild(int node) {
        return firstChild[node];
    }

    public int nextSibling(int node) {
        return nextSibling[node];
    }

    public int available(int node, long now) {
        return buckets[node].available(now, limits[node]);
    }

    public void consume(int node, long now, int amount) {
        buckets[node].consume(now, amount);
    }

    static SplitTree build(List<RoutePath> routes) {
        if (routes.isEmpty()) {
            return EMPTY;
        }

        Map<RoutePath.Step, Integer> indices = new IdentityHashMap<>();
        List<RoutePath.Step> steps = new ArrayList<>();
        for (RoutePath route : routes) {
            for (RoutePath.Step step = route.last(); step != null && !indices.containsKey(step); step = step.parent) {
                indices.put(step, steps.size());
                steps.add(step);
            }
        }

        int size = steps.size();
        BlockPos[] positions = new BlockPos[size];
        Bucket[] buckets = new Bucket[size];
        int[] limits = new int[size];
        int[] firstChild = new int[size];
        int[] nextSibling = new int[size];
        int[] lastChild = new int[size];
        boolean[] destination = new boolean[size];
        Arrays.fill(firstChild, -1);
        Arrays.fill(nextSibling, -1);
        Arrays.fill(lastChild, -1);

        int root = -1;
        for (int i = 0; i < size; i++) {
            RoutePath.Step step = steps.get(i);
            positions[i] = step.pos;
            buckets[i] = step.bucket;
            limits[i] = step.limit;

            if (step.parent == null) {
                root = i;
                continue;
            }

            int parent = indices.get(step.parent);
            if (firstChild[parent] < 0) {
                firstChild[parent] = i;
            } else {
                nextSibling[lastChild[parent]] = i;
            }

            lastChild[parent] = i;
        }

        for (RoutePath route : routes) {
            destination[indices.get(route.last())] = true;
        }

        return new SplitTree(root, positions, buckets, limits, firstChild, nextSibling, destination);
    }

}
