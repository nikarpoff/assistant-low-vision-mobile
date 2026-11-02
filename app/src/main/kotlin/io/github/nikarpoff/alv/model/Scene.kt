package io.github.nikarpoff.alv.model

/**
 * Scene (KM-1): the last described scene - the full answer text and the objects from `answer.end`.
 *
 * The snapshot itself is not kept: frames live in memory only until the request ends (MOB-16).
 *
 * @property text full description text
 * @property objects objects the gateway found in the scene
 */
data class Scene(
    val text: String,
    val objects: List<SceneObject>,
)
