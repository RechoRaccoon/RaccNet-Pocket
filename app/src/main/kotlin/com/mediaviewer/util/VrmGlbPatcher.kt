package com.mediaviewer.util

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites a `.vrm`'s glTF JSON chunk so Filament's gltfio renders it the
 * way VRM viewers (UniVRM, VSeeFace, three-vrm) do, instead of as a
 * near-black silhouette. Runs once per load, off the main thread, and
 * writes straight into the direct buffer `loadModelGlb` consumes. The BIN
 * chunk (meshes, textures) is copied through untouched.
 *
 * ## Why the avatar rendered black
 * (Point 4, textures, is further down at [detachTextures].)
 *
 * Three separate glTF-vs-VRM mismatches all produce "black with a few
 * specular glints" (exactly the screenshot), so all three are handled:
 *
 * 1. **Vertex colors.** glTF says `COLOR_0` multiplies base color, and
 *    gltfio obeys. Unity/VRChat-derived avatars very often carry vertex
 *    colors that are really shader masks (outline width, emission masks —
 *    frequently black). Unity's Standard shader and MToon both *ignore*
 *    vertex color, so the avatar looks fine everywhere except a strict
 *    glTF renderer. Stripping `COLOR_0` matches what the avatar's author
 *    actually saw. Specular highlights aren't multiplied by vertex color,
 *    which is why the hair still showed glints.
 * 2. **Default metallic = 1.** glTF's default `metallicFactor` is 1.0 when
 *    the key is absent. A fully metallic surface has no diffuse term, and
 *    with no reflection cubemap in this scene it reflects nothing — black,
 *    except direct-light highlights. Exporters that omit the key mean
 *    "not set", not "chrome", so it's written as 0.
 * 3. **MToon.** gltfio can't run MToon. The VRM 1.0 spec itself says the
 *    fallback for an MToon material is `KHR_materials_unlit` (flat,
 *    texture-accurate color), which gltfio *does* support — so every MToon
 *    material (VRM 0.x: `extensions.VRM.materialProperties[].shader`;
 *    VRM 1.0: `VRMC_materials_mtoon` on the material) is marked unlit.
 *    That's the closest match to MToon's flat anime shading and makes the
 *    texture visible regardless of how the scene is lit.
 */
object VrmGlbPatcher {
    private const val TAG = "VrmGlbPatcher"
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val UNLIT = "KHR_materials_unlit"

    class Stats(
        val unlitMaterials: Int = 0,
        val metallicFixed: Int = 0,
        val vertexColorsStripped: Int = 0,
        val texturesDetached: Int = 0,
        val unlitToLit: Int = 0
    ) {
        val changedAnything get() = unlitMaterials + metallicFixed + vertexColorsStripped + texturesDetached + unlitToLit > 0
        override fun toString() =
            "$unlitMaterials made unlit, $unlitToLit unlit→lit, $metallicFixed metallic fixed, " +
                "$vertexColorsStripped vertex-color prims stripped, $texturesDetached texture refs taken over"
    }

    private val TEXTURE_KEYS = listOf("normalTexture", "occlusionTexture", "emissiveTexture")
    private val PBR_TEXTURE_KEYS = listOf("baseColorTexture", "metallicRoughnessTexture")

    /**
     * 4. **Textures.** Every texture reference is removed from the
     *    materials, so gltfio's asynchronous loader never creates a texture
     *    whose pixels might not arrive (black / solid-red avatars). The
     *    base colors are decoded and bound synchronously by
     *    [MToonTextureApplier] instead; normal/occlusion/metal maps are
     *    dropped (MToon-as-unlit ignores them anyway), and an emissive
     *    factor that only made sense through its (now gone) mask texture is
     *    zeroed so nothing glows. Returns how many references were removed.
     */
    private fun detachTextures(mat: JSONObject): Int {
        var removed = 0
        mat.optJSONObject("pbrMetallicRoughness")?.let { pbr ->
            for (k in PBR_TEXTURE_KEYS) if (pbr.remove(k) != null) {
                removed++
                if (k == "metallicRoughnessTexture") pbr.put("metallicFactor", 0.0)
            }
        }
        for (k in TEXTURE_KEYS) if (mat.remove(k) != null) {
            removed++
            if (k == "emissiveTexture") mat.remove("emissiveFactor")
        }
        // Texture-carrying material extensions (clearcoat, sheen, …): keep
        // only the ones that don't load images.
        mat.optJSONObject("extensions")?.let { ext ->
            ext.keys().asSequence().toList()
                .filter { it.startsWith("KHR_materials_") && it != UNLIT && it != "KHR_materials_emissive_strength" }
                .forEach { if (ext.remove(it) != null) removed++ }
        }
        return removed
    }

    class Result(val buffer: ByteBuffer, val stats: Stats)

    /** Returns a native-order direct buffer ready for `loadModelGlb`. Never
     *  throws: on any parsing problem it returns the original bytes as-is. */
    /**
     * How materials are shaded.
     *  - LIT (default): every material responds to the scene lights. VRM
     *    exporters mark MToon materials (face, hair…) KHR_materials_unlit
     *    as their fallback, which made those parts ignore the lights
     *    entirely while the clothes were lit — "the light doesn't hit the
     *    face". Here that flag is removed and they become matte,
     *    non-metallic surfaces like everything else.
     *  - FULL_BRIGHT: every material unlit — pure texture colors, no
     *    shading or shadows at all.
     */
    enum class Lighting { LIT, FULL_BRIGHT }

