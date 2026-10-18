package io.github.h3nb.jlmodplus.config

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json

/** Fixture construction without production normalization; historical fixtures also live as static resources. */
internal class ProfileFixtures {
    private val format = Json { ignoreUnknownKeys = true }
    fun toJson(model: ProfileModel): String = ProfileJson.tree(model).toString()
    fun toJson(tree: JsonElement): String = tree.toString()
    fun toJsonTree(model: ProfileModel): JsonElement = JsonParser.parseString(toJson(model))
    fun fromJson(text: String, type: Class<ProfileModel>): ProfileModel =
        format.decodeFromString<ProfileModel>(text)
}

internal object ProfileJsonTestSupport {
    @JvmStatic fun parse(text: String): kotlinx.serialization.json.JsonElement = Json.parseToJsonElement(text)
}
