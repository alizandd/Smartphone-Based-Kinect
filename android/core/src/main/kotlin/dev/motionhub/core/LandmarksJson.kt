package dev.motionhub.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * `landmarks.jsonl` line format shared by the app recorder, the Python bake-off and the replay CLI:
 * `{"t_us":…, "w":1280, "h":960, "backend":"mediapipe_full",
 *   "lm":[[x_norm,y_norm,z_rel,visibility] ×33] | null, "world":[[x,y,z] ×33] | null}`
 * Missing landmarks inside `lm` are `null`.
 */
object LandmarksJson {
    fun encode(f: PoseFrame?, tUs: Long, width: Int, height: Int, backend: String, zRel: FloatArray? = null): String =
        buildJsonObject {
            put("t_us", tUs); put("w", width); put("h", height); put("backend", backend)
            if (f == null) {
                put("lm", JsonNull); put("world", JsonNull)
            } else {
                put("lm", buildJsonArray {
                    for (i in 0 until BP.COUNT) {
                        if (f.px[2 * i].isNaN()) add(JsonNull) else addJsonArray {
                            add(round5(f.px[2 * i] / width)); add(round5(f.px[2 * i + 1] / height))
                            add(round5(zRel?.get(i) ?: 0f)); add(round5(f.vis[i]))
                        }
                    }
                })
                val w = f.world
                put("world", if (w == null) JsonNull else buildJsonArray {
                    for (i in 0 until BP.COUNT) addJsonArray {
                        add(round5(w[3 * i])); add(round5(w[3 * i + 1])); add(round5(w[3 * i + 2]))
                    }
                })
            }
        }.toString()

    /** Returns null for frames without a detection. */
    fun decode(line: String): PoseFrame? {
        val o = Json.parseToJsonElement(line).jsonObject
        val lm = o["lm"]
        if (lm == null || lm is JsonNull) return null
        val w = o["w"]!!.jsonPrimitive.int
        val h = o["h"]!!.jsonPrimitive.int
        val px = FloatArray(BP.COUNT * 2) { Float.NaN }
        val vis = FloatArray(BP.COUNT)
        lm.jsonArray.forEachIndexed { i, e ->
            if (i < BP.COUNT && e !is JsonNull) {
                val a = e.jsonArray
                px[2 * i] = a[0].jsonPrimitive.float * w
                px[2 * i + 1] = a[1].jsonPrimitive.float * h
                vis[i] = if (a.size > 3) a[3].jsonPrimitive.float else 1f
            }
        }
        val worldEl = o["world"]
        val world = if (worldEl == null || worldEl is JsonNull) null else FloatArray(BP.COUNT * 3).also { arr ->
            worldEl.jsonArray.forEachIndexed { i, e ->
                if (i < BP.COUNT && e !is JsonNull) {
                    val a = e.jsonArray
                    for (k in 0 until 3) arr[3 * i + k] = a[k].jsonPrimitive.float
                }
            }
        }
        return PoseFrame(o["t_us"]!!.jsonPrimitive.long, w, h, px, vis, world)
    }

    fun timestampUs(line: String): Long = Json.parseToJsonElement(line).jsonObject["t_us"]!!.jsonPrimitive.long

    private fun round5(v: Float): Double = Math.round(v * 100000.0) / 100000.0

    // ---- meta.json helpers ----
    fun doubles(e: JsonElement?): DoubleArray? = (e as? JsonArray)?.map { it.jsonPrimitive.double }?.toDoubleArray()
    fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
}
