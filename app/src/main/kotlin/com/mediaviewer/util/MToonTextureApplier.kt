package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentAsset
import java.nio.ByteBuffer

/**
 * Applies MToon base-color textures to a loaded Filament asset.
 *
 * After [MToonMaterialParser] extracts texture data from the GLB, this
 * creates Filament [Texture] objects from the image bytes and binds them
 * to the asset's material instances.
 *
 * Filament's gltfio does not support MToon, so materials load without
 * textures. This manually wires the base-color textures that MToon
 * materials reference.
 *
 * ## Why this is split into two phases
 * The old version did everything -- BitmapFactory.decodeByteArray at full
 * resolution, an IntArray copy of every pixel, a *third* manual per-pixel
 * copy into a direct ByteBuffer -- while already running on the UI thread
 * (the old call site wrapped the whole load in runBlocking(Dispatchers.Main)).
 * A real VRM export commonly ships several 2048x2048+ textures; decoding
 * one of those at full size needs three ~16-64MB buffers alive
 * simultaneously (bitmap + intarray + direct buffer) on top of the raw GLB
 * bytes already held for parsing -- exactly the kind of spike that produces
 * the OutOfMemoryError this screen was showing, and doing it all on the
 * main thread is what made "sometimes some textures load, sometimes they
 * don't" and general jank/ANR-adjacent stalls happen too.
 *
 * [decodeTextures] does the CPU-only, allocation-heavy work (decode +
 * downsample + pixel copy) and is safe -- required, even -- to call from a
 * background thread. It:
 *  - Reads the bitmap's bounds first and picks an inSampleSize so nothing
 *    decodes larger than [MAX_TEXTURE_DIMENSION] on its longest edge. A
 *    phone screen never shows enough of a VRM avatar's face at once to
 *    need a 4096px texture at full resolution, and this alone cuts
 *    worst-case per-texture memory by 4-16x before anything else.
 *  - Uses Bitmap.copyPixelsToBuffer instead of getPixels + a manual
 *    per-pixel loop. Android's ARGB_8888 config is stored in memory as
 *    R,G,B,A bytes per pixel -- which is exactly the byte layout Filament's
 *    Texture.Format.RGBA/Type.UBYTE wants -- so this is both a native bulk
 *    copy (fast) instead of ~4M+ individual ByteBuffer.put() calls, and
 *    needs only ONE extra buffer instead of two (no IntArray).
 *  - Catches OutOfMemoryError per-texture (not just Exception -- an Error,
 *    not caught by a plain catch (e: Exception)) so one huge texture
 *    failing to decode skips *that* texture instead of taking the whole
 *    avatar load down with it.
 *
 * [bindTextures] then does only fast, GL-context-bound work -- building a
 * Filament Texture from an already-decoded buffer and calling
 * setParameter -- and is the only part of this file that still needs to
 * run on the thread that owns the Filament engine (normally the main
 * thread, since that's where ModelViewer's SurfaceView/GL context live).
 */
object MToonTextureApplier {
    private const val TAG = "MToonApplier"

    /** No VRM avatar needs to be rendered larger than this on a phone
     *  screen -- VRM exports commonly ship 2048/4096px textures sized for
     *  a desktop VTuber setup, which is far more resolution than this
     *  screen can ever show and was the single biggest memory cost in the
     *  old pipeline. Downsampling here, once, up front, is strictly better
     *  than decoding full-size and letting Filament (or nothing) deal with
     *  it later. */
    private const val MAX_TEXTURE_DIMENSION = 1024

    /** One texture's CPU-decoded pixels, ready to hand straight to
     *  Filament -- no further per-pixel work needed on the main thread. */
    class DecodedTexture(val width: Int, val height: Int, val pixels: ByteBuffer)

    /**
     * Phase 1 -- decode every referenced base-color texture to RGBA8
     * pixels, downsampled to fit [MAX_TEXTURE_DIMENSION]. Pure CPU work;
     * call this from a background dispatcher (e.g. Dispatchers.Default),
     * never the main thread -- a multi-MB texture decode blocking the UI
     * thread is exactly what starved MediaPipe's callbacks and made
     * tracking look dead on first entry.
     */
    fun decodeTextures(parseResult: MToonMaterialParser.ParseResult): Map<Int, DecodedTexture> {
        val decoded = mutableMapOf<Int, DecodedTexture>()
        for ((texIndex, bytes) in parseResult.textureBytes) {
            val texture = decodeOneTexture(texIndex, bytes) ?: continue
            decoded[texIndex] = texture
        }
        Log.i(TAG, "Decoded ${decoded.size}/${parseResult.textureBytes.size} MToon textures")
        return decoded
    }

