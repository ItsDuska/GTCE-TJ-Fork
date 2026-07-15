package gregtech.api.pipenet.block;

import gregtech.api.pipenet.tile.IPipeTile;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class ItemBlockPipe<PipeType extends Enum<PipeType> & IPipeType<NodeDataType>, NodeDataType> extends ItemBlock {

    protected final BlockPipe<PipeType, NodeDataType, ?> blockPipe;

    public ItemBlockPipe(BlockPipe<PipeType, NodeDataType, ?> block) {
        super(block);
        this.blockPipe = block;
        setHasSubtypes(true);
    }

    @Override
    public int getMetadata(int damage) {
        return damage;
    }


    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, BlockPos pos, EnumFacing side,
                                float hitX, float hitY, float hitZ, IBlockState newState) {
        boolean placed = super.placeBlockAt(stack, player, world, pos, side, hitX, hitY, hitZ, newState);
        if (placed && !world.isRemote) {
            BlockPos clickedPos = pos.offset(side.getOpposite());
            IPipeTile<PipeType, NodeDataType> placedPipe = blockPipe.getPipeTileEntity(world, pos);
            if (placedPipe != null) {
                IPipeTile<PipeType, NodeDataType> clickedPipe = blockPipe.getPipeTileEntity(world, clickedPos);
                if (clickedPipe != null) {
                    placedPipe.setConnectionForced(side.getOpposite(), true);
                    clickedPipe.setConnectionForced(side, true);
                } else if (world.getTileEntity(clickedPos) != null) {
                    int activeMask = blockPipe.getActiveNodeConnections(world, pos, placedPipe);
                    if ((activeMask & (1 << side.getOpposite().getIndex())) != 0) {
                        placedPipe.setConnectionForced(side.getOpposite(), true);
                    }
                }
            }
        }
        return placed;
    }
}
