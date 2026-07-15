package gregtech.api.pipenet.block;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.raytracer.IndexedCuboid6;
import codechicken.lib.raytracer.RayTracer;
import codechicken.lib.vec.Cuboid6;
import gregtech.api.GregTechAPI;
import gregtech.api.block.BuiltInRenderBlock;
import gregtech.api.capability.GregtechCapabilities;
import gregtech.api.capability.tool.IDamagableToolItem;
import gregtech.api.capability.tool.IScrewdriverItem;
import gregtech.api.cover.CoverBehavior;
import gregtech.api.cover.ICoverable;
import gregtech.api.cover.ICoverable.CoverSideData;
import gregtech.api.cover.ICoverable.PrimaryBoxData;
import gregtech.api.cover.IFacadeCover;
import gregtech.api.pipenet.PipeNet;
import gregtech.api.pipenet.WorldPipeNet;
import gregtech.api.pipenet.tile.AttachmentType;
import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.api.pipenet.tile.TileEntityPipeBase;
import gregtech.api.util.GTUtility;
import gregtech.common.sound.GTSoundEvents;
import gregtech.common.tools.DamageValues;
import gregtech.api.render.IBlockAppearance;
import gregtech.integration.ctm.IFacadeWrapper;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

@SuppressWarnings("deprecation")
public abstract class BlockPipe<PipeType extends Enum<PipeType> & IPipeType<NodeDataType>, NodeDataType, WorldPipeNetType extends WorldPipeNet<NodeDataType, ? extends PipeNet<NodeDataType>>> extends BuiltInRenderBlock implements ITileEntityProvider, IFacadeWrapper, IBlockAppearance {

    public BlockPipe() {
        super(net.minecraft.block.material.Material.IRON);
        setTranslationKey("pipe");
        setCreativeTab(GregTechAPI.TAB_GREGTECH);
        setSoundType(SoundType.METAL);
        setHardness(2.0f);
        setResistance(3.0f);
        setLightOpacity(0);
        disableStats();
    }

    public abstract Class<PipeType> getPipeTypeClass();

    public abstract WorldPipeNetType getWorldPipeNet(World world);

    public abstract int getActiveNodeConnections(IBlockAccess world, BlockPos nodePos, IPipeTile<PipeType, NodeDataType> selfTileEntity);

    public abstract TileEntityPipeBase<PipeType, NodeDataType> createNewTileEntity(boolean supportsTicking);

    public abstract NodeDataType createProperties(IPipeTile<PipeType, NodeDataType> pipeTile);

    public abstract NodeDataType createItemProperties(ItemStack itemStack);

    public abstract ItemStack getDropItem(IPipeTile<PipeType, NodeDataType> pipeTile);

    protected abstract NodeDataType getFallbackType();

    public abstract PipeType getItemPipeType(ItemStack itemStack);

    public abstract void setTileEntityData(TileEntityPipeBase<PipeType, NodeDataType> pipeTile, ItemStack itemStack);

