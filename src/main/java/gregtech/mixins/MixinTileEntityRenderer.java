
package gregtech.mixins;

import gregtech.api.metatileentity.IFastRenderMetaTileEntity;
import gregtech.api.metatileentity.IRenderMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import meldexun.renderlib.RenderLib;
import meldexun.renderlib.api.IBoundingBoxCache;
import meldexun.renderlib.api.ILoadable;
import meldexun.renderlib.integration.ValkyrienSkies;
import meldexun.renderlib.renderer.tileentity.TileEntityRenderer;
import meldexun.renderlib.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.*;
import meldexun.renderlib.renderer.tileentity.TileEntityRenderList;

import java.util.*;

@Mixin(value = TileEntityRenderer.class, remap = false)
public abstract class MixinTileEntityRenderer {
    @Shadow
    private int camChunkX;
    @Shadow
    private int camChunkZ;
    @Shadow
    private int renderDist;
    @Shadow
    protected int renderedTileEntities;
    @Shadow
    protected int occludedTileEntities;
    @Shadow
    protected int totalTileEntities;

    @Final
    @Shadow
    private Deque<TileEntityRenderList> tileEntityListQueue;


    @Shadow
    protected abstract <T extends TileEntity> void setCanBeOcclusionCulled(T tileEntity, boolean canBeOcclusionCulled);

    @Shadow
    protected abstract <T extends TileEntity> boolean isOcclusionCulled(T tileEntity);

    @Shadow
    protected abstract void preRenderTileEntity(TileEntity tileEntity);

    @Shadow
    protected abstract void postRenderTileEntity();

    @Unique
    private final Long2ObjectOpenHashMap<List<TileEntity>> chunkBuckets = new Long2ObjectOpenHashMap<>();
    @Unique
    private final Object2LongOpenHashMap<TileEntity> teChunkKey = new Object2LongOpenHashMap<>();
    @Unique
    private final Long2ObjectOpenHashMap<AxisAlignedBB> chunkBoundsCache = new Long2ObjectOpenHashMap<>();

    @Unique
    private int reconcileCursor;

    @Unique
    private long[] sweepKeys = new long[0];

    @Unique
    private int sweepCursor;

    @Unique
    private static final int Y_SLICE_LOG2 = 4;                  // 16-block slices
    @Unique
    private static final int Y_BITS = 8;                        // slice index 0..255
    @Unique
    private static final int XZ_BITS = 28;
    @Unique
    private static final long Y_MASK  = (1L << Y_BITS) - 1;
    @Unique
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    @Unique
    private static final long XZ_BIAS = 1L << (XZ_BITS - 1);    // recenters cx/cz to unsigned range


    /**
     * @author Duska
     * @reason amogus, do I really have to add these docs lol
     */
    @Overwrite
    public void setup(ICamera frustum, float partialTicks, double camX, double camY, double camZ) {
        Minecraft mc = Minecraft.getMinecraft();
        this.renderedTileEntities = 0;
        this.occludedTileEntities = 0;
        this.camChunkX = MathHelper.floor(RenderUtil.getCameraEntityX()) >> 4;
        this.camChunkZ = MathHelper.floor(RenderUtil.getCameraEntityZ()) >> 4;
        this.renderDist = mc.gameSettings.renderDistanceChunks;

        this.absorbNewlyAddedTileEntities(mc.world);

        /*
        if (++this.sweepCounter >= SWEEP_INTERVAL) {
            this.sweepCounter = 0;
            sweepInvalidEntries();
            reconcileMissingEntries(mc.world);
        }
         */
        sweepInvalidEntries(8);
        reconcileMissingEntries(mc.world, 50);


        this.totalTileEntities = this.teChunkKey.size();

        TileEntityRenderList tileEntityList = new TileEntityRenderList();

        for (Long2ObjectMap.Entry<List<TileEntity>> entry : chunkBuckets.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            if (!isChunkColumnInRange(key) || !isChunkColumnVisible(key, frustum)) {
                continue;
            }

            List<TileEntity> bucket = entry.getValue();
            for (Iterator<TileEntity> it = bucket.iterator(); it.hasNext(); ) {
                TileEntity tileEntity = it.next();
                if (tileEntity.isInvalid()) {
                    it.remove();
                    this.teChunkKey.removeLong(tileEntity);
                    continue;
                }
                processTileEntity(tileEntity, frustum, camX, camY, camZ, tileEntityList);
            }
        }



        this.tileEntityListQueue.addLast(tileEntityList);
    }