    fun patchToDirectBuffer(glb: ByteArray, lighting: Lighting = Lighting.LIT): Result {
        return runCatching { patch(glb, lighting) }
            .onFailure { Log.e(TAG, "Patching failed — loading the file unmodified", it) }
            .getOrNull() ?: Result(copyToDirect(glb), Stats())
    }

    private fun patch(glb: ByteArray, lighting: Lighting): Result? {
        if (glb.size < 20) return null
        val header = ByteBuffer.wrap(glb, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != GLB_MAGIC) return null
        val version = header.int

        // First chunk must be JSON per the GLB spec.
        val c0 = ByteBuffer.wrap(glb, 12, 8).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = c0.int
        if (c0.int != CHUNK_JSON || 20 + jsonLength > glb.size) return null
        val restOffset = 20 + jsonLength // BIN (and any other) chunks, copied verbatim

        val root = JSONObject(String(glb, 20, jsonLength, Charsets.UTF_8))
        val stats = patchJson(root, lighting)
        if (!stats.changedAnything) return Result(copyToDirect(glb), stats)

        var jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
        val pad = (4 - jsonBytes.size % 4) % 4
        if (pad > 0) jsonBytes = jsonBytes + ByteArray(pad) { 0x20 } // spaces, per spec

        val restLength = glb.size - restOffset
        val total = 12 + 8 + jsonBytes.size + restLength
        val out = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(GLB_MAGIC).putInt(version).putInt(total)
        out.putInt(jsonBytes.size).putInt(CHUNK_JSON).put(jsonBytes)
        out.put(glb, restOffset, restLength)
        out.flip()
        Log.i(TAG, "Patched VRM materials: $stats")
        return Result(out.order(ByteOrder.nativeOrder()), stats)
    }

    private fun patchJson(root: JSONObject, lighting: Lighting): Stats {
        val materials = root.optJSONArray("materials") ?: JSONArray()

        // VRM 0.x keeps MToon parameters in one top-level list, matched to
        // glTF materials by name.
        // UniVRM writes that list in the same order as `materials`, so when
        // names don't line up (renamed on export, "(Instance)" suffixes…)
        // the entry at the same index is used.
        val vrm0ToonNames = HashSet<String>()
        val vrm0ToonIndices = HashSet<Int>()
        root.optJSONObject("extensions")?.optJSONObject("VRM")?.optJSONArray("materialProperties")?.let { props ->
            val aligned = props.length() == materials.length()
            for (i in 0 until props.length()) {
                val p = props.optJSONObject(i) ?: continue
                val shader = p.optString("shader", "")
                if (shader.contains("MToon", ignoreCase = true) || shader.contains("Unlit", ignoreCase = true)) {
                    vrm0ToonNames.add(p.optString("name", ""))
                    if (aligned) vrm0ToonIndices.add(i)
                }
            }
        }

        var unlit = 0
        var metallic = 0
        var detached = 0
        var litFromUnlit = 0
        for (i in 0 until materials.length()) {
            val mat = materials.optJSONObject(i) ?: continue
            detached += detachTextures(mat)
            val ext = mat.optJSONObject("extensions")
            val isToon = ext?.has("VRMC_materials_mtoon") == true ||
                ext?.has("VRMC_materials_mtoon-1.0") == true ||
                mat.optString("name", "\u0000") in vrm0ToonNames ||
                i in vrm0ToonIndices
            if (lighting == Lighting.FULL_BRIGHT) {
                if (ext?.has(UNLIT) != true) {
                    val e = ext ?: JSONObject().also { mat.put("extensions", it) }
                    e.put(UNLIT, JSONObject())
                    unlit++
                }
            } else {
                // Lit: drop any unlit fallback so the lights reach this part.
                if (ext?.remove(UNLIT) != null) litFromUnlit++
                val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    ?: JSONObject().also { mat.put("pbrMetallicRoughness", it) }
                // Toon materials: matte and non-metallic whatever the fallback
                // PBR values say. Others: only fill in the missing default
                // (glTF's metallic = 1 would be black without reflections).
                if (isToon || !pbr.has("metallicFactor")) {
                    pbr.put("metallicFactor", 0.0)
                    metallic++
                }
                if (isToon) pbr.put("roughnessFactor", 0.9)
            }
        }
        if (unlit > 0) addExtensionUsed(root, UNLIT)

        var stripped = 0
        root.optJSONArray("meshes")?.let { meshes ->
            for (m in 0 until meshes.length()) {
                val prims = meshes.optJSONObject(m)?.optJSONArray("primitives") ?: continue
                for (p in 0 until prims.length()) {
                    val prim = prims.optJSONObject(p) ?: continue
                    var touched = false
                    prim.optJSONObject("attributes")?.let { attrs ->
                        val keys = attrs.keys().asSequence().filter { it.startsWith("COLOR_") }.toList()
                        keys.forEach { attrs.remove(it) }
                        if (keys.isNotEmpty()) touched = true
                    }
                    prim.optJSONArray("targets")?.let { targets ->
                        for (t in 0 until targets.length()) {
                            val target = targets.optJSONObject(t) ?: continue
                            target.keys().asSequence().filter { it.startsWith("COLOR_") }.toList()
                                .forEach { target.remove(it) }
                        }
                    }
                    if (touched) stripped++
                }
            }
        }
        return Stats(unlit, metallic, stripped, detached, litFromUnlit)
    }

    private fun addExtensionUsed(root: JSONObject, name: String) {
        val used = root.optJSONArray("extensionsUsed") ?: JSONArray().also { root.put("extensionsUsed", it) }
        for (i in 0 until used.length()) if (used.optString(i) == name) return
        used.put(name)
    }

    private fun copyToDirect(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
}