    @Override
    public abstract void getSubBlocks(CreativeTabs itemIn, NonNullList<ItemStack> items);

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(worldIn, pos);
        if (pipeTile != null) {
            pipeTile.getCoverableImplementation().dropAllCovers();

            tileEntities.set(pipeTile);
        }
        super.breakBlock(worldIn, pos, state);
        getWorldPipeNet(worldIn).removeNode(pos);
    }

    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        worldIn.scheduleUpdate(pos, this, 1);
    }

    @Override
    public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand) {
       refreshNodeConnectivity(worldIn,pos);
    }


    @Override
    public void onBlockPlacedBy(World worldIn, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(worldIn, pos);
        if (pipeTile != null) {
            setTileEntityData((TileEntityPipeBase<PipeType, NodeDataType>) pipeTile, stack);
        }
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(worldIn, pos);
        if (pipeTile == null && worldIn.isRemote) {
            return;
        }
        updateActiveNodeStatus(worldIn, pos, pipeTile);
        pipeTile.getCoverableImplementation().updateInputRedstoneSignals();

        EnumFacing facing = getFacingFromOffset(pos, fromPos);
        if (facing != null && pipeTile.isConnectionExtended(facing) && !pipeTile.isConnectionForced(facing)) {
            int activeMask = getActiveNodeConnections(worldIn, pos, pipeTile);
            if ((activeMask & (1 << facing.getIndex())) != 0) {
                pipeTile.setConnectionForced(facing, true);
                pipeTile.setConnectionExtended(facing, false);
            }
        }
    }

    private EnumFacing getFacingFromOffset(BlockPos pos, BlockPos fromPos) {
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (pos.offset(facing).equals(fromPos)) {
                return facing;
            }
        }
        return null;
    }

    @Override
    public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos, @Nullable EnumFacing side) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(world, pos);
        return pipeTile != null && pipeTile.getCoverableImplementation().canConnectRedstone(side);
    }

    @Override
    public boolean shouldCheckWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        // The check in World::getRedstonePower in the vanilla code base is reversed. Setting this to false will
        // actually cause getWeakPower to be called, rather than prevent it.
        return false;
    }

    @Override
    public int getWeakPower(IBlockState blockState, IBlockAccess blockAccess, BlockPos pos, EnumFacing side) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(blockAccess, pos);
        return pipeTile == null ? 0 : pipeTile.getCoverableImplementation().getOutputRedstoneSignal(side.getOpposite());
    }

    public void updateActiveNodeStatus(World worldIn, BlockPos pos, IPipeTile<PipeType, NodeDataType> pipeTile) {
        PipeNet<NodeDataType> pipeNet = getWorldPipeNet(worldIn).getNetFromPos(pos);
        if (pipeNet != null && pipeTile != null) {
            int activeConnections = getActiveNodeConnections(worldIn, pos, pipeTile);
            activeConnections &= ~pipeTile.getBlockedConnections(); //remove blocked connections
            boolean isActiveNodeNow = activeConnections > 0;
            boolean modeChanged = pipeNet.markNodeAsActive(pos, isActiveNodeNow);
            if (modeChanged) {
                onActiveModeChange(worldIn, pos, isActiveNodeNow, false);
            }
        }
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return createNewTileEntity(false);
    }

    /**
     * Can be used to update tile entity to tickable when node becomes active
     * usable for fluid pipes, as example
     */
    protected void onActiveModeChange(World world, BlockPos pos, boolean isActiveNow, boolean isInitialChange) {
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(world, pos);
        if (pipeTile == null) {
            return ItemStack.EMPTY;
        }
        if (target instanceof CuboidRayTraceResult) {
            CuboidRayTraceResult result = (CuboidRayTraceResult) target;
            if (result.cuboid6.data instanceof CoverSideData) {
                EnumFacing coverSide = ((CoverSideData) result.cuboid6.data).side;
                CoverBehavior coverBehavior = pipeTile.getCoverableImplementation().getCoverAtSide(coverSide);
                return coverBehavior == null ? ItemStack.EMPTY : coverBehavior.getPickItem();
            }
        }
        return getDropItem(pipeTile);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(worldIn, pos);
        if (pipeTile == null) {
            return false;
        }

        ItemStack heldItem = playerIn.getHeldItem(hand);
        if (heldItem.hasCapability(getConnectionToggleCapability(), null)) {
            EnumFacing gridSide = GTUtility.determineWrenchingSide(facing, hitX, hitY, hitZ);
            if (gridSide != null) {
                return handleConnectionToolInteraction(pipeTile, gridSide, playerIn, hand);
            }
        }

        CuboidRayTraceResult rayTraceResult = (CuboidRayTraceResult) RayTracer.retraceBlock(worldIn, playerIn, pos);
        if (rayTraceResult == null) {
            return false;
        }
        return onPipeActivated(playerIn, hand, rayTraceResult, pipeTile);
    }

    public boolean onPipeActivated(EntityPlayer entityPlayer, EnumHand hand, CuboidRayTraceResult hit, IPipeTile<PipeType, NodeDataType> pipeTile) {
        EnumFacing coverSide = ICoverable.traceCoverSide(hit);
        if (coverSide == null) return false;

        CoverBehavior coverBehavior = pipeTile.getCoverableImplementation().getCoverAtSide(coverSide);
        if (coverBehavior == null) return false;

        ItemStack itemStack = entityPlayer.getHeldItem(hand);
        IScrewdriverItem screwdriver = itemStack.getCapability(GregtechCapabilities.CAPABILITY_SCREWDRIVER, null);
        if (screwdriver != null) {
            if (screwdriver.damageItem(DamageValues.DAMAGE_FOR_SCREWDRIVER, true) && coverBehavior.onScrewdriverClick(entityPlayer, hand, hit) == EnumActionResult.SUCCESS) {
                screwdriver.damageItem(DamageValues.DAMAGE_FOR_SCREWDRIVER, false);
                return true;
            }
            return false;
        }
        return coverBehavior.onRightClick(entityPlayer, hand, hit) == EnumActionResult.SUCCESS;
    }

    protected boolean handleConnectionToolInteraction(IPipeTile<PipeType, NodeDataType> pipeTile, EnumFacing side, EntityPlayer player, EnumHand hand) {
        ItemStack itemStack = player.getHeldItem(hand);
        IDamagableToolItem tool = itemStack.getCapability(getConnectionToggleCapability(), null);

        if (tool == null) {
            return false;
        }

        int damage = getConnectionToggleDamage();

        if (onToolClickSide(pipeTile, side, tool, damage, player)) {
            return true;
        }

        if (!tool.damageItem(damage, true)) {
            return false;
        }


        if (!player.world.isRemote) {
            toggleConnection(pipeTile, side);

            tool.damageItem(damage, false);
            player.getEntityWorld().playSound(null, player.getPosition(), getConnectionToggleSound(), SoundCategory.PLAYERS, 1f, 1f);
        }

        return true;
    }

    private void toggleConnection(IPipeTile<PipeType, NodeDataType> pipeTile, EnumFacing side) {
        IPipeTile<PipeType, NodeDataType> neighbor = getPipeTileEntity(pipeTile.getPipeWorld(), pipeTile.getPipePos().offset(side));

        boolean selfBlocked = pipeTile.isConnectionBlocked(AttachmentType.PIPE, side);
        boolean neighborBlocked = neighbor != null && neighbor.isConnectionBlocked(AttachmentType.PIPE, side.getOpposite());

        if (selfBlocked || neighborBlocked) {
            if (selfBlocked) {
                pipeTile.setConnectionBlocked(AttachmentType.PIPE, side, false);
            }
            if (neighborBlocked) {
                neighbor.setConnectionBlocked(AttachmentType.PIPE, side.getOpposite(), false);
            }
            return;
        }

        pipeTile.setConnectionBlocked(AttachmentType.PIPE, side, true);
    }

    protected boolean onToolClickSide(IPipeTile<PipeType, NodeDataType> selfTile, EnumFacing side, IDamagableToolItem tool, int damage, EntityPlayer player) {
        IPipeTile<PipeType, NodeDataType> sideTile = getPipeTileEntity(selfTile.getPipeWorld(), selfTile.getPipePos().offset(side));
        if (sideTile == null) {
            if (!tool.damageItem(damage, true)){
                return false;
            }

            boolean hasRealConnection = (getActiveNodeConnections(selfTile.getPipeWorld(), selfTile.getPipePos(), selfTile) & (1 << side.getIndex())) != 0;

            if (!player.world.isRemote) {
                if (hasRealConnection) {
                    boolean nowForced = !selfTile.isConnectionForced(side);
                    selfTile.setConnectionForced(side, nowForced);
                } else {
                    boolean nowExtended = !selfTile.isConnectionExtended(side);
                    selfTile.setConnectionExtended(side, nowExtended);
                }
                tool.damageItem(damage, false);
                player.getEntityWorld().playSound(null, player.getPosition(), getConnectionToggleSound(), SoundCategory.PLAYERS, 1f, 1f);
            }
            return true;
        }

        if (!tool.damageItem(damage, true)) {
            return false;
        }


        if (!player.world.isRemote) {
            World world = selfTile.getPipeWorld();
            BlockPos selfPos = selfTile.getPipePos();
            BlockPos sidePos = sideTile.getPipePos();

            boolean nowForced = !(selfTile.isConnectionForced(side) || sideTile.isConnectionForced(side.getOpposite()));

            selfTile.setConnectionForced(side, nowForced);
            IPipeTile<PipeType, NodeDataType> freshSelf = getPipeTileEntity(world, selfPos);
            if (freshSelf != null) {
                freshSelf.setConnectionBlocked(AttachmentType.PIPE, side, false);
            }

            IPipeTile<PipeType, NodeDataType> freshSide = getPipeTileEntity(world, sidePos);
            if (freshSide != null) {
                freshSide.setConnectionForced(side.getOpposite(), nowForced);
                freshSide.setConnectionBlocked(AttachmentType.PIPE, side.getOpposite(), false);
            }

            tool.damageItem(damage, false);
            player.getEntityWorld().playSound(null, player.getPosition(), getConnectionToggleSound(), SoundCategory.PLAYERS, 1f, 1f);
        }
        return true;
    }

    public Capability<? extends IDamagableToolItem> getConnectionToggleCapability() {
        return GregtechCapabilities.CAPABILITY_WRENCH;
    }

    protected int getConnectionToggleDamage() {
        return DamageValues.DAMAGE_FOR_WRENCH;
    }

    public void refreshNodeConnectivity(World world, BlockPos pos) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(world, pos);
        if (pipeTile == null || ((TileEntityPipeBase<?, ?>) pipeTile).wasInDetachedConversionMode()) {
            return;
        }
        int activeConnections = getActiveNodeConnections(world, pos, pipeTile);
        activeConnections &= ~pipeTile.getBlockedConnections();
        boolean isActiveNode = activeConnections > 0;
        getWorldPipeNet(world).addNode(pos, createProperties(pipeTile), 0, pipeTile.getBlockedConnections(), getForcedConnectionsMask(pipeTile), isActiveNode);


        if (isActiveNode != pipeTile.supportsTicking()) {
            onActiveModeChange(world, pos, isActiveNode, true);
        }
    }

    public int getForcedConnectionsMask(IPipeTile<PipeType, NodeDataType> pipeTile) {
        int mask = 0;
        for (EnumFacing side : EnumFacing.VALUES) {
            if (pipeTile.isConnectionForced(side)) {
                mask |= 1 << side.getIndex();
            }
        }
        return mask;
    }


    @Override
    public void onBlockClicked(World worldIn, BlockPos pos, EntityPlayer playerIn) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(worldIn, pos);
        CuboidRayTraceResult rayTraceResult = (CuboidRayTraceResult) RayTracer.retraceBlock(worldIn, playerIn, pos);
        if (pipeTile == null || rayTraceResult == null) {
            return;
        }
        EnumFacing coverSide = ICoverable.traceCoverSide(rayTraceResult);
        CoverBehavior coverBehavior = coverSide == null ? null : pipeTile.getCoverableImplementation().getCoverAtSide(coverSide);

        if (coverBehavior != null) {
            coverBehavior.onLeftClick(playerIn, rayTraceResult);
        }
    }

    protected ThreadLocal<IPipeTile<PipeType, NodeDataType>> tileEntities = new ThreadLocal<>();

    @SuppressWarnings("unchecked")
    @Override
    public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos, IBlockState state, @Nullable TileEntity te, ItemStack stack) {
        tileEntities.set(te == null ? tileEntities.get() : (IPipeTile<PipeType, NodeDataType>) te);
        super.harvestBlock(worldIn, player, pos, state, te, stack);
        tileEntities.set(null);
    }

    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        IPipeTile<PipeType, NodeDataType> pipeTile = tileEntities.get() == null ? getPipeTileEntity(world, pos) : tileEntities.get();
        if (pipeTile == null) return;
        drops.add(getDropItem(pipeTile));
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean isActualState) {
        for (Cuboid6 axisAlignedBB : getCollisionBox(worldIn, pos)) {
            AxisAlignedBB offsetBox = axisAlignedBB.aabb().offset(pos);
            if (offsetBox.intersects(entityBox)) collidingBoxes.add(offsetBox);
        }
    }

    @Nullable
    @Override
    public RayTraceResult collisionRayTrace(IBlockState blockState, World worldIn, BlockPos pos, Vec3d start, Vec3d end) {
        IndexedCuboid6 gridBox = new IndexedCuboid6(new GridInteractionData(), new Cuboid6(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        return RayTracer.rayTraceCuboidsClosest(start, end, pos, Collections.singletonList(gridBox));
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess worldIn, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Override
    public boolean recolorBlock(World world, BlockPos pos, EnumFacing side, EnumDyeColor color) {
        IPipeTile<PipeType, NodeDataType> tileEntityPipe = (IPipeTile<PipeType, NodeDataType>) world.getTileEntity(pos);
        if (tileEntityPipe != null && tileEntityPipe.getPipeType() != null &&
            tileEntityPipe.getPipeType().isPaintable() &&
            tileEntityPipe.getInsulationColor() != color.colorValue) {
            tileEntityPipe.setInsulationColor(color.colorValue);
            return true;
        }
        return false;
    }

    protected boolean isThisPipeBlock(Block block) {
        return block.getClass().isAssignableFrom(getClass());
    }

    /**
     * Just returns proper pipe tile entity
     */
    public IPipeTile<PipeType, NodeDataType> getPipeTileEntity(IBlockAccess world, BlockPos selfPos) {
        TileEntity tileEntityAtPos = world.getTileEntity(selfPos);
        return getPipeTileEntity(tileEntityAtPos);
    }

    public IPipeTile<PipeType, NodeDataType> getPipeTileEntity(TileEntity tileEntityAtPos) {
        if (tileEntityAtPos instanceof IPipeTile && isThisPipeBlock(((IPipeTile) tileEntityAtPos).getPipeBlock())) {
            return (IPipeTile<PipeType, NodeDataType>) tileEntityAtPos;
        }
        return null;
    }

    /**
     * Tests whatever pipe at given position can connect to a pipe from fromFacing face with fromColor color
     * Used purely for rendering purposes
     *
     * @return 0 - not a pipe; 1 - pipe but blocked; 2,3 - accessible
     */
    protected final int isPipeAccessibleAtSide(IBlockAccess world, IPipeTile<PipeType, NodeDataType> selfTile, EnumFacing side) {
        IPipeTile<PipeType, NodeDataType> tileEntityPipe = getPipeTileEntity(world, selfTile.getPipePos().offset(side));
        return isPipeAccessibleAtSideInternal(selfTile, tileEntityPipe, side);
    }

    protected final int isPipeAccessibleAtSideInternal(IPipeTile<PipeType, NodeDataType> selfTile, IPipeTile<PipeType, NodeDataType> tileEntityPipe, EnumFacing side) {
        if (tileEntityPipe == null) {
            return 0; //not a cable pipe entity
        }
        if ((tileEntityPipe.getBlockedConnections() & 1 << side.getOpposite().getIndex()) > 0 ||
            (selfTile.getBlockedConnections() & 1 << side.getIndex()) > 0) {
            return 1; //connection is blocked on this facing
        }

        int insulationColor = selfTile.getInsulationColor();
        if (insulationColor != IPipeTile.DEFAULT_INSULATION_COLOR &&
            tileEntityPipe.getInsulationColor() != IPipeTile.DEFAULT_INSULATION_COLOR &&
            insulationColor != tileEntityPipe.getInsulationColor()) {
            return 1; //color doesn't match; unable to connect
        }

        if (!canPipesConnect(selfTile, side, tileEntityPipe)) {
            return 1; //custom connection predicate didn't match
        }
        PipeType otherPipeType = tileEntityPipe.getPipeType();
        PipeType myPipeType = selfTile.getPipeType();
        if (otherPipeType == null || myPipeType == null) {
            return 0;
        }
        return myPipeType.getThickness() > otherPipeType.getThickness() ? 3 : 2;
    }

    protected boolean canPipesConnect(IPipeTile<PipeType, NodeDataType> selfTile, EnumFacing side, IPipeTile<PipeType, NodeDataType> sideTile) {
        return selfTile.isConnectionForced(side) || sideTile.isConnectionForced(side.getOpposite());
    }

    /**
     * Returns bit mask of actual cable connections, including cable-cable and cable-receiver
     * connections. but excluding unaccessible covers on blocked sides
     */
    public int getActualConnections(IPipeTile<PipeType, NodeDataType> selfTile, IBlockAccess world) {
        int connectedSidesMask = 0;
        int activeNodeConnections = getActiveVisualConnections(selfTile);
        for (EnumFacing side : EnumFacing.VALUES) {
            CoverBehavior coverBehavior = selfTile.getCoverableImplementation().getCoverAtSide(side);
            if (coverBehavior != null && coverBehavior.shouldRenderConnected()) {
                connectedSidesMask |= 1 << side.getIndex();
            }
            if ((selfTile.getBlockedConnections() & (1 << side.getIndex())) > 0)
                continue;
            int cableState = isPipeAccessibleAtSide(world, selfTile, side);
            if (cableState >= 2) {
                connectedSidesMask |= 1 << side.getIndex();
                if (cableState >= 3) {
                    connectedSidesMask |= 1 << (6 + side.getIndex());
                }
            } else if ((selfTile.isConnectionForced(side) && (activeNodeConnections & 1 << side.getIndex()) > 0)) {
                connectedSidesMask |= 1 << side.getIndex();
                connectedSidesMask |= 1 << (6 + side.getIndex());
            } else if (selfTile.isConnectionExtended(side)) {
                connectedSidesMask |= 1 << side.getIndex();
                connectedSidesMask |= 1 << (6 + side.getIndex());
            }
        }

        return connectedSidesMask;
    }

    protected int getActiveVisualConnections(IPipeTile<PipeType, NodeDataType> selfTile) {
        return getActiveNodeConnections(selfTile.getPipeWorld(), selfTile.getPipePos(), selfTile);
    }

    private List<IndexedCuboid6> getCollisionBox(IBlockAccess world, BlockPos pos) {
        IPipeTile<PipeType, NodeDataType> pipeTile = getPipeTileEntity(world, pos);
        if (pipeTile == null) {
            return Collections.emptyList();
        }
        PipeType pipeType = pipeTile.getPipeType();
        if (pipeType == null) {
            return Collections.emptyList();
        }
        int actualConnections = getActualConnections(pipeTile, world);
        float thickness = pipeType.getThickness();
        ArrayList<IndexedCuboid6> result = new ArrayList<>();
        result.add(new IndexedCuboid6(new PrimaryBoxData(false), getSideBox(null, thickness)));
        ICoverable coverable = pipeTile.getCoverableImplementation();
        for (EnumFacing side : EnumFacing.VALUES) {
            if ((actualConnections & 1 << side.getIndex()) > 0) {
                result.add(new IndexedCuboid6(new PipeConnectionData(side), getSideBox(side, thickness)));
            }
        }
        coverable.addCoverCollisionBoundingBox(result);
        return result;
    }

    @Override
    public boolean canRenderInLayer(IBlockState state, BlockRenderLayer layer) {
        return true;
    }

    @Nonnull
    @Override
    public IBlockState getFacade(@Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nullable EnumFacing side, @Nonnull BlockPos otherPos) {
        return getFacade(world, pos, side);
    }

    @Nonnull
    @Override
    public IBlockState getFacade(@Nonnull IBlockAccess world, @Nonnull BlockPos pos, EnumFacing side) {
        IPipeTile<?, ?> pipeTileEntity = getPipeTileEntity(world, pos);
        if (pipeTileEntity != null && side != null) {
            CoverBehavior coverBehavior = pipeTileEntity.getCoverableImplementation().getCoverAtSide(side);
            if (coverBehavior instanceof IFacadeCover) {
                return ((IFacadeCover) coverBehavior).getVisualState();
            }
        }
        return world.getBlockState(pos);
    }

    @Nonnull
    @Override
    public IBlockState getVisualState(@Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nonnull EnumFacing side) {
        return getFacade(world, pos, side);
    }

    @Override
    public boolean supportsVisualConnections() {
        return true;
    }

    public static class PipeConnectionData {
        public final EnumFacing side;

        public PipeConnectionData(EnumFacing side) {
            this.side = side;
        }
    }

    public static class GridInteractionData {
    }

    public static Cuboid6 getSideBox(EnumFacing side, float thickness) {
        float min = (1.0f - thickness) / 2.0f;
        float max = min + thickness;
        if (side == null) {
            return new Cuboid6(min, min, min, max, max, max);
        } else if (side == EnumFacing.DOWN) {
            return new Cuboid6(min, 0.0f, min, max, min, max);
        } else if (side == EnumFacing.UP) {
            return new Cuboid6(min, max, min, max, 1.0f, max);
        } else if (side == EnumFacing.WEST) {
            return new Cuboid6(0.0f, min, min, min, max, max);
        } else if (side == EnumFacing.EAST) {
            return new Cuboid6(max, min, min, 1.0f, max, max);
        } else if (side == EnumFacing.NORTH) {
            return new Cuboid6(min, min, 0.0f, max, max, min);
        } else if (side == EnumFacing.SOUTH) {
            return new Cuboid6(min, min, max, max, max, 1.0f);
        } else throw new IllegalArgumentException(side.toString());
    }

    protected SoundEvent getConnectionToggleSound() {
        return GTSoundEvents.WRENCH;
    }
}
