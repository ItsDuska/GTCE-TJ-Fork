package gregtech.api.pipenet;


import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.api.pipenet.RoutePath;
import gregtech.common.pipelike.item.net.Bucket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;


public abstract class RoutedPipeNet<T> extends PipeNet<T>{

    private static final Comparator<Candidate<?>> DIJKSTRA_ORDER = Comparator.<Candidate<?>>comparingInt(c -> c.cost)
            .thenComparingInt(c -> c.hops)
            .thenComparingInt(c -> c.sequence);

    private final Map<BlockPos, Bucket> pipeBuckets = new HashMap<>();
    private final Map<BlockPos, List<RoutePath>> routeCache = new HashMap<>();


    protected RoutedPipeNet(WorldPipeNet<T, ? extends PipeNet<T>> world) {
        super(world);
    }

    protected abstract int getCost(Node<T> node);

    protected abstract int getLimit(Node<T> node);

    protected boolean isEntryBlocked(World world, BlockPos pos, EnumFacing travelDirection) {
        if (!world.isBlockLoaded(pos)) {
            return false;
        }

        TileEntity tile = world.getTileEntity(pos);
        return tile instanceof IPipeTile<?,?> pipe && pipe.isSideBlocked(travelDirection.getOpposite());
    }

    public void invalidateRoutes() {
        routeCache.clear();
    }

    public static void invalidateRoutesFor(IPipeTile<?,?> pipe) {
        World world = pipe.getPipeWorld();
        if (world == null || world.isRemote) {
            return;
        }

        PipeNet<?> net = pipe.getPipeBlock().getWorldPipeNet(world).getNetFromPos(pipe.getPipePos());
        if (net instanceof RoutedPipeNet<?> routed) {
            routed.invalidateRoutes();
        }
    }

    public Bucket getPipeBucket(BlockPos pipePos) {
        return pipeBuckets.computeIfAbsent(pipePos.toImmutable(), pos -> new Bucket());
    }

    protected List<RoutePath> getRoutes(BlockPos sourcePipePos) {
        return routeCache.computeIfAbsent(sourcePipePos.toImmutable(), this::computeRoutes);
    }

    private List<RoutePath> computeRoutes(BlockPos startPos) {
        Node<T> rootNode = getNodeAt(startPos);
        if (rootNode == null) {
            return Collections.emptyList();
        }

        World world = worldData.getWorld();
        List<RoutePath> routes = new ArrayList<>();
        Set<BlockPos> settled = new HashSet<>();
        PriorityQueue<Candidate<T>> queue = new PriorityQueue<>(DIJKSTRA_ORDER);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int sequence = 0;

        queue.add(new Candidate<>(startPos, rootNode, null, getCost(rootNode), 0, sequence++));

        while (!queue.isEmpty()) {
            Candidate<T> candidate = queue.poll();
            if (!settled.add(candidate.pos)) {
                continue;
            }

            RoutePath.Step step = new RoutePath.Step(getPipeBucket(candidate.pos), getLimit(candidate.node), candidate.parent);

            if (candidate.node.isActive) {
                routes.add(new RoutePath(candidate.pos, step));
            }

            for (EnumFacing face : EnumFacing.VALUES) {
                probe.setPos(candidate.pos).move(face);
                if (settled.contains(probe)) {
                    continue;
                }

                Node<T> next = getNodeAt(probe);
                if (next == null || !canNodesConnect(candidate.node, face, next, this)) {
                    continue;
                }

                if (isEntryBlocked(world, probe, face)) {
                    continue;
                }

                queue.add(new Candidate<>(probe.toImmutable(), next, step, candidate.cost + getCost(next), candidate.hops + 1, sequence++));
            }
        }

        return routes;
    }

    @Override
    protected void onConnectionsUpdate() {
        super.onConnectionsUpdate();
        invalidateRoutes();
    }

    @Override
    protected Node<T> removeNodeWithoutRebuilding(BlockPos nodePos) {
        pipeBuckets.remove(nodePos);
        invalidateRoutes();
        return super.removeNodeWithoutRebuilding(nodePos);
    }

    private static final class Candidate<T> {
        final BlockPos pos;
        final Node<T> node;
        final RoutePath.Step parent;
        final int cost;
        final int hops;
        final int sequence;

        Candidate(BlockPos pos, Node<T> node, RoutePath.Step parent, int cost, int hops, int sequence) {
            this.pos = pos;
            this.node = node;
            this.parent = parent;
            this.cost = cost;
            this.hops = hops;
            this.sequence = sequence;
        }
    }

}
