package com.mediaviewer.util

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses MToon material data from a VRM (GLB) file.
 *
 * VRM files use the MToon shader (VRMC_materials_mtoon extension), which
 * Filament's gltfio does NOT support. When gltfio encounters MToon materials,
 * it fails to set up textures correctly — the model loads (geometry/bones
 * work) but renders without textures.
 *
 * This parser extracts the base color texture information from MToon
 * materials so we can manually apply them to Filament materials after
 * loadModelGlb completes.
 *
 * Supports both VRM 0.x (VRMC_materials_mtoon) and VRM 1.0 (VRMC_materials_mtoon-1.0).
 */
object MToonMaterialParser {
    private const val TAG = "MToonParser"

    data class MToonMaterialInfo(
        val materialIndex: Int,
        val name: String,
        /** glTF texture index for the base color (main) texture, or null */
        val baseColorTextureIndex: Int?,
        /** Base color factor [r, g, b, a], defaults to white */
        val baseColorFactor: FloatArray,
        /** Whether this material uses MToon (vs standard PBR) */
        val isMToon: Boolean
    )

    data class TextureInfo(
        /** glTF image index */
        val imageIndex: Int,
        /** Buffer view index containing the image data */
        val bufferViewIndex: Int,
        /** MIME type (image/png, image/jpeg) */
        val mimeType: String?
    )

