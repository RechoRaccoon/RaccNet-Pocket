package com.mediaviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentAsset
import java.nio.ByteBuffer

/**
 * Textures the avatar: every material's base color (see
 * [MToonMaterialParser]'s file doc for why gltfio no longer does this).
 *
 * Two phases:
 *  - [decodeTextures] — CPU only, call off the main thread. Decodes each
 *    image once, downsampled to [MAX_TEXTURE_DIMENSION], with STRAIGHT
 *    (non-premultiplied) alpha as glTF expects — Android's default
 *    premultiplied pixels darken semi-transparent hair/lash edges — and
 *    builds the whole mip chain on the CPU, so there's no reliance on GPU
 *    mipmap generation.
 *  - [bindTextures] — main thread (owns the engine). Uploads each texture
 *    once, shares it between every material that uses it, and binds it to
 *    exactly the MaterialInstance(s) gltfio created for that glTF
 *    material, via [resolvePrimitives]. Returns the textures so the caller
 *    can destroy them when the model goes away.
 */
object MToonTextureApplier {
    private const val TAG = "MToonApplier"

    /** Phone screens never need more than this per avatar texture. */
    private const val MAX_TEXTURE_DIMENSION = 1024

    private val SAMPLER = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT
    )

    /** One decoded texture: [levels] holds tightly packed RGBA8 pixels for
     *  each mip level, largest first. */
    class DecodedTexture(val width: Int, val height: Int, val levels: List<ByteBuffer>)

    /** A glTF mesh primitive resolved to the Filament entity drawing it. */
    class ResolvedPrimitive(val ref: MToonMaterialParser.PrimitiveMaterialRef, val entity: Int)

    class BindResult(val materialsTextured: Int, val textures: List<Texture>)

    // ─────────────────────────────────────────────────────────── decode

    fun decodeTextures(parseResult: MToonMaterialParser.ParseResult): Map<Int, DecodedTexture> {
        val decoded = HashMap<Int, DecodedTexture>()
        for ((texIndex, bytes) in parseResult.textureBytes) {
            decodeOne(texIndex, bytes)?.let { decoded[texIndex] = it }
        }
        Log.i(TAG, "Decoded ${decoded.size}/${parseResult.textureBytes.size} textures")
        return decoded
    }

    private fun decodeOne(texIndex: Int, bytes: ByteArray): DecodedTexture? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Texture $texIndex: unreadable image (${bytes.size} bytes, ${bounds.outMimeType})")
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_TEXTURE_DIMENSION) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false // glTF wants straight alpha
                inScaled = false
            }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IllegalStateException("BitmapFactory returned null")
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
                bitmap = converted ?: throw IllegalStateException("could not convert to ARGB_8888")
            }
            val w = bitmap.width
            val h = bitmap.height
            // ARGB_8888 is laid out R,G,B,A in memory — Filament's RGBA/UBYTE.
            val base = ByteBuffer.allocateDirect(w * h * 4)
            bitmap.copyPixelsToBuffer(base)
            base.rewind()
            bitmap.recycle()
            DecodedTexture(w, h, buildMipChain(base, w, h))
        }
    } catch (oom: OutOfMemoryError) {
        Log.e(TAG, "Texture $texIndex: out of memory — skipped", oom)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Texture $texIndex: decode failed", e)
        null
    }

    /** 2x2 box filter down to 1x1, weighting color by alpha so transparent
     *  texels (often black) don't bleed dark fringes into edges. */
    private fun buildMipChain(base: ByteBuffer, width: Int, height: Int): List<ByteBuffer> {
        val levels = arrayListOf(base)
        var src = base
        var w = width
        var h = height
        while (w > 1 || h > 1) {
            val nw = maxOf(1, w / 2)
            val nh = maxOf(1, h / 2)
            val dst = ByteBuffer.allocateDirect(nw * nh * 4)
            for (y in 0 until nh) {
                val y0 = minOf(y * 2, h - 1); val y1 = minOf(y * 2 + 1, h - 1)
                for (x in 0 until nw) {
                    val x0 = minOf(x * 2, w - 1); val x1 = minOf(x * 2 + 1, w - 1)
                    var r = 0; var g = 0; var b = 0; var a = 0; var rgbPlain0 = 0; var rgbPlain1 = 0; var rgbPlain2 = 0
                    for (i in 0 until 4) {
                        val sx = if (i and 1 == 0) x0 else x1
                        val sy = if (i < 2) y0 else y1
                        val o = (sy * w + sx) * 4
                        val pa = src.get(o + 3).toInt() and 0xFF
                        val pr = src.get(o).toInt() and 0xFF
                        val pg = src.get(o + 1).toInt() and 0xFF
                        val pb = src.get(o + 2).toInt() and 0xFF
                        r += pr * pa; g += pg * pa; b += pb * pa; a += pa
                        rgbPlain0 += pr; rgbPlain1 += pg; rgbPlain2 += pb
                    }
                    val o = (y * nw + x) * 4
                    if (a > 0) {
                        dst.put(o, (r / a).toByte()); dst.put(o + 1, (g / a).toByte()); dst.put(o + 2, (b / a).toByte())
                    } else {
                        dst.put(o, (rgbPlain0 / 4).toByte()); dst.put(o + 1, (rgbPlain1 / 4).toByte()); dst.put(o + 2, (rgbPlain2 / 4).toByte())
                    }
                    dst.put(o + 3, ((a + 2) / 4).toByte())
                }
            }
            dst.rewind()
            levels.add(dst)
            src = dst; w = nw; h = nh
        }
        return levels
    }

    // ─────────────────────────────────────────────────────────── bind

    /**
     * Matches each glTF (node, primitive) to the Filament renderable drawing
     * it. gltfio names an entity after its node (or the node's mesh), so
     * look up by that name; when several nodes share a name, the n-th one
     * takes the n-th entity with a matching primitive count.
     */
    fun resolvePrimitives(
        engine: Engine,
        asset: FilamentAsset,
        refs: List<MToonMaterialParser.PrimitiveMaterialRef>
    ): List<ResolvedPrimitive> {
        val rm = engine.renderableManager
        val byName = HashMap<String, MutableList<Int>>()
        for (entity in asset.entities) {
            val name = runCatching { asset.getName(entity) }.getOrNull() ?: continue
            if (rm.getInstance(entity) == 0) continue
            byName.getOrPut(name) { mutableListOf() }.add(entity)
        }
        val out = ArrayList<ResolvedPrimitive>()
        val chosen = HashMap<Pair<String, Int>, Int?>()
        for (ref in refs) {
            val entity = chosen.getOrPut(ref.nodeName to ref.occurrence) {
                val candidates = byName[ref.nodeName].orEmpty().filter {
                    rm.getPrimitiveCount(rm.getInstance(it)) == ref.primitiveCount
                }
                candidates.getOrNull(ref.occurrence) ?: candidates.firstOrNull()
            } ?: continue
            if (ref.primitiveIndex < rm.getPrimitiveCount(rm.getInstance(entity))) {
                out.add(ResolvedPrimitive(ref, entity))
            }
        }
        if (out.size < refs.size) Log.w(TAG, "Resolved ${out.size}/${refs.size} primitives to renderables")
        return out
    }

    fun bindTextures(
        engine: Engine,
        primitives: List<ResolvedPrimitive>,
        parseResult: MToonMaterialParser.ParseResult,
        decoded: Map<Int, DecodedTexture>
    ): BindResult {
        val rm = engine.renderableManager
        val materials = parseResult.materials.associateBy { it.materialIndex }
        val uploaded = HashMap<Int, Texture?>()
        val textured = HashSet<Int>()
        val handled = HashSet<MaterialInstance>()
        for (p in primitives) {
            val info = materials[p.ref.materialIndex] ?: continue
            val texIndex = info.baseColorTextureIndex ?: continue
            val texture = uploaded.getOrPut(texIndex) { decoded[texIndex]?.let { upload(engine, it) } } ?: continue
            val mi = runCatching { rm.getMaterialInstanceAt(rm.getInstance(p.entity), p.ref.primitiveIndex) }.getOrNull() ?: continue
            if (!handled.add(mi)) continue
            runCatching {
                mi.setParameter("baseColorMap", texture, SAMPLER)
                // gltfio's ubershaders only sample baseColorMap when
                // baseColorIndex (the UV set) is >= 0, and use the UV
                // matrix — both were left at "no texture" values because
                // the glTF no longer references one.
                mi.setParameter("baseColorIndex", info.baseColorTexCoord)
                mi.setParameter(
                    "baseColorUvMatrix", MaterialInstance.FloatElement.MAT3,
                    floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 0, 1
                )
                val f = info.baseColorFactor
                mi.setParameter("baseColorFactor", f[0], f[1], f[2], f[3]) // linear, like glTF
                textured.add(info.materialIndex)
            }.onFailure { Log.w(TAG, "Binding '${info.name}' failed: ${it.message}") }
        }
        val textures = uploaded.values.filterNotNull()
        Log.i(TAG, "Textured ${textured.size} materials with ${textures.size} textures")
        return BindResult(textured.size, textures)
    }

    private fun upload(engine: Engine, d: DecodedTexture): Texture? = try {
        val texture = Texture.Builder()
            .width(d.width)
            .height(d.height)
            .levels(d.levels.size)
            .format(Texture.InternalFormat.SRGB8_A8)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .build(engine)
        for ((level, pixels) in d.levels.withIndex()) {
            texture.setImage(engine, level, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE))
        }
        texture
    } catch (e: Exception) {
        Log.e(TAG, "Texture upload failed", e)
        null
    }
}
