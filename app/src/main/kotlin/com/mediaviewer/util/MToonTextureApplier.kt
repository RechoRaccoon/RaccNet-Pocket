package com.mediaviewer.util

import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Texture
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
 */
object MToonTextureApplier {
    private const val TAG = "MToonApplier"

    /**
     * Applies textures to the asset's materials. Call after loadModelGlb
     * completes and the asset is available.
     *
     * @param engine The Filament engine
     * @param asset The loaded Filament asset
     * @param parseResult The parsed MToon material/texture data
     * @return Number of materials that got textures applied
     */
    fun applyTextures(
        engine: Engine,
        asset: FilamentAsset,
        parseResult: MToonMaterialParser.ParseResult
    ): Int {
        var applied = 0
        try {
            // Material instances live on FilamentInstance, not FilamentAsset
            // (Filament 1.51.6: FilamentAsset.getInstance().getMaterialInstances()).
            val materialInstances = asset.instance.materialInstances
            Log.i(TAG, "Asset has ${materialInstances.size} material instances, " +
                    "${parseResult.materials.size} parsed materials")

            // Map glTF material index -> Filament material instance.
            // getMaterialInstances() returns them in glTF material order.
            if (materialInstances.size != parseResult.materials.size) {
                Log.w(TAG, "Material count mismatch: Filament=${materialInstances.size}, " +
                        "parsed=${parseResult.materials.size}. Attempting best-effort mapping.")
            }

            for (matInfo in parseResult.materials) {
                val texIndex = matInfo.baseColorTextureIndex ?: continue
                val imageBytes = parseResult.textureBytes[texIndex] ?: continue

                // Find the corresponding Filament material instance
                // (index-based mapping; may be fragile but it's the best we have)
                if (matInfo.materialIndex >= materialInstances.size) continue
                val materialInstance = materialInstances[matInfo.materialIndex]

                // Create Filament texture from image bytes
                val texture = createTextureFromBytes(engine, imageBytes) ?: continue

                // Try to set the base color map. Filament's standard PBR
                // material uses "baseColorMap" parameter name.
                try {
                    val sampler = Texture.Sampler(Texture.Sampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    materialInstance.setParameter("baseColorMap", texture, sampler)
                    // Also set base color factor if not white
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
                    texture.destroy(engine)
                }
            }

            Log.i(TAG, "Applied textures to $applied/${parseResult.materials.size} materials")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply MToon textures", e)
        }
        return applied
    }

    /**
     * Creates a Filament Texture from PNG/JPEG bytes.
     * Decodes via BitmapFactory, then uploads as RGBA8.
     */
    private fun createTextureFromBytes(engine: Engine, bytes: ByteArray): Texture? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                Log.e(TAG, "BitmapFactory failed to decode ${bytes.size} bytes")
                return null
            }

            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()

            // Convert ARGB_8888 to RGBA bytes (Filament expects RGBA)
            val rgbaBytes = ByteBuffer.allocateDirect(width * height * 4)
            for (pixel in pixels) {
                rgbaBytes.put(((pixel shr 16) and 0xFF).toByte()) // R
                rgbaBytes.put(((pixel shr 8) and 0xFF).toByte())  // G
                rgbaBytes.put((pixel and 0xFF).toByte())          // B
                rgbaBytes.put(((pixel shr 24) and 0xFF).toByte()) // A
            }
            rgbaBytes.flip()

            val texture = Texture.Builder()
                .width(width)
                .height(height)
                .levels(1)
                .format(Texture.InternalFormat.RGBA8)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .build(engine)

            val buffer = Texture.PixelBufferDescriptor(
                rgbaBytes,
                Texture.Format.RGBA,
                Texture.Type.UBYTE
            )
            texture.setImage(engine, 0, buffer)
            texture
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create texture from bytes", e)
            null
        }
    }
}
