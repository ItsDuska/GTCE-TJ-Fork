package gregtech.api.pipenet.block;

import gregtech.api.pipenet.tile.AttachmentType;
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
            IPipeTile<PipeType, NodeDataType> placedPipe = blockPipe.getPipeTileEntity(world, pos);
            if (placedPipe != null) {
                EnumFacing towardClicked = side.getOpposite();
                BlockPos clickedPos = pos.offset(towardClicked);
                IPipeTile<PipeType, NodeDataType> clickedPipe = blockPipe.getPipeTileEntity(world, clickedPos);

                boolean connect = false;
                if (clickedPipe != null) {
                    connect = true;
                } else if (world.getTileEntity(clickedPos) != null) {
                    int activeMask = blockPipe.getActiveNodeConnections(world, pos, placedPipe);
                    connect = (activeMask & (1 << towardClicked.getIndex())) != 0;
                }

                // opt-in: start with every side blocked, open only the one we were placed against
                for (EnumFacing face : EnumFacing.VALUES) {
                    boolean open = connect && face == towardClicked;
                    placedPipe.setConnectionBlocked(AttachmentType.PIPE, face, !open);
                }
                if (clickedPipe != null) {
                    clickedPipe.setConnectionBlocked(AttachmentType.PIPE, side, false);
                }
            }
        }
        return placed;
    }
}