    /*
    @Unique
    private void reconcileMissingEntries(World world) {
        for (TileEntity te : world.loadedTileEntityList) {
            if (!te.isInvalid() && !this.teChunkKey.containsKey(te) && !this.unbucketedTileEntities.contains(te)) {
                addToBucket(te);
            }
        }
    }
     */
    @Unique
    private void reconcileMissingEntries(World world, int budget) {
        List<TileEntity> loaded = world.loadedTileEntityList;

        if (loaded.isEmpty()) {
            reconcileCursor = 0;
            return;
        }

        int size = loaded.size();

        for (int i = 0; i < budget && i < size; i++) {
            if (reconcileCursor >= size) {
                reconcileCursor = 0;
                size = loaded.size();

                if (size == 0) {
                    return;
                }
            }

            TileEntity te = loaded.get(reconcileCursor++);

            if (!te.isInvalid() && !this.teChunkKey.containsKey(te)) {

                addToBucket(te);
            }
        }
    }



    @Unique
    private void processTileEntity(TileEntity tileEntity, ICamera frustum, double camX, double camY, double camZ, TileEntityRenderList tileEntityList) {
        if (!((ILoadable) tileEntity).isChunkLoaded()) {
            return;
        }

        if (tileEntity.getDistanceSq(camX, camY, camZ) >= tileEntity.getMaxRenderDistanceSquared()) {
            this.setCanBeOcclusionCulled(tileEntity, false);
            return;
        }

        if (!((IBoundingBoxCache) tileEntity).getCachedBoundingBox().isVisible(frustum)) {
            this.setCanBeOcclusionCulled(tileEntity, false);
            return;
        }

        if (!tileEntity.shouldRenderInPass(0) && !tileEntity.shouldRenderInPass(1)) {
            return;
        }

        this.setCanBeOcclusionCulled(tileEntity, true);
        if (this.isOcclusionCulled(tileEntity)) {
            this.occludedTileEntities++;
        } else {
            this.renderedTileEntities++;
            tileEntityList.addTileEntity(tileEntity);
        }
    }


    /**
     * Reads world.addedTileEntityList directly instead of going through
     * TileEntityUtil's full-list callback — that list only contains TEs added
     * since last frame, so this is bounded by placement rate, not total TE count.
     * We still invoke TileEntityUtil.processTileEntityList with a no-op consumer
     * to preserve its merge side effects (world.addTileEntity / chunk.addTileEntity /
     * notifyBlockUpdate), which vanilla and other renderlib code expect to run once
     * per frame. ASSUMPTION: ITileEntityHolder.getTileEntities() returns the
     * existing loadedTileEntityList by reference (no copy/iteration) — if it does
     * something heavier, this call stops being free and needs a different approach.
     */
    @Unique
    private void absorbNewlyAddedTileEntities(World world) {
        if (world.addedTileEntityList.isEmpty()) {
            return;
        }
        List<TileEntity> newlyAdded = new ArrayList<>(world.addedTileEntityList);

        //TileEntityUtil.processTileEntityList(world, ignoredFullList -> {
        // no-op: we only need the addedTileEntityList merge side effects below,
        // not a walk over the full loaded list.
        // });

        for (TileEntity tileEntity : newlyAdded) {
            if (!tileEntity.isInvalid()) {
                addToBucket(tileEntity);
            }
        }

        world.addedTileEntityList.clear();
    }

    @Unique
    private void addToBucket(TileEntity tileEntity) {
        long key = chunkKey(tileEntity.getPos());
        this.chunkBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(tileEntity);
        this.teChunkKey.put(tileEntity, key);
    }

    /**
     * Bounded full sweep to reclaim entries for TEs that went invalid without ever
     * passing through the visible-chunk path (e.g. a machine breaks in a chunk the
     * player hasn't looked at since). There's no invalidate()-hook available here,
     * so this is the fallback net — infrequent, not per-frame.
     */
   /*
    @Unique
    private void sweepInvalidEntries() {
        for (Iterator<Long2ObjectMap.Entry<List<TileEntity>>> bucketIt = this.chunkBuckets.long2ObjectEntrySet().iterator(); bucketIt.hasNext(); ) {
            Long2ObjectMap.Entry<List<TileEntity>> entry = bucketIt.next();

            List<TileEntity> bucket = entry.getValue();

            bucket.removeIf(te -> {
                if (te.isInvalid()) {
                    this.teChunkKey.removeLong(te);
                    return true;
                }
                return false;
            });

            if (bucket.isEmpty()) {
                bucketIt.remove();
                this.chunkBoundsCache.remove(entry.getLongKey());
            }
        }
        this.unbucketedTileEntities.removeIf(TileEntity::isInvalid);
    }
    */
    private void sweepInvalidEntries(int budget) {
        if (this.chunkBuckets.isEmpty()) {
            this.sweepKeys = new long[0];
            this.sweepCursor = 0;
            return;
        }

        // Aloita uusi kierros nykyisestä bucket-tilasta.
        if (this.sweepCursor >= this.sweepKeys.length) {
            this.sweepKeys = this.chunkBuckets.keySet().toLongArray();
            this.sweepCursor = 0;
        }

        int processed = 0;

        while (processed < budget && this.sweepCursor < this.sweepKeys.length) {
            long key = this.sweepKeys[this.sweepCursor++];

            List<TileEntity> bucket = this.chunkBuckets.get(key);

            // Bucket on voinut kadota edellisen framet aikana.
            if (bucket == null) {
                processed++;
                continue;
            }

            bucket.removeIf(te -> {
                if (te == null || te.isInvalid()) {
                    if (te != null) {
                        this.teChunkKey.removeLong(te);
                    }
                    return true;
                }
                return false;
            });

            if (bucket.isEmpty()) {
                this.chunkBuckets.remove(key);
                this.chunkBoundsCache.remove(key);
            }

            processed++;
        }

        // Koko snapshot käsitelty → seuraava frame aloittaa uuden snapshotin.
        if (this.sweepCursor >= this.sweepKeys.length) {
            this.sweepKeys = new long[0];
            this.sweepCursor = 0;
        }
    }



