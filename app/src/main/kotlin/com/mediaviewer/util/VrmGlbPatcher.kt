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
        val vertexColorsStripped: Int = 0
    ) {
        val changedAnything get() = unlitMaterials + metallicFixed + vertexColorsStripped > 0
        override fun toString() =
            "$unlitMaterials toon→unlit, $metallicFixed metallic fixed, $vertexColorsStripped vertex-color prims stripped"
    }

    class Result(val buffer: ByteBuffer, val stats: Stats)

    /** Returns a native-order direct buffer ready for `loadModelGlb`. Never
     *  throws: on any parsing problem it returns the original bytes as-is. */
    fun patchToDirectBuffer(glb: ByteArray): Result {
        return runCatching { patch(glb) }
            .onFailure { Log.e(TAG, "Patching failed — loading the file unmodified", it) }
            .getOrNull() ?: Result(copyToDirect(glb), Stats())
    }

    private fun patch(glb: ByteArray): Result? {
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
        val stats = patchJson(root)
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

    private fun patchJson(root: JSONObject): Stats {
        val materials = root.optJSONArray("materials") ?: JSONArray()

        // VRM 0.x keeps MToon parameters in one top-level list, matched to
        // glTF materials by name.
        val vrm0ToonNames = HashSet<String>()
        root.optJSONObject("extensions")?.optJSONObject("VRM")?.optJSONArray("materialProperties")?.let { props ->
            for (i in 0 until props.length()) {
                val p = props.optJSONObject(i) ?: continue
                val shader = p.optString("shader", "")
                if (shader.contains("MToon", ignoreCase = true) || shader.contains("Unlit", ignoreCase = true)) {
                    vrm0ToonNames.add(p.optString("name", ""))
                }
            }
        }

        var unlit = 0
        var metallic = 0
        for (i in 0 until materials.length()) {
            val mat = materials.optJSONObject(i) ?: continue
            val ext = mat.optJSONObject("extensions")
            val isToon = ext?.has("VRMC_materials_mtoon") == true ||
                ext?.has("VRMC_materials_mtoon-1.0") == true ||
                mat.optString("name", "\u0000") in vrm0ToonNames
            if (isToon) {
                if (ext?.has(UNLIT) != true) {
                    val e = ext ?: JSONObject().also { mat.put("extensions", it) }
                    e.put(UNLIT, JSONObject())
                    unlit++
                }
            } else if (ext?.has(UNLIT) != true) {
                val pbr = mat.optJSONObject("pbrMetallicRoughness")
                    ?: JSONObject().also { mat.put("pbrMetallicRoughness", it) }
                if (!pbr.has("metallicFactor") && !pbr.has("metallicRoughnessTexture")) {
                    pbr.put("metallicFactor", 0.0)
                    metallic++
                }
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
        return Stats(unlit, metallic, stripped)
    }

    private fun addExtensionUsed(root: JSONObject, name: String) {
        val used = root.optJSONArray("extensionsUsed") ?: JSONArray().also { root.put("extensionsUsed", it) }
        for (i in 0 until used.length()) if (used.optString(i) == name) return
        used.put(name)
    }

    private fun copyToDirect(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
}
