package com.mediaviewer.util

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * VRM pipeline step 4 (see VrmModeScreen.kt's doc comment / the handoff's
 * "VRM: what's next" section): parses just the two things retargeting
 * (step 6) and expression puppeting actually need out of a `.vrm` file —
 * "bone-name-to-node-index and expression-name-to-morph-target-index
 * maps," per the handoff's own scoping of this step. Deliberately **not**
 * a full VRM spec implementation: no meta/license/spring-bone/material
 * parsing, because nothing downstream of this needs it. Step 5's Filament
 * loader (`filament-utils-android`'s glTF loader) parses the *rest* of the
 * same file — geometry, materials, the node hierarchy itself — on its own;
 * this class only needs to answer "given this glTF's own node indices,
 * which one is the head bone, and which (mesh, morph-target-index) pairs
 * make the avatar smile."
 *
 * A `.vrm` file is a binary glTF (`.glb`) container with an extra VRM
 * extension block in its JSON chunk — see [GlbReader] for the container
 * format and [VrmParser] for the VRM-specific block.
 *
 * ## Unverified
 * Same caveat as every file in this pipeline: written against the
 * documented VRM 0.x (`vrm-specification/specification/0.0`) and VRM 1.0
 * (`VRMC_vrm-1.0`) JSON schemas from memory, with no compiler or sample
 * `.vrm` file to test against in this sandbox. The container-format parts
 * ([GlbReader]) are the plain glTF 2.0 binary spec, which is small and
 * stable and the part I'm most confident in; the VRM-extension field names
 * are the part most likely to have a typo Android Studio's compiler won't
 * catch (Gson navigation fails silently — see [JsonObject.at] below — so a
 * wrong field name degrades to "bone/expression missing" rather than a
 * build error). Test against a real exported `.vrm` file (e.g. one of the
 * official samples at github.com/vrm-c/vrm-specification/tree/master/samples)
 * before trusting the maps this produces.
 */

/** One glTF node's contribution to one expression: which mesh's morph
 *  target to drive, and how far ([weight]) driving it all the way to 1.0
 *  should move that morph target. [nodeIndex] is a **glTF node** index
 *  (matches [VrmData.humanBones] and whatever Filament's loader in step 5
 *  hands back for the same file), already resolved from VRM 0.x's raw
 *  mesh-index binds — see [VrmParser]'s VRM 0.x branch for why that
 *  resolution is needed at all. */
data class MorphTargetBind(val nodeIndex: Int, val morphTargetIndex: Int, val weight: Float)

enum class VrmSpecVersion { VRM_0, VRM_1 }

/**
 * The parsed result. [humanBones] keys are the spec's own bone name
 * strings (`"hips"`, `"leftUpperArm"`, `"head"`, ...) — VRM 0.x and 1.0
 * use the same bone-name strings for the ones both versions define, so
 * step 6's retargeting can mostly ignore [specVersion] for bones.
 *
 * [expressions] keys are **not** normalized between versions — VRM 1.0
 * uses `"happy"`/`"aa"`/`"blinkLeft"`; VRM 0.x uses `"joy"`/`"a"`/(often)
 * no left/right blink split at all, and the group's own free-text [name]
 * if it has no [presetName]. Building a VRM-0-to-1 preset-name table is a
 * real task (VRM 0.x's preset list is a strict subset with different
 * spelling for several entries) but genuinely separate from parsing —
 * it's a static lookup table, not something that needs a `.vrm` file to
 * write, so it's deliberately left for whoever wires expressions into
 * retargeting to add once they've picked which naming the app's own
 * expression code standardizes on.
 */
data class VrmData(
    val specVersion: VrmSpecVersion,
    val humanBones: Map<String, Int>,
    val expressions: Map<String, List<MorphTargetBind>>,
    /** glTF node index → that node's own `name` field, for every node that
     *  has one (glTF's `name` is itself optional). Not part of the VRM
     *  spec block at all — this is the plain glTF `nodes` array — but
     *  bundled onto [VrmData] anyway because it's the bridge retargeting
     *  (step 6, `AvatarRetargeter.kt`) needs: [humanBones] and every
     *  [MorphTargetBind] give a **node index**, but Filament's `gltfio`
     *  loader (step 5) is looked up by **entity**, and the most reliable
     *  bridge between the two available without deeper gltfio APIs this
     *  pipeline hasn't otherwise needed is going via each node's *name*
     *  (`FilamentAsset.getFirstEntityByName`) — see AvatarRetargeter.kt's
     *  own doc comment for why, and how confident that bridge actually is.
     */
    val nodeNames: Map<Int, String>
)

/**
 * Reads the glTF 2.0 binary container format (`.glb`, and `.vrm` is always
 * one of these in practice — VRM is only ever distributed/exported as a
 * single binary file, never the multi-file JSON+.bin+textures form). Per
 * the spec (registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#glb-file-format):
 * a 12-byte header (magic `"glTF"`, version, total length, all little-
 * endian uint32) followed by chunks, each a
 * `[length: uint32][type: uint32][data: length bytes]` record. We only
 * need the JSON chunk (type `0x4E4F534A`) — the binary buffer chunk
 * (`0x004E4942`, mesh/texture data) is step 5's concern via Filament's own
 * loader, not this class's.
 */
object GlbReader {
    private const val TAG = "GlbReader"
    private const val GLB_MAGIC = 0x46546C67 // ASCII "glTF", read little-endian
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // ASCII "JSON", read little-endian

    /** Returns the JSON chunk's text, or null (logged) if [bytes] isn't a
     *  well-formed glTF-binary container or has no JSON chunk. */
    fun extractJsonChunk(bytes: ByteArray): String? = runCatching {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= 12) { "file too short for a GLB header" }
        val magic = buffer.int
        require(magic == GLB_MAGIC) { "not a GLB file (bad magic)" }
        buffer.int // version — not checked; glTF 2.0 is the only version in practice
        buffer.int // total declared length — not checked, we just read chunks until EOF

        while (buffer.remaining() >= 8) {
            val chunkLength = buffer.int
            val chunkType = buffer.int
            require(chunkLength >= 0 && chunkLength <= buffer.remaining()) { "chunk length runs past end of file" }
            if (chunkType == CHUNK_TYPE_JSON) {
                val jsonBytes = ByteArray(chunkLength)
                buffer.get(jsonBytes)
                return@runCatching String(jsonBytes, Charset.forName("UTF-8"))
            } else {
                buffer.position(buffer.position() + chunkLength) // skip BIN or any unknown chunk type
            }
        }
        null
    }.onFailure {
        Log.e(TAG, "Could not read GLB container", it)
    }.getOrNull()
}