    /**
     * @author Duska
     * @reason AAAAAAAAAAAAAAAAAAA
     */
    @Overwrite
    protected void renderTileEntities(float partialTicks, TileEntityRenderList tileEntityList) {
        List<TileEntity> fastOnly = new ArrayList<>();
        List<TileEntity> fullDispatch = new ArrayList<>();

        for (TileEntity te : tileEntityList.getTileEntities()) {
            MetaTileEntity mte = (te instanceof MetaTileEntityHolder)
                    ? ((MetaTileEntityHolder) te).getMetaTileEntity() : null;
            if (mte instanceof IFastRenderMetaTileEntity && !(mte instanceof IRenderMetaTileEntity)) {
                fastOnly.add(te);
            } else {
                fullDispatch.add(te);
            }
        }

        if (!fastOnly.isEmpty()) {
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.shadeModel(Minecraft.isAmbientOcclusionEnabled() ? GL11.GL_SMOOTH : GL11.GL_FLAT);
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);

            double px = TileEntityRendererDispatcher.staticPlayerX;
            double py = TileEntityRendererDispatcher.staticPlayerY;
            double pz = TileEntityRendererDispatcher.staticPlayerZ;

            TileEntitySpecialRenderer<MetaTileEntityHolder> mteTESR = TileEntityRendererDispatcher.instance.getRenderer(MetaTileEntityHolder.class);

            for (TileEntity te : fastOnly) {
                mteTESR.renderTileEntityFast((MetaTileEntityHolder) te,
                        te.getPos().getX() - px, te.getPos().getY() - py, te.getPos().getZ() - pz,
                        partialTicks, -1, partialTicks, buffer);
            }

            buffer.setTranslation(0, 0, 0);
            tessellator.draw();
            RenderHelper.enableStandardItemLighting();
        }

        for (TileEntity te : fullDispatch) {
            preRenderTileEntity(te);
            TileEntityRendererDispatcher.instance.render(te, partialTicks, -1);
            postRenderTileEntity();
        }
    }


    @Unique
    private static long chunkKey(BlockPos pos) {
        long cx = (pos.getX() >> 4) + XZ_BIAS;
        long cz = (pos.getZ() >> 4) + XZ_BIAS;
        long ySlice = pos.getY() >> Y_SLICE_LOG2;

        return ((cx & XZ_MASK) << (XZ_BITS + Y_BITS))
                | ((cz & XZ_MASK) << Y_BITS)
                |  (ySlice & Y_MASK);
    }

    @Unique
    private static int unpackCx(long key) {
        return (int) (((key >>> (XZ_BITS + Y_BITS)) & XZ_MASK) - XZ_BIAS);
    }

    @Unique
    private static int unpackCz(long key) {
        return (int) (((key >>> Y_BITS) & XZ_MASK) - XZ_BIAS);
    }

    @Unique
    private static int unpackYSlice(long key) {
        return (int) (key & Y_MASK);
    }

    @Unique
    private boolean isChunkColumnInRange(long key) {
        int cx = unpackCx(key);
        int cz = unpackCz(key);
        return Math.abs(cx - this.camChunkX) <= this.renderDist && Math.abs(cz - this.camChunkZ) <= this.renderDist;
    }

    @Unique
    private boolean isChunkColumnVisible(long key, ICamera frustum) {
        AxisAlignedBB bb = this.chunkBoundsCache.computeIfAbsent(key, k -> {
            int cx = unpackCx(k);
            int cz = unpackCz(k);
            int minY = unpackYSlice(k) << Y_SLICE_LOG2;
            int maxY = minY + (1 << Y_SLICE_LOG2);
            return new AxisAlignedBB(cx << 4, minY, cz << 4, (cx << 4) + 16, maxY, (cz << 4) + 16);
        });
        return frustum.isBoundingBoxInFrustum(bb);
    }
}