    private fun decodeOneTexture(texIndex: Int, bytes: ByteArray): DecodedTexture? {
        return try {
            // Bounds-only pass first: cheap (no pixel allocation at all),
            // gives us the source size so we can pick an inSampleSize that
            // lands at or under MAX_TEXTURE_DIMENSION on the long edge
            // without ever allocating the full-size bitmap.
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            val srcWidth = boundsOptions.outWidth
            val srcHeight = boundsOptions.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                Log.w(TAG, "Texture $texIndex: BitmapFactory couldn't read bounds (${bytes.size} bytes)")
                return null
            }
            var sampleSize = 1
            val longestEdge = maxOf(srcWidth, srcHeight)
            while (longestEdge / (sampleSize * 2) >= MAX_TEXTURE_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888 // matches Filament's RGBA8/UBYTE byte layout -- see class doc
                inScaled = false
                inMutable = false
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: run {
                Log.e(TAG, "Texture $texIndex: BitmapFactory failed to decode ${bytes.size} bytes")
                return null
            }
            // inPreferredConfig is a *hint* -- some encoders (indexed-
            // palette PNGs, some JPEGs) still come back as a different
            // config. copyPixelsToBuffer below assumes 4 bytes/pixel, so
            // force a copy through ARGB_8888 if needed rather than
            // silently mis-reading the buffer.
            val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                converted ?: run {
                    Log.e(TAG, "Texture $texIndex: could not convert ${bitmap.config} to ARGB_8888")
                    return null
                }
            }

            val width = argbBitmap.width
            val height = argbBitmap.height
            // ONE buffer, filled by a native bulk copy -- no IntArray, no
            // manual per-pixel loop. See this file's class doc for why
            // ARGB_8888's in-memory layout already matches what Filament
            // wants here.
            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
            argbBitmap.copyPixelsToBuffer(pixelBuffer)
            pixelBuffer.rewind()
            argbBitmap.recycle()

            DecodedTexture(width, height, pixelBuffer)
        } catch (oom: OutOfMemoryError) {
            // Caught specifically (Exception alone would miss this) so one
            // pathologically large texture skips itself instead of taking
            // the whole avatar load down.
            Log.e(TAG, "Texture $texIndex: out of memory even after downsampling (${bytes.size} source bytes) -- skipping", oom)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Texture $texIndex: failed to decode", e)
            null
        }
    }

    /**
     * Phase 2 -- binds already-[decodeTextures]'d pixels to the asset's
     * material instances. Only GL-context-bound calls here (building a
     * Filament Texture, setParameter) -- must run on the thread that owns
     * [engine] (normally the main thread). No decoding happens in this
     * function, so it's fast: safe to call from runBlocking(Main) without
     * reintroducing the stall this split was built to avoid.
     *
     * @return Number of materials that got textures applied.
     */
    fun bindTextures(
        engine: Engine,
        asset: FilamentAsset,
        parseResult: MToonMaterialParser.ParseResult,
        decodedTextures: Map<Int, DecodedTexture>
    ): Int {
        var applied = 0
        try {
            // Material instances live on FilamentInstance, not FilamentAsset
            // (Filament 1.51.6: FilamentAsset.getInstance().getMaterialInstances()).
            val materialInstances = asset.instance.materialInstances
            Log.i(TAG, "Asset has ${materialInstances.size} material instances, " +
                    "${parseResult.materials.size} parsed materials")

            if (materialInstances.size != parseResult.materials.size) {
                Log.w(TAG, "Material count mismatch: Filament=${materialInstances.size}, " +
                        "parsed=${parseResult.materials.size}. Attempting best-effort mapping.")
            }

            for (matInfo in parseResult.materials) {
                val texIndex = matInfo.baseColorTextureIndex ?: continue
                val decoded = decodedTextures[texIndex] ?: continue

                if (matInfo.materialIndex >= materialInstances.size) continue
                val materialInstance = materialInstances[matInfo.materialIndex]

                val texture = createFilamentTexture(engine, decoded) ?: continue

                try {
                    // setParameter takes TextureSampler, not Texture.Sampler --
                    // verified against Filament 1.51.6 MaterialInstance source.
                    materialInstance.setParameter("baseColorMap", texture, TextureSampler())
                    if (!matInfo.baseColorFactor.contentEquals(floatArrayOf(1f, 1f, 1f, 1f))) {
                        materialInstance.setParameter("baseColorFactor",
                            matInfo.baseColorFactor[0],
                            matInfo.baseColorFactor[1],
                            matInfo.baseColorFactor[2],
                            matInfo.baseColorFactor[3])
                    }
                    applied++
                    Log.i(TAG, "Applied texture to material '${matInfo.name}' (index ${matInfo.materialIndex})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set texture on material '${matInfo.name}': ${e.message}")
                    engine.destroyTexture(texture)
                }
            }

            Log.i(TAG, "Applied textures to $applied/${parseResult.materials.size} materials")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply MToon textures", e)
        }
        return applied
    }

    private fun createFilamentTexture(engine: Engine, decoded: DecodedTexture): Texture? {
        return try {
            val texture = Texture.Builder()
                .width(decoded.width)
                .height(decoded.height)
                .levels(1)
                .format(Texture.InternalFormat.RGBA8)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .build(engine)

            val buffer = Texture.PixelBufferDescriptor(
                decoded.pixels,
                Texture.Format.RGBA,
                Texture.Type.UBYTE
            )
            texture.setImage(engine, 0, buffer)
            texture
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Filament texture from decoded pixels", e)
            null
        }
    }
}
