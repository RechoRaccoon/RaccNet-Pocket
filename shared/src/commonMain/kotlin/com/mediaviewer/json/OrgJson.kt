package com.mediaviewer.json

/*
 * org.json's JSONObject / JSONArray for shared code. On Android these ARE
 * org.json.JSONObject / JSONArray (Android's built-in implementation, so
 * behaviour is unchanged); on iOS they're a small implementation with the
 * same semantics (opt* return defaults, get* throw JSONException).
 */

expect open class JSONException : Exception {
    constructor(message: String?)
}

expect open class JSONObject {
    constructor()
    constructor(json: String)

    fun length(): Int
    fun has(name: String): Boolean
    fun isNull(name: String): Boolean
    fun keys(): Iterator<String>
    fun opt(name: String): Any?
    fun remove(name: String): Any?
    fun put(name: String, value: Any?): JSONObject

    fun optString(name: String): String
    fun optString(name: String, fallback: String): String
    fun optInt(name: String): Int
    fun optInt(name: String, fallback: Int): Int
    fun optLong(name: String): Long
    fun optLong(name: String, fallback: Long): Long
    fun optDouble(name: String): Double
    fun optDouble(name: String, fallback: Double): Double
    fun optBoolean(name: String): Boolean
    fun optBoolean(name: String, fallback: Boolean): Boolean
    fun optJSONObject(name: String): JSONObject?
    fun optJSONArray(name: String): JSONArray?

    fun getString(name: String): String
    fun getInt(name: String): Int
    fun getLong(name: String): Long
    fun getDouble(name: String): Double
    fun getBoolean(name: String): Boolean
    fun getJSONObject(name: String): JSONObject
    fun getJSONArray(name: String): JSONArray

    fun toString(indentSpaces: Int): String
}

expect open class JSONArray {
    constructor()
    constructor(json: String)

    fun length(): Int
    fun opt(index: Int): Any?
    fun put(value: Any?): JSONArray

    fun optString(index: Int): String
    fun optString(index: Int, fallback: String): String
    fun optInt(index: Int): Int
    fun optInt(index: Int, fallback: Int): Int
    fun optLong(index: Int): Long
    fun optDouble(index: Int): Double
    fun optDouble(index: Int, fallback: Double): Double
    fun optBoolean(index: Int): Boolean
    fun optJSONObject(index: Int): JSONObject?
    fun optJSONArray(index: Int): JSONArray?

    fun getString(index: Int): String
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
    fun getDouble(index: Int): Double
    fun getBoolean(index: Int): Boolean
    fun getJSONObject(index: Int): JSONObject
    fun getJSONArray(index: Int): JSONArray

    fun toString(indentSpaces: Int): String
}