/** Parses the VRM-specific extension block out of a glTF JSON document. */
object VrmParser {
    private const val TAG = "VrmParser"

    /** [bytes] is a whole `.vrm` file's contents. Returns null (logged) if
     *  it isn't a readable GLB, has no JSON chunk, or that JSON has
     *  neither a `VRMC_vrm` (1.0) nor `VRM` (0.x) extension — i.e. isn't
     *  actually a VRM file, just a plain glTF/GLB. */
    fun parse(bytes: ByteArray): VrmData? {
        val jsonText = GlbReader.extractJsonChunk(bytes) ?: return null
        return runCatching {
            val root = JsonParser.parseString(jsonText).asJsonObject
            val nodeNames = mutableMapOf<Int, String>()
            root.at("nodes")?.asJsonArrayOrNull?.forEachIndexed { nodeIndex, node ->
                node.asJsonObjectOrNull?.at("name")?.asStringOrNull?.let { nodeNames[nodeIndex] = it }
            }
            val extensions = root.at("extensions")?.asJsonObjectOrNull ?: return null
            when {
                extensions.has("VRMC_vrm") -> parseVrm1(extensions.getAsJsonObject("VRMC_vrm"), nodeNames)
                extensions.has("VRM") -> parseVrm0(root, extensions.getAsJsonObject("VRM"), nodeNames)
                else -> null
            }
        }.onFailure {
            Log.e(TAG, "Could not parse VRM extension block", it)
        }.getOrNull()
    }

