package io.github.nikarpoff.alv.model

/**
 * Scene object (KM-1): one element of `objects` in `answer.end` (PROTO-5).
 *
 * @property label vocabulary class id (DET-2), for example `chair`
 * @property labelRu Russian class name, for example `стул`
 * @property direction horizontal third of the frame the object is in (DET-4)
 * @property distance distance class of the object (DET-4)
 * @property hazard whether a hazard rule fired on the object (PLT-6)
 */
data class SceneObject(
    val label: String,
    val labelRu: String,
    val direction: Direction,
    val distance: Distance,
    val hazard: Boolean,
) {
    /** Direction to the object; [wire] is the value in the protocol. */
    enum class Direction(val wire: String) {
        LEFT("left"),
        AHEAD("ahead"),
        RIGHT("right"),
    }

    /** Distance to the object; [wire] is the value in the protocol. */
    enum class Distance(val wire: String) {
        NEAR("near"),
        FAR("far"),
    }
}
