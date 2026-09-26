package com.mediaviewer.util

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a `.vrm`'s glTF JSON (the ORIGINAL bytes, before [VrmGlbPatcher])
 * and extracts everything needed to texture it ourselves:
 *  - each material's base-color texture (plain glTF, VRM 1.0 MToon, or the
 *    VRM 0.x `materialProperties._MainTex` fallback), its UV set and color;
 *  - the embedded image bytes for those textures;
 *  - which (node, primitive) uses which material, so the texture lands on
 *    exactly the MaterialInstance gltfio made for it — this is also the
 *    list of "parts" the VRM settings let you hide.
 *
 * ## Why we texture everything ourselves now
 * Leaving base colors to gltfio's asynchronous texture loader gave "all
 * black" or "all red" avatars: its textures are created and bound to the
 * materials immediately, but their pixels only arrive later from a
 * background decode of the full-size (often 4096px, mipmapped) images. When
 * that decode is slow or runs out of memory, the GPU samples uninitialised
 * texture memory — black on some drivers, a solid color on others. So
 * [VrmGlbPatcher] now removes every texture reference from the materials
 * (gltfio decodes nothing), and [MToonTextureApplier] decodes the base
 * colors here, downsampled, and binds them synchronously before the first
 * frame is drawn.
 */
object MToonMaterialParser {
    private const val TAG = "MToonParser"

    data class MToonMaterialInfo(
        val materialIndex: Int,
        val name: String,
        /** glTF texture index for the base color (main) texture, or null. */
        val baseColorTextureIndex: Int?,
        /** Which UV set the base color texture uses (TEXCOORD_n). */
        val baseColorTexCoord: Int,
        /** Base color factor [r, g, b, a], defaults to white. */
        val baseColorFactor: FloatArray,
        /** Whether this material uses MToon (vs standard PBR). */
        val isMToon: Boolean
    ) {
        /** Every textured material is bound by us (see the file doc). */
        val needsManualBinding: Boolean get() = baseColorTextureIndex != null
    }

    /**
     * One mesh primitive. gltfio makes one renderable per mesh node, named
     * after the node (or, for an unnamed node, its mesh), with primitives in
     * glTF order — so ([entityName], [occurrence], [primitiveIndex]) pins
     * down exactly which MaterialInstance a glTF material became.
     */
    data class PrimitiveMaterialRef(
        val nodeName: String,
        val primitiveIndex: Int,
        val materialIndex: Int,
        val nodeIndex: Int = -1,
        /** Which of several same-named nodes this is (0 = first). */
        val occurrence: Int = 0,
        val primitiveCount: Int = 1,
        val materialName: String = ""
    )

    /** Where one encoded image (PNG/JPEG/WebP) sits inside the .vrm bytes.
     *  Decoded straight from there — never copied out (memory; see
     *  [MToonTextureApplier]). */
    class ImageSlice(val offset: Int, val length: Int)

    data class ParseResult(
        val materials: List<MToonMaterialInfo>,
        /** glTF texture index -> its encoded image inside the file. */
        val textureSlices: Map<Int, ImageSlice>,
        val primitiveMaterials: List<PrimitiveMaterialRef> = emptyList(),
        /** Spring bones (hair/ear/tail physics), if the file has any. */
        val springs: VrmSpringBones.SpringData? = null
    )

    fun parse(glbBytes: ByteArray): ParseResult? = try {
        parseInternal(glbBytes)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse VRM materials", e)
        null
    }