    /**
     * Parses the GLB and returns MToon material info plus texture metadata.
     * Returns null if the bytes aren't a valid GLB or have no materials.
     */
    fun parse(glbBytes: ByteArray): ParseResult? {
        try {
            // GLB header: magic (4), version (4), length (4)
            if (glbBytes.size < 12) return null
            val header = ByteBuffer.wrap(glbBytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
            val magic = header.int
            if (magic != 0x46546C67) { // "glTF"
                Log.e(TAG, "Not a GLB file (bad magic)")
                return null
            }

            // Chunks: each has length (4), type (4), data
            var offset = 12
            var jsonString: String? = null
            var binChunkOffset = -1
            var binChunkLength = 0

            while (offset + 8 <= glbBytes.size) {
                val chunkHeader = ByteBuffer.wrap(glbBytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
                val chunkLength = chunkHeader.int
                val chunkType = chunkHeader.int
                offset += 8

                if (chunkType == 0x4E4F534A) { // "JSON"
                    jsonString = String(glbBytes, offset, chunkLength, Charsets.UTF_8)
                } else if (chunkType == 0x004E4942) { // "BIN"
                    binChunkOffset = offset
                    binChunkLength = chunkLength
                }
                offset += chunkLength
            }

            if (jsonString == null) {
                Log.e(TAG, "GLB has no JSON chunk")
                return null
            }

            val json = JSONObject(jsonString)
            val materials = json.optJSONArray("materials") ?: return null
            val textures = json.optJSONArray("textures")
            val images = json.optJSONArray("images")
            val bufferViews = json.optJSONArray("bufferViews")

            val materialInfos = mutableListOf<MToonMaterialInfo>()
            for (i in 0 until materials.length()) {
                val mat = materials.getJSONObject(i)
                val name = mat.optString("name", "material_$i")

                // Check for MToon extensions (both 0.x and 1.0)
                val extensions = mat.optJSONObject("extensions")
                val mtoon0x = extensions?.optJSONObject("VRMC_materials_mtoon")
                val mtoon10 = extensions?.optJSONObject("VRMC_materials_mtoon-1.0")
                val isMToon = mtoon0x != null || mtoon10 != null

                var baseColorTextureIndex: Int? = null
                var baseColorFactor = floatArrayOf(1f, 1f, 1f, 1f)

                if (isMToon) {
                    // MToon: base color texture is in the extension
                    val mtoon = mtoon0x ?: mtoon10!!
                    // VRM 0.x: "mainTexture", VRM 1.0: "mainTexture" in different location
                    // Try multiple paths
                    baseColorTextureIndex = mtoon.optJSONObject("mainTexture")?.optInt("index")
                        ?: mtoon.optInt("mainTextureIndex", -1).takeIf { it >= 0 }
                    val colorArray = mtoon.optJSONArray("mainColor")
                    if (colorArray != null && colorArray.length() >= 3) {
                        baseColorFactor = floatArrayOf(
                            colorArray.optDouble(0, 1.0).toFloat(),
                            colorArray.optDouble(1, 1.0).toFloat(),
                            colorArray.optDouble(2, 1.0).toFloat(),
                            colorArray.optDouble(3, 1.0).toFloat()
                        )
                    }
                } else {
                    // Standard PBR: check pbrMetallicRoughness
                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    if (pbr != null) {
                        baseColorTextureIndex = pbr.optJSONObject("baseColorTexture")?.optInt("index")
                        val colorArray = pbr.optJSONArray("baseColorFactor")
                        if (colorArray != null && colorArray.length() >= 3) {
                            baseColorFactor = floatArrayOf(
                                colorArray.optDouble(0, 1.0).toFloat(),
                                colorArray.optDouble(1, 1.0).toFloat(),
                                colorArray.optDouble(2, 1.0).toFloat(),
                                colorArray.optDouble(3, 1.0).toFloat()
                            )
                        }
                    }
                }

                // Fallback: if MToon but no texture in extension, check PBR fallback
                if (baseColorTextureIndex == null) {
                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    baseColorTextureIndex = pbr?.optJSONObject("baseColorTexture")?.optInt("index")
                }

                materialInfos.add(MToonMaterialInfo(
                    materialIndex = i,
                    name = name,
                    baseColorTextureIndex = baseColorTextureIndex,
                    baseColorFactor = baseColorFactor,
                    isMToon = isMToon
                ))
            }

            // Parse texture -> image -> bufferView mapping
            val textureInfos = mutableMapOf<Int, TextureInfo>()
            if (textures != null && images != null && bufferViews != null) {
                for (i in 0 until textures.length()) {
                    val tex = textures.getJSONObject(i)
                    val imageIndex = tex.optInt("source", -1)
                    if (imageIndex >= 0 && imageIndex < images.length()) {
                        val img = images.getJSONObject(imageIndex)
                        val bufferViewIndex = img.optInt("bufferView", -1)
                        val mimeType = img.optString("mimeType", null)
                        if (bufferViewIndex >= 0) {
                            textureInfos[i] = TextureInfo(imageIndex, bufferViewIndex, mimeType)
                        }
                    }
                }
            }

            // Extract image bytes from BIN chunk
            val imageBytes = mutableMapOf<Int, ByteArray>()
            if (binChunkOffset >= 0 && bufferViews != null) {
                for ((texIndex, texInfo) in textureInfos) {
                    if (texInfo.bufferViewIndex < bufferViews.length()) {
                        val bv = bufferViews.getJSONObject(texInfo.bufferViewIndex)
                        val byteOffset = bv.optInt("byteOffset", 0)
                        val byteLength = bv.optInt("byteLength", 0)
                        if (byteLength > 0 && binChunkOffset + byteOffset + byteLength <= glbBytes.size) {
                            imageBytes[texIndex] = glbBytes.copyOfRange(
                                binChunkOffset + byteOffset,
                                binChunkOffset + byteOffset + byteLength
                            )
                        }
                    }
                }
            }

            Log.i(TAG, "Parsed ${materialInfos.size} materials (${materialInfos.count { it.isMToon }} MToon), ${imageBytes.size} textures")
            return ParseResult(materialInfos, imageBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse MToon materials", e)
            return null
        }
    }

    data class ParseResult(
        val materials: List<MToonMaterialInfo>,
        /** Map of glTF texture index -> image bytes (PNG/JPEG) */
        val textureBytes: Map<Int, ByteArray>
    )
}