    // ---- VRM 1.0 (VRMC_vrm) ----------------------------------------------

    private fun parseVrm1(vrmc: JsonObject, nodeNames: Map<Int, String>): VrmData {
        val humanBones = mutableMapOf<String, Int>()
        vrmc.at("humanoid.humanBones")?.asJsonObjectOrNull?.entrySet()?.forEach { (boneName, boneValue) ->
            boneValue.asJsonObjectOrNull?.at("node")?.asIntOrNull?.let { humanBones[boneName] = it }
        }

        val expressions = mutableMapOf<String, List<MorphTargetBind>>()
        val expressionsRoot = vrmc.at("expressions")?.asJsonObjectOrNull
        // "preset" (spec-defined names: happy, angry, sad, blink, aa, ...)
        // and "custom" (author-defined names) are siblings of the same
        // shape — merge them into one map, since nothing here cares which
        // bucket a given expression name came from.
        listOf("preset", "custom").forEach { bucket ->
            expressionsRoot?.at(bucket)?.asJsonObjectOrNull?.entrySet()?.forEach { (expressionName, expressionValue) ->
                val binds = expressionValue.asJsonObjectOrNull?.at("morphTargetBinds")?.asJsonArrayOrNull
                    ?.mapNotNull { it.asJsonObjectOrNull?.toMorphTargetBindVrm1() }
                    ?: emptyList()
                if (binds.isNotEmpty()) expressions[expressionName] = binds
            }
        }

        return VrmData(VrmSpecVersion.VRM_1, humanBones, expressions, nodeNames)
    }

    /** VRM 1.0's own bind shape: `{node, index, weight}` with `weight`
     *  already on a 0.0–1.0 scale (unlike VRM 0.x's 0–100, see
     *  [toMorphTargetBindVrm0]). */
    private fun JsonObject.toMorphTargetBindVrm1(): MorphTargetBind? {
        val node = at("node")?.asIntOrNull ?: return null
        val index = at("index")?.asIntOrNull ?: return null
        val weight = at("weight")?.asFloatOrNull ?: 1.0f
        return MorphTargetBind(node, index, weight)
    }

    // ---- VRM 0.x (VRM) ----------------------------------------------------

    private fun parseVrm0(root: JsonObject, vrm: JsonObject, nodeNames: Map<Int, String>): VrmData {
        val humanBones = mutableMapOf<String, Int>()
        vrm.at("humanoid.humanBones")?.asJsonArrayOrNull?.forEach { entry ->
            val boneObject = entry.asJsonObjectOrNull ?: return@forEach
            val boneName = boneObject.at("bone")?.asStringOrNull ?: return@forEach
            val node = boneObject.at("node")?.asIntOrNull ?: return@forEach
            humanBones[boneName] = node
        }

        // VRM 0.x's blendShapeGroups reference a *mesh* index
        // (glTF.meshes[i]), not a node index — unlike VRM 1.0, which
        // already stores a node index directly. Every mesh a humanoid VRM
        // actually renders is referenced by exactly one node (VRM avatars
        // don't reuse a mesh across multiple nodes), so build a
        // mesh-index -> node-index lookup once from the glTF node array
        // and resolve through it, rather than expose the mesh index and
        // push this resolution onto every caller.
        val meshIndexToNodeIndex = mutableMapOf<Int, Int>()
        root.at("nodes")?.asJsonArrayOrNull?.forEachIndexed { nodeIndex, node ->
            node.asJsonObjectOrNull?.at("mesh")?.asIntOrNull?.let { meshIndex ->
                meshIndexToNodeIndex[meshIndex] = nodeIndex
            }
        }

        val expressions = mutableMapOf<String, List<MorphTargetBind>>()
        vrm.at("blendShapeMaster.blendShapeGroups")?.asJsonArrayOrNull?.forEach { entry ->
            val group = entry.asJsonObjectOrNull ?: return@forEach
            // Prefer the spec-defined presetName ("joy", "a", "blink", ...)
            // when present, matching VRM 1.0's preset/custom split in
            // spirit; fall back to the group's free-text name for
            // author-defined expressions VRM 0.x has no preset for.
            val presetName = group.at("presetName")?.asStringOrNull?.takeIf { it.isNotBlank() && it != "unknown" }
            val expressionName = presetName ?: group.at("name")?.asStringOrNull ?: return@forEach
            val binds = group.at("binds")?.asJsonArrayOrNull
                ?.mapNotNull { it.asJsonObjectOrNull?.toMorphTargetBindVrm0(meshIndexToNodeIndex) }
                ?: emptyList()
            if (binds.isNotEmpty()) expressions[expressionName] = binds
        }

        return VrmData(VrmSpecVersion.VRM_0, humanBones, expressions, nodeNames)
    }

