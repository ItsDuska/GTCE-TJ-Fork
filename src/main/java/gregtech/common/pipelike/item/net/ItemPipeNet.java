package gregtech.common.pipelike.item.net;

import gregtech.api.pipenet.Node;
import gregtech.api.pipenet.PipeNet;
import gregtech.api.pipenet.WorldPipeNet;
import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.common.pipelike.item.ItemPipeProperties;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.*;

public class ItemPipeNet extends PipeNet<ItemPipeProperties> {
    private static final Comparator<Candidate> DIJKSTRA_ORDER = Comparator.<Candidate>comparingInt(c -> c.cost)
            .thenComparingInt(c -> c.hops)
            .thenComparingInt(c -> c.sequence);

    private final Map<BlockPos, Bucket> pipeBuckets = new HashMap<>();
    private final Map<BlockPos, List<ItemRoutePath>> routeCache = new HashMap<>();

    public ItemPipeNet(WorldPipeNet<ItemPipeProperties, ItemPipeNet> world) {
        super(world);
    }

    public ItemStack insert(BlockPos sourcePipePos, @Nullable EnumFacing sourceSide, ItemStack stack, boolean simulate) {
        Node<ItemPipeProperties> sourceNode = getNodeAt(sourcePipePos);
        if (stack.isEmpty() || sourceNode == null) {
            return stack;
        }

        World world = worldData.getWorld();
        long now = world.getTotalWorldTime();
        Bucket sourceBucket = getPipeBucket(sourcePipePos);
        int sourceRate = sourceNode.data.getItemsPerSecond();
        BlockPos sourceInvPos = sourceSide == null ? null : sourcePipePos.offset(sourceSide);

        Set<BlockPos> offered = simulate ? new HashSet<>() : null;
        List<Runnable> undo = new ArrayList<>();

        ItemStack remaining = stack.copy();
        for (ItemRoutePath route : getRoutes(sourcePipePos)) {
            if (remaining.isEmpty() || sourceBucket.available(now, sourceRate) <= 0) {
                break;
            }

            int toSend = Math.min(route.remainingCapacity(now), remaining.getCount());

            if (toSend <= 0) {
                continue;
            }

            ItemStack attempt = ItemHandlerHelper.copyStackWithSize(remaining, toSend);
            ItemStack leftover = insertAtDestination(world, route.destination, sourceInvPos, attempt, simulate, offered);

            int inserted = toSend - leftover.getCount();
            if (inserted <= 0) {
                continue;
            }

            remaining.shrink(inserted);
            route.commit(now, inserted);
            if (simulate) {
                undo.add(() -> route.commit(now, -inserted));
            }
        }

        undo.forEach(Runnable::run);

        return remaining.isEmpty() ? ItemStack.EMPTY : remaining;
    }

    private ItemStack insertAtDestination(World world, BlockPos pipePos, @Nullable BlockPos sourceInvPos, ItemStack stack, boolean simulate, @Nullable Set<BlockPos> offered) {
        if (!world.isBlockLoaded(pipePos)) {
            return stack;
        }

        TileEntity pipeTile = world.getTileEntity(pipePos);
        if (!(pipeTile instanceof IPipeTile<?, ?> pipe)) {
            return stack;
        }

        ItemStack remaining = stack;
        for (EnumFacing side : EnumFacing.VALUES) {
            if (remaining.isEmpty()) {
                break;
            }

            if (!pipe.isConnected(side)) {
                continue;
            }

            BlockPos inventoryPos = pipePos.offset(side);
            if (inventoryPos.equals(sourceInvPos) || !world.isBlockLoaded(inventoryPos)) {
                continue;
            }

            if (offered != null && !offered.add(inventoryPos)) {
                continue;
            }

            TileEntity inventory = world.getTileEntity(inventoryPos);
            if (inventory == null || inventory instanceof IPipeTile) {
                continue;
            }

            IItemHandler handler = inventory.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, side.getOpposite());
            if (handler != null) {
                remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, simulate);
            }
        }

        return remaining;
    }

    private List<ItemRoutePath> getRoutes(BlockPos sourcePipePos) {
        return routeCache.computeIfAbsent(sourcePipePos.toImmutable(), this::computeRoutes);
    }

    private List<ItemRoutePath> computeRoutes(BlockPos startPos) {
        Node<ItemPipeProperties> rootNode = getNodeAt(startPos);
        if (rootNode == null) {
            return Collections.emptyList();
        }

        List<ItemRoutePath> routes = new ArrayList<>();
        Set<BlockPos> settled = new HashSet<>();
        PriorityQueue<Candidate> queue = new PriorityQueue<>(DIJKSTRA_ORDER);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int sequence = 0;

        queue.add(new Candidate(startPos, rootNode, null, cost(rootNode), 0, sequence++));

        while (!queue.isEmpty()) {
            Candidate candidate = queue.poll();
            if (!settled.add(candidate.pos)) {
                continue;
            }

            ItemRoutePath.Step step = new ItemRoutePath.Step(getPipeBucket(candidate.pos), candidate.node.data.getItemsPerSecond(), candidate.parent);

            if (candidate.node.isActive) {
                routes.add(new ItemRoutePath(candidate.pos, step));
            }

            for (EnumFacing face : EnumFacing.VALUES) {
                probe.setPos(candidate.pos).move(face);
                if (settled.contains(probe)) {
                    continue;
                }

                Node<ItemPipeProperties> next = getNodeAt(probe);
                if (next == null || !canNodesConnect(candidate.node, face, next, this)) {
                    continue;
                }

                queue.add(new Candidate(probe.toImmutable(), next, step, candidate.cost + cost(next), candidate.hops + 1, sequence++));
            }
        }

        return routes;
    }

    private static int cost(Node<ItemPipeProperties> node) {
        return Math.max(1, node.data.getPriority());
    }

    public Bucket getPipeBucket(BlockPos pipePos) {
        return pipeBuckets.computeIfAbsent(pipePos.toImmutable(), pos -> new Bucket());
    }

    public void invalidateRoutes() {
        routeCache.clear();
    }

    @Override
    protected void onConnectionsUpdate() {
        super.onConnectionsUpdate();
        invalidateRoutes();
    }

    @Override
    protected Node<ItemPipeProperties> removeNodeWithoutRebuilding(BlockPos nodePos) {
        pipeBuckets.remove(nodePos);
        invalidateRoutes();
        return super.removeNodeWithoutRebuilding(nodePos);
    }

    @Override
    protected void writeNodeData(ItemPipeProperties nodeData, NBTTagCompound tagCompound) {
        tagCompound.setInteger("priority", nodeData.getPriority());
        tagCompound.setFloat("rate", nodeData.getTransferRate());
    }

    @Override
    protected ItemPipeProperties readNodeData(NBTTagCompound tagCompound) {
        int priority = tagCompound.getInteger("priority");
        float rate = tagCompound.getFloat("rate");
        return new ItemPipeProperties(priority, rate);
    }


    private static final class Candidate {
        final BlockPos pos;
        final Node<ItemPipeProperties> node;
        final ItemRoutePath.Step parent;
        final int cost;
        final int hops;
        final int sequence;

        Candidate(BlockPos pos, Node<ItemPipeProperties> node, ItemRoutePath.Step parent, int cost, int hops, int sequence) {
            this.pos = pos;
            this.node = node;
            this.parent = parent;
            this.cost = cost;
            this.hops = hops;
            this.sequence = sequence;
        }
    }
}