    private fun parseInternal(glbBytes: ByteArray): ParseResult? {
        if (glbBytes.size < 12) return null
        val header = ByteBuffer.wrap(glbBytes, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != 0x46546C67) { // "glTF"
            Log.e(TAG, "Not a GLB file (bad magic)")
            return null
        }

        var offset = 12
        var jsonString: String? = null
        var binOffset = -1
        var binLength = 0
        while (offset + 8 <= glbBytes.size) {
            val chunk = ByteBuffer.wrap(glbBytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
            val length = chunk.int
            val type = chunk.int
            offset += 8
            if (length < 0 || offset + length > glbBytes.size) break
            when (type) {
                0x4E4F534A -> jsonString = String(glbBytes, offset, length, Charsets.UTF_8) // JSON
                0x004E4942 -> { binOffset = offset; binLength = length }                   // BIN
            }
            offset += length
        }
        val json = JSONObject(jsonString ?: return null)
        val materials = json.optJSONArray("materials") ?: JSONArray()
        val textures = json.optJSONArray("textures")
        val images = json.optJSONArray("images")
        val bufferViews = json.optJSONArray("bufferViews")

        // VRM 0.x MToon: per-material main texture/color, matched by name.
        val vrm0Props = HashMap<String, JSONObject>()
        json.optJSONObject("extensions")?.optJSONObject("VRM")?.optJSONArray("materialProperties")?.let { props ->
            for (i in 0 until props.length()) {
                val p = props.optJSONObject(i) ?: continue
                vrm0Props[p.optString("name", "")] = p
            }
        }

        val infos = ArrayList<MToonMaterialInfo>()
        for (i in 0 until materials.length()) {
            val mat = materials.optJSONObject(i) ?: continue
            val name = mat.optString("name", "material_$i")
            val ext = mat.optJSONObject("extensions")
            val mtoon1 = ext?.optJSONObject("VRMC_materials_mtoon") ?: ext?.optJSONObject("VRMC_materials_mtoon-1.0")
            val vrm0 = vrm0Props[name]
            val isMToon = mtoon1 != null || vrm0?.optString("shader", "")?.contains("MToon", true) == true

            val pbr = mat.optJSONObject("pbrMetallicRoughness")
            var texIndex: Int? = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1)?.takeIf { it >= 0 }
            var texCoord = pbr?.optJSONObject("baseColorTexture")?.optInt("texCoord", 0) ?: 0
            var factor = floatArrayOf(1f, 1f, 1f, 1f)
            pbr?.optJSONArray("baseColorFactor")?.let { factor = color(it, factor) }

            if (texIndex == null && mtoon1 != null) {
                // Pre-release MToon 1.0 exporters wrote their own keys.
                texIndex = mtoon1.optJSONObject("mainTexture")?.optInt("index", -1)?.takeIf { it >= 0 }
                    ?: mtoon1.optInt("mainTextureIndex", -1).takeIf { it >= 0 }
                mtoon1.optJSONObject("mainTexture")?.let { texCoord = it.optInt("texCoord", 0) }
                mtoon1.optJSONArray("mainColor")?.let { factor = color(it, factor) }
            }
            if (texIndex == null && vrm0 != null) {
                texIndex = vrm0.optJSONObject("textureProperties")?.optInt("_MainTex", -1)?.takeIf { it >= 0 }
                texCoord = 0
                if (pbr?.has("baseColorFactor") != true) {
                    vrm0.optJSONObject("vectorProperties")?.optJSONArray("_Color")?.let { factor = color(it, factor) }
                }
            }
            if (texIndex != null && textures != null && texIndex >= textures.length()) texIndex = null

            infos.add(MToonMaterialInfo(i, name, texIndex, texCoord.coerceIn(0, 1), factor, isMToon))
        }

        // Texture -> image -> bufferView -> location in the file.
        val needed = infos.mapNotNull { it.baseColorTextureIndex }.toSet()
        val imageSlices = HashMap<Int, ImageSlice>()
        if (textures != null && images != null && bufferViews != null && binOffset >= 0) {
            for (texIndex in needed) {
                val tex = textures.optJSONObject(texIndex) ?: continue
                // Plain `source`, or a basisu/webp extension source (the
                // latter BitmapFactory can read on API 28+).
                val source = tex.optInt("source", -1).takeIf { it >= 0 }
                    ?: tex.optJSONObject("extensions")?.optJSONObject("EXT_texture_webp")?.optInt("source", -1)?.takeIf { it >= 0 }
                    ?: continue
                val img = images.optJSONObject(source) ?: continue
                val bvIndex = img.optInt("bufferView", -1)
                val bv = bufferViews.optJSONObject(bvIndex) ?: continue
                if (bv.optInt("buffer", 0) != 0) continue
                val start = binOffset + bv.optInt("byteOffset", 0)
                val length = bv.optInt("byteLength", 0)
                if (length > 0 && start + length <= binOffset + binLength) {
                    imageSlices[texIndex] = ImageSlice(start, length)
                }
            }
        }

        // Node -> primitives, named the way gltfio names its entities.
        val refs = ArrayList<PrimitiveMaterialRef>()
        val nodes = json.optJSONArray("nodes")
        val meshes = json.optJSONArray("meshes")
        if (nodes != null && meshes != null) {
            val seen = HashMap<String, Int>()
            for (n in 0 until nodes.length()) {
                val node = nodes.optJSONObject(n) ?: continue
                val meshIndex = node.optInt("mesh", -1)
                val mesh = meshes.optJSONObject(meshIndex) ?: continue
                val name = node.optString("name", "").ifEmpty { mesh.optString("name", "") }
                if (name.isEmpty()) continue
                val occurrence = seen[name] ?: 0
                seen[name] = occurrence + 1
                val prims = mesh.optJSONArray("primitives") ?: continue
                for (pi in 0 until prims.length()) {
                    val matIndex = prims.optJSONObject(pi)?.optInt("material", -1) ?: -1
                    refs.add(PrimitiveMaterialRef(
                        nodeName = name, primitiveIndex = pi, materialIndex = matIndex,
                        nodeIndex = n, occurrence = occurrence, primitiveCount = prims.length(),
                        materialName = materials.optJSONObject(matIndex)?.optString("name", "") ?: ""
                    ))
                }
            }
        }

        Log.i(TAG, "Parsed ${infos.size} materials (${infos.count { it.isMToon }} MToon), " +
            "${imageSlices.size}/${needed.size} base textures, ${refs.size} primitives")
        return ParseResult(infos, imageSlices, refs, VrmSpringBones.parse(json))
    }

    private fun color(a: JSONArray, fallback: FloatArray): FloatArray =
        if (a.length() < 3) fallback else floatArrayOf(
            a.optDouble(0, 1.0).toFloat(), a.optDouble(1, 1.0).toFloat(),
            a.optDouble(2, 1.0).toFloat(), a.optDouble(3, 1.0).toFloat()
        )
}