    /** VRM 0.x's own bind shape: `{mesh, index, weight}` with `weight` on
     *  a 0–100 scale (percentage, matching Unity's `SkinnedMeshRenderer`
     *  blend shape weights) — normalized to VRM 1.0's 0.0–1.0 here so
     *  downstream expression code (step 6+) can treat every
     *  [MorphTargetBind] the same regardless of which VRM version it came
     *  from, and never needs to know [VrmData.specVersion] itself. */
    private fun JsonObject.toMorphTargetBindVrm0(meshIndexToNodeIndex: Map<Int, Int>): MorphTargetBind? {
        val meshIndex = at("mesh")?.asIntOrNull ?: return null
        val nodeIndex = meshIndexToNodeIndex[meshIndex] ?: run {
            Log.w(TAG, "VRM 0.x blend shape bind references mesh $meshIndex with no owning node — skipped")
            return null
        }
        val index = at("index")?.asIntOrNull ?: return null
        val weightPercent = at("weight")?.asFloatOrNull ?: 100.0f
        return MorphTargetBind(nodeIndex, index, weightPercent / 100.0f)
    }
}

// ---- Small Gson navigation helpers ---------------------------------------
// Bare Gson (`JsonObject.get`/`getAsJsonObject`) throws on a missing key in
// some call shapes and returns a JsonNull in others, and every VRM field
// above is optional per spec (a minimal/hand-authored VRM can omit any of
// them) — these turn "missing or wrong-typed" uniformly into null instead
// of a thrown exception, so one absent field degrades to "that one
// bone/expression is missing" instead of failing the whole file's parse.
// This is the same defend-against-a-field-that-isn't-there shape as the
// Gson-cache bug fixed elsewhere in this codebase (see PreferencesManager's
// crash-fix comment) — same root cause class (Gson doesn't enforce a
// schema), same defensive answer.

/** Dotted-path lookup, e.g. `at("humanoid.humanBones")`, returning null the
 *  moment any segment is missing/null rather than throwing. */
private fun JsonObject.at(path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path.split(".")) {
        val obj = current.asJsonObjectOrNull ?: return null
        current = obj.get(segment) ?: return null
        if (current.isJsonNull) return null
    }
    return current
}

private val JsonElement.asJsonObjectOrNull: JsonObject?
    get() = runCatching { takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

private val JsonElement.asJsonArrayOrNull: JsonArray?
    get() = runCatching { takeIf { it.isJsonArray }?.asJsonArray }.getOrNull()

private val JsonElement.asIntOrNull: Int?
    get() = runCatching { takeIf { isJsonPrimitive }?.asInt }.getOrNull()

private val JsonElement.asFloatOrNull: Float?
    get() = runCatching { takeIf { isJsonPrimitive }?.asFloat }.getOrNull()

private val JsonElement.asStringOrNull: String?
    get() = runCatching { takeIf { isJsonPrimitive }?.asString }.getOrNull()
