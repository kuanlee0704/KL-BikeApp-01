package com.bikeplanner.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 以 JSON 檔存放在 App 私有目錄；不需要資料庫套件。 */
class Storage private constructor(context: Context) {

    private val dir: File = context.applicationContext.filesDir
    private val plansFile = File(dir, "plans.json")
    private val indexFile = File(dir, "rides_index.json")
    private val ridesDir = File(dir, "rides").also { it.mkdirs() }
    private val lock = Any()

    companion object {
        @Volatile
        private var instance: Storage? = null

        fun get(context: Context): Storage =
            instance ?: synchronized(this) {
                instance ?: Storage(context).also { instance = it }
            }
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    // ---------- 規劃 ----------

    fun loadPlans(): List<Plan> = synchronized(lock) {
        try {
            if (!plansFile.exists()) {
                emptyList()
            } else {
                val arr = JSONArray(plansFile.readText())
                (0 until arr.length()).map { planFromJson(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlans(plans: List<Plan>) = synchronized(lock) {
        val arr = JSONArray()
        plans.forEach { arr.put(planToJson(it)) }
        writeAtomic(plansFile, arr.toString())
    }

    private fun planToJson(p: Plan): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("plannedAt", p.plannedAt)
        put("notes", p.notes)
        val wps = JSONArray()
        p.waypoints.forEach { wps.put(JSONArray().put(it.lat).put(it.lon)) }
        put("wps", wps)
    }

    private fun planFromJson(o: JSONObject): Plan {
        val wa = o.optJSONArray("wps") ?: JSONArray()
        val wps = (0 until wa.length()).map {
            val a = wa.getJSONArray(it)
            TrackPoint(a.getDouble(0), a.getDouble(1))
        }
        return Plan(
            id = o.getString("id"),
            name = o.optString("name"),
            plannedAt = o.optLong("plannedAt", 0L),
            notes = o.optString("notes"),
            waypoints = wps
        )
    }

    // ---------- 騎乘紀錄 ----------

    fun loadRides(): List<RideMeta> = synchronized(lock) { readIndex().sortedByDescending { it.startTime } }

    private fun readIndex(): List<RideMeta> {
        return try {
            if (!indexFile.exists()) {
                emptyList()
            } else {
                val arr = JSONArray(indexFile.readText())
                (0 until arr.length()).map { metaFromJson(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(list: List<RideMeta>) {
        val arr = JSONArray()
        list.forEach { arr.put(metaToJson(it)) }
        writeAtomic(indexFile, arr.toString())
    }

    fun saveRide(meta: RideMeta, points: List<TrackPoint>) = synchronized(lock) {
        val pa = JSONArray()
        points.forEach {
            pa.put(
                JSONArray()
                    .put(it.lat).put(it.lon).put(it.time).put(it.alt).put(it.speed.toDouble())
            )
        }
        writeAtomic(File(ridesDir, meta.id + ".json"), pa.toString())
        writeIndex(readIndex().filter { it.id != meta.id } + meta)
    }

    fun loadPoints(id: String): List<TrackPoint> = synchronized(lock) {
        try {
            val f = File(ridesDir, "$id.json")
            if (!f.exists()) {
                emptyList()
            } else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map {
                    val a = arr.getJSONArray(it)
                    TrackPoint(
                        lat = a.getDouble(0),
                        lon = a.getDouble(1),
                        time = a.getLong(2),
                        alt = a.getDouble(3),
                        speed = a.getDouble(4).toFloat()
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteRide(id: String) = synchronized(lock) {
        File(ridesDir, "$id.json").delete()
        writeIndex(readIndex().filter { it.id != id })
    }

    private fun metaToJson(m: RideMeta): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("startTime", m.startTime)
        put("durationMs", m.durationMs)
        put("distanceM", m.distanceM)
        put("maxSpeed", m.maxSpeedMps.toDouble())
        if (m.planId != null) put("planId", m.planId)
    }

    private fun metaFromJson(o: JSONObject): RideMeta = RideMeta(
        id = o.getString("id"),
        name = o.optString("name"),
        startTime = o.optLong("startTime"),
        durationMs = o.optLong("durationMs"),
        distanceM = o.optDouble("distanceM", 0.0),
        maxSpeedMps = o.optDouble("maxSpeed", 0.0).toFloat(),
        planId = if (o.has("planId") && !o.isNull("planId")) o.getString("planId") else null
    )
}
