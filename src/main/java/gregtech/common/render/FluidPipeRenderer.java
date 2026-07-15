package gregtech.common.render;

import codechicken.lib.render.BlockRenderer;
import codechicken.lib.render.CCModel;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.block.BlockRenderingRegistry;
import codechicken.lib.render.block.ICCBlockRenderer;
import codechicken.lib.render.item.IItemRenderer;
import codechicken.lib.render.pipeline.ColourMultiplier;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.texture.TextureUtils;
import codechicken.lib.util.TransformUtils;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Matrix4;
import codechicken.lib.vec.Translation;
import codechicken.lib.vec.Vector3;
import codechicken.lib.vec.uv.IconTransformation;
import gregtech.api.GTValues;
import gregtech.api.cover.ICoverable;
import gregtech.api.pipenet.tile.IPipeTile;
import gregtech.api.unification.material.type.Material;
import gregtech.api.util.GTUtility;
import gregtech.api.util.ModCompatibility;
import gregtech.common.pipelike.fluidpipe.BlockFluidPipe;
import gregtech.common.pipelike.fluidpipe.FluidPipeProperties;
import gregtech.common.pipelike.fluidpipe.FluidPipeType;
import gregtech.common.pipelike.fluidpipe.ItemBlockFluidPipe;
import gregtech.common.pipelike.fluidpipe.tile.TileEntityFluidPipe;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.model.IModelState;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class FluidPipeRenderer implements ICCBlockRenderer, IItemRenderer {

    public static ModelResourceLocation MODEL_LOCATION = new ModelResourceLocation(new ResourceLocation(GTValues.MODID, "fluid_pipe"), "normal");
    public static FluidPipeRenderer INSTANCE = new FluidPipeRenderer();
    public static EnumBlockRenderType BLOCK_RENDER_TYPE;
    private Map<FluidPipeType, PipeTextureInfo> pipeTextures = new HashMap<>();

    private static final float DIFFUSE_DOWN = 0.5F;
    private static final float DIFFUSE_UP = 1.0F;
    private static final float DIFFUSE_NORTH_SOUTH = 0.8F;
    private static final float DIFFUSE_EAST_WEST = 0.6F;

    private static final ThreadLocal<BlockRenderer.BlockFace> blockFaces = ThreadLocal.withInitial(BlockRenderer.BlockFace::new);

    private static class PipeTextureInfo {
        public final TextureAtlasSprite inTexture;
        public final TextureAtlasSprite sideTexture;

        public PipeTextureInfo(TextureAtlasSprite inTexture, TextureAtlasSprite sideTexture) {
            this.inTexture = inTexture;
            this.sideTexture = sideTexture;
        }
    }

    public static void preInit() {
        BLOCK_RENDER_TYPE = BlockRenderingRegistry.createRenderType("gt_fluid_pipe");
        BlockRenderingRegistry.registerRenderer(BLOCK_RENDER_TYPE, INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        TextureUtils.addIconRegister(INSTANCE::registerIcons);
    }

    public void registerIcons(TextureMap map) {
        for (FluidPipeType fluidPipeType : FluidPipeType.values()) {
            ResourceLocation inLocation = new ResourceLocation(GTValues.MODID, String.format("blocks/pipe/pipe_%s_in", fluidPipeType.name));
            ResourceLocation sideLocation = new ResourceLocation(GTValues.MODID, String.format("blocks/pipe/pipe_%s_side", fluidPipeType.name));

            TextureAtlasSprite inTexture = map.registerSprite(inLocation);
            TextureAtlasSprite sideTexture = map.registerSprite(sideLocation);
            this.pipeTextures.put(fluidPipeType, new PipeTextureInfo(inTexture, sideTexture));
        }
    }

    @SubscribeEvent
    public void onModelsBake(ModelBakeEvent event) {
        event.getModelRegistry().putObject(MODEL_LOCATION, this);
    }

    @Override
    public void renderItem(ItemStack rawItemStack, TransformType transformType) {
        ItemStack stack = ModCompatibility.getRealItemStack(rawItemStack);
        if (!(stack.getItem() instanceof ItemBlockFluidPipe)) {
            return;
        }
        CCRenderState renderState = CCRenderState.instance();
        GlStateManager.enableBlend();
        renderState.reset();
        renderState.startDrawing(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        BlockFluidPipe blockFluidPipe = (BlockFluidPipe) ((ItemBlockFluidPipe) stack.getItem()).getBlock();
        FluidPipeType pipeType = blockFluidPipe.getItemPipeType(stack);
        Material material = blockFluidPipe.getItemMaterial(stack);
        if (pipeType != null && material != null) {
            renderPipeBlock(material, pipeType, IPipeTile.DEFAULT_INSULATION_COLOR, renderState, new IVertexOperation[0], null, null,0,0);
        }
        renderState.draw();
        GlStateManager.disableBlend();
    }

    @Override
    public boolean renderBlock(IBlockAccess world, BlockPos pos, IBlockState state, BufferBuilder buffer) {
        CCRenderState renderState = CCRenderState.instance();
        renderState.reset();
        renderState.bind(buffer);
        renderState.setBrightness(world, pos);

        BlockFluidPipe blockPipe = ((BlockFluidPipe) state.getBlock());
        TileEntityFluidPipe tileEntityPipe = (TileEntityFluidPipe) blockPipe.getPipeTileEntity(world, pos);

        if (tileEntityPipe == null) {
            return false;
        }

        FluidPipeType fluidPipeType = tileEntityPipe.getPipeType();
        Material pipeMaterial = tileEntityPipe.getPipeMaterial();
        int paintingColor = tileEntityPipe.getInsulationColor();



        if (fluidPipeType != null && pipeMaterial != null) {
            BlockRenderLayer renderLayer = MinecraftForgeClient.getRenderLayer();

            if (renderLayer == BlockRenderLayer.CUTOUT) {
                int connectedSidesMask = blockPipe.getActualConnections(tileEntityPipe, world);


                int blockedSidesMask = tileEntityPipe.getBlockedConnections();

                IVertexOperation[] pipeline = new IVertexOperation[0];

                renderPipeBlock(pipeMaterial, fluidPipeType, paintingColor, renderState, pipeline, world, pos,connectedSidesMask, blockedSidesMask);
            }


            ICoverable coverable = tileEntityPipe.getCoverableImplementation();
            coverable.renderCovers(renderState, new Matrix4().translate(pos.getX(), pos.getY(), pos.getZ()), renderLayer);
        }
        return true;
    }

    private int getPipeColor(Material material, int insulationColor) {
        if(insulationColor == IPipeTile.DEFAULT_INSULATION_COLOR) {
            return material.materialRGB;
        } else return insulationColor;
    }

    public boolean renderPipeBlock(Material material, FluidPipeType pipeType, int insulationColor,
                                   CCRenderState state, IVertexOperation[] pipeline,
                                   IBlockAccess world, BlockPos pos, int connectMask, int blockedMask) {

        int pipeColor = GTUtility.convertRGBtoOpaqueRGBA_CL(getPipeColor(material, insulationColor));
        ColourMultiplier multiplier = new ColourMultiplier(pipeColor);

        PipeTextureInfo textureInfo = this.pipeTextures.get(pipeType);
        float thickness = pipeType.getThickness();

        /*
        IVertexOperation[] basePipeline;
        if (pos == null) {
            basePipeline = pipeline;
        } else {
            basePipeline = ArrayUtils.addAll(
                    pipeline,
                    new Translation(pos),
                    state.lightMatrix
            );
        }
         */

        IVertexOperation[] basePipeline;
        if (pos == null) {
            basePipeline = pipeline;
        } else {
            basePipeline = ArrayUtils.addAll(pipeline, new Translation(pos));
        }

        Map<EnumFacing, IVertexOperation[]> openPipelines = new EnumMap<>(EnumFacing.class);
        Map<EnumFacing, IVertexOperation[]> sidePipelines = new EnumMap<>(EnumFacing.class);
        for (EnumFacing facing : EnumFacing.VALUES) {
            ColourMultiplier faceMultiplier = new ColourMultiplier(scaleColor(pipeColor, getFaceDiffuse(facing)));
            openPipelines.put(facing, ArrayUtils.addAll(basePipeline, new IconTransformation(textureInfo.inTexture), faceMultiplier));
            sidePipelines.put(facing, ArrayUtils.addAll(basePipeline, new IconTransformation(textureInfo.sideTexture), faceMultiplier));
        }

        IVertexOperation[] openPipeline = ArrayUtils.addAll(
                basePipeline,
                new IconTransformation(textureInfo.inTexture),
                multiplier
        );

        IVertexOperation[] sidePipeline = ArrayUtils.addAll(
                basePipeline,
                new IconTransformation(textureInfo.sideTexture),
                multiplier
        );

        int sidedConnMask = connectMask & 0b111111;
        int endMask = (connectMask >> 6) & 0b111111;

        Cuboid6 centerCuboid = BlockFluidPipe.getSideBox(null, thickness);

        if (sidedConnMask == 0) {

            for (EnumFacing face : EnumFacing.VALUES) {
                renderFace(state, world, pos, sidePipeline, face, centerCuboid);
            }

        } else {

            for (EnumFacing face : EnumFacing.VALUES) {

                if (!hasFlag(sidedConnMask, face)) {

                    if (hasFlag(blockedMask, face)) {
                        renderFace(state, world, pos, sidePipeline, face, centerCuboid);
                        continue;
                    }

                    EnumFacing opposite = face.getOpposite();
                    boolean oppositeConnected = hasFlag(sidedConnMask, opposite);
                    boolean onlyOpposite = oppositeConnected && (sidedConnMask & ~(1 << opposite.getIndex())) == 0;

                    if (onlyOpposite) {
                        renderFace(state, world, pos, openPipeline, face, centerCuboid);
                    } else {
                        renderFace(state, world, pos, sidePipeline, face, centerCuboid);
                    }

                } else {

                    Cuboid6 extCuboid = BlockFluidPipe.getSideBox(face, thickness);

                    if (hasFlag(endMask, face)) {
                        renderFace(state, world, pos, openPipeline, face, extCuboid);
                    } else {
                        renderFace(state, world, pos, sidePipeline, face, extCuboid);
                    }
                }
            }
        }

        for (EnumFacing side : EnumFacing.VALUES) {

            if (!hasFlag(sidedConnMask, side))
                continue;

            Cuboid6 extCuboid = BlockFluidPipe.getSideBox(side, thickness);

            for (EnumFacing face : EnumFacing.VALUES) {

                if (face.getAxis() == side.getAxis())
                    continue;

                renderFace(state, world, pos, sidePipeline, face, extCuboid);
            }
        }

        return true;
    }

    protected static void renderFace(CCRenderState state, IBlockAccess world, BlockPos pos, IVertexOperation[] pipeline, EnumFacing side, Cuboid6 cuboid) {
        if (world != null && pos != null) {
            state.setBrightness(world, pos.offset(side));
        }
        BlockRenderer.BlockFace blockFace = blockFaces.get();
        blockFace.loadCuboidFace(cuboid, side.getIndex());
        state.setPipeline(blockFace, 0, blockFace.verts.length, pipeline);
        state.render();
    }

    private static boolean hasFlag(int mask, EnumFacing facing) {
        return (mask & (1 << facing.getIndex())) != 0;
    }

    @Override
    public void renderBrightness(IBlockState state, float brightness) {
    }

    @Override
    public void handleRenderBlockDamage(IBlockAccess world, BlockPos pos, IBlockState state, TextureAtlasSprite sprite, BufferBuilder buffer) {
        CCRenderState renderState = CCRenderState.instance();
        renderState.reset();
        renderState.bind(buffer);
        renderState.setPipeline(new Vector3(new Vec3d(pos)).translation(), new IconTransformation(sprite));
        BlockFluidPipe blockFluidPipe = (BlockFluidPipe) state.getBlock();
        IPipeTile<FluidPipeType, FluidPipeProperties> tileEntityPipe = blockFluidPipe.getPipeTileEntity(world, pos);
        if (tileEntityPipe == null) {
            return;
        }
        FluidPipeType fluidPipeType = tileEntityPipe.getPipeType();
        if (fluidPipeType == null) {
            return;
        }
        float thickness = fluidPipeType.getThickness();
        int connectedSidesMask = blockFluidPipe.getActualConnections(tileEntityPipe, world);
        Cuboid6 baseBox = BlockFluidPipe.getSideBox(null, thickness);
        BlockRenderer.renderCuboid(renderState, baseBox, 0);
        for (EnumFacing renderSide : EnumFacing.VALUES) {
            if ((connectedSidesMask & (1 << renderSide.getIndex())) > 0) {
                Cuboid6 sideBox = BlockFluidPipe.getSideBox(renderSide, thickness);
                BlockRenderer.renderCuboid(renderState, sideBox, 0);
            }
        }
    }

    @Override
    public void registerTextures(TextureMap map) {
    }

    @Override
    public IModelState getTransforms() {
        return TransformUtils.DEFAULT_BLOCK;
    }

    @Override
    public TextureAtlasSprite getParticleTexture() {
        return TextureUtils.getMissingSprite();
    }

    @Override
    public boolean isBuiltInRenderer() {
        return true;
    }

    @Override
    public boolean isAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    public Pair<TextureAtlasSprite, Integer> getParticleTexture(IPipeTile<FluidPipeType, FluidPipeProperties> tileEntity) {
        if (tileEntity == null) {
            return Pair.of(TextureUtils.getMissingSprite(), 0xFFFFFF);
        }
        FluidPipeType fluidPipeType = tileEntity.getPipeType();
        Material material = ((TileEntityFluidPipe) tileEntity).getPipeMaterial();
        if (fluidPipeType == null || material == null) {
            return Pair.of(TextureUtils.getMissingSprite(), 0xFFFFFF);
        }
        int pipeColor = getPipeColor(material, tileEntity.getInsulationColor());
        TextureAtlasSprite atlasSprite = pipeTextures.get(fluidPipeType).sideTexture;
        return Pair.of(atlasSprite, pipeColor);
    }


    private static float getFaceDiffuse(EnumFacing facing) {
        switch (facing) {
            case DOWN: return DIFFUSE_DOWN;
            case UP: return DIFFUSE_UP;
            case NORTH:
            case SOUTH: return DIFFUSE_NORTH_SOUTH;
            default: return DIFFUSE_EAST_WEST;
        }
    }

    private static int scaleColor(int argb, float scale) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.round(((argb >> 16) & 0xFF) * scale);
        int g = Math.round(((argb >> 8) & 0xFF) * scale);
        int b = Math.round((argb & 0xFF) * scale);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
