package com.openautolink.app.cluster

import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.core.graphics.drawable.IconCompat
import com.openautolink.app.navigation.ManeuverIconRenderer
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import com.openautolink.app.navigation.LaneInfo

// ── Shared helpers for cluster sessions ──

internal fun buildManeuver(maneuver: ManeuverState, carContext: CarContext): Maneuver {
    var type = mapManeuverTypeToCarApp(maneuver.type)
    // Named semantics remain authoritative when AndroidX can represent them.
    // A bitmap never waives the required positive ordinal on combined types.
    if (isRoundaboutManeuverType(type) && (maneuver.roundaboutExitNumber ?: 0) <= 0) {
        type = Maneuver.TYPE_UNKNOWN
    }
    val builder = Maneuver.Builder(type)
    if (isRoundaboutManeuverType(type)) {
        builder.setRoundaboutExitNumber(requireNotNull(maneuver.roundaboutExitNumber))
    }
    // NavigationDisplayImpl scopes artwork to the current instruction: explicit
    // turns clear absent artwork; distance-only deltas retain the association.
    val bitmap = decodeCurrentManeuverArtwork(maneuver.navImageBase64)
    if (bitmap != null) {
        builder.setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
    } else if (maneuver.type != ManeuverType.UNKNOWN) {
        // Generic roundabouts have known identity, but no sourced circulation.
        // Missing required geometry also uses neutral, never a fictional exit.
        val resId = if (type == Maneuver.TYPE_UNKNOWN) {
            com.openautolink.app.R.drawable.nav_roundabout_neutral
        } else ManeuverIconRenderer.drawableForManeuver(maneuver.type)
        builder.setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build())
    }
    return builder.build()
}

/** Bound encoded bytes, decoded bytes, dimensions and pixels before allocation. */
private fun decodeCurrentManeuverArtwork(encoded: String?): android.graphics.Bitmap? {
    if (encoded == null || encoded.length > 1_398_104) return null // 1 MiB base64 ceiling
    return try {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > 1_048_576) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024 ||
            bounds.outWidth.toLong() * bounds.outHeight > 1_048_576L) return null
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inScaled = false
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.takeIf {
            it.width == bounds.outWidth && it.height == bounds.outHeight
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.lang.RuntimeException) {
        null
    }
}

/** The ordinal-only combined types require a positive, sourced exit number. */
internal fun isRoundaboutManeuverType(type: Int): Boolean =
    type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW ||
    type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW

internal fun mapManeuverTypeToCarApp(type: ManeuverType): Int = when (type) {
    ManeuverType.DEPART -> Maneuver.TYPE_DEPART
    ManeuverType.STRAIGHT -> Maneuver.TYPE_STRAIGHT
    ManeuverType.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    ManeuverType.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
    ManeuverType.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
    ManeuverType.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    ManeuverType.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    ManeuverType.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
    ManeuverType.U_TURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
    ManeuverType.U_TURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
    ManeuverType.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
    ManeuverType.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
    ManeuverType.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
    ManeuverType.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
    ManeuverType.MERGE_UNSPECIFIED -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
    ManeuverType.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
    ManeuverType.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
    ManeuverType.ON_RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
    ManeuverType.ON_RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
    ManeuverType.ON_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
    ManeuverType.ON_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
    ManeuverType.ON_RAMP_SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
    ManeuverType.ON_RAMP_SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
    ManeuverType.ON_RAMP_U_TURN_LEFT -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
    ManeuverType.ON_RAMP_U_TURN_RIGHT -> Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT
    ManeuverType.OFF_RAMP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
    ManeuverType.OFF_RAMP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
    ManeuverType.OFF_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
    ManeuverType.OFF_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
    ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
    ManeuverType.ROUNDABOUT_ENTER_AND_EXIT -> Maneuver.TYPE_UNKNOWN
    ManeuverType.ROUNDABOUT_ENTER_CW -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
    ManeuverType.ROUNDABOUT_ENTER_CCW -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
    ManeuverType.ROUNDABOUT_EXIT_CW -> Maneuver.TYPE_ROUNDABOUT_EXIT_CW
    ManeuverType.ROUNDABOUT_EXIT_CCW -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
    ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
    ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
    ManeuverType.DESTINATION -> Maneuver.TYPE_DESTINATION
    ManeuverType.DESTINATION_STRAIGHT -> Maneuver.TYPE_DESTINATION_STRAIGHT
    ManeuverType.DESTINATION_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
    ManeuverType.DESTINATION_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
    ManeuverType.FERRY -> Maneuver.TYPE_FERRY_BOAT
    ManeuverType.FERRY_TRAIN -> Maneuver.TYPE_FERRY_TRAIN
    ManeuverType.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
    ManeuverType.UNKNOWN -> Maneuver.TYPE_UNKNOWN
}

/**
 * Map AA proto lane direction shape string to Car App LaneDirection.SHAPE_* constant.
 */
internal fun mapLaneShape(shape: String): Int = when (shape) {
    "straight" -> LaneDirection.SHAPE_STRAIGHT
    "normal_left" -> LaneDirection.SHAPE_NORMAL_LEFT
    "normal_right" -> LaneDirection.SHAPE_NORMAL_RIGHT
    "slight_left" -> LaneDirection.SHAPE_SLIGHT_LEFT
    "slight_right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
    "sharp_left" -> LaneDirection.SHAPE_SHARP_LEFT
    "sharp_right" -> LaneDirection.SHAPE_SHARP_RIGHT
    "u_turn_left" -> LaneDirection.SHAPE_U_TURN_LEFT
    "u_turn_right" -> LaneDirection.SHAPE_U_TURN_RIGHT
    else -> LaneDirection.SHAPE_UNKNOWN
}

/**
 * Build Car App Lane objects from ManeuverState lane data.
 */
internal fun buildLanes(lanes: List<LaneInfo>): List<Lane> {
    return lanes.map { laneInfo ->
        val builder = Lane.Builder()
        for (dir in laneInfo.directions) {
            builder.addDirection(
                LaneDirection.create(mapLaneShape(dir.shape), dir.highlighted)
            )
        }
        builder.build()
    }
}

/** Admit sourced display distance independently of optional raw meters; unknown stays unknown. */
internal fun toOptionalDistance(
    meters: Int?,
    distanceUnits: String = "auto",
    displayValue: String? = null,
    displayUnit: String? = null
): androidx.car.app.model.Distance? {
    val value = displayValue?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val unit = when (displayUnit) {
        "meters" -> androidx.car.app.model.Distance.UNIT_METERS
        "kilometers", "kilometers_p1" -> androidx.car.app.model.Distance.UNIT_KILOMETERS
        "miles", "miles_p1" -> androidx.car.app.model.Distance.UNIT_MILES
        "feet" -> androidx.car.app.model.Distance.UNIT_FEET
        "yards" -> androidx.car.app.model.Distance.UNIT_YARDS
        else -> null
    }
    if (value != null && unit != null) return androidx.car.app.model.Distance.create(value, unit)
    return meters?.takeIf { it >= 0 }?.let { toDistance(it, distanceUnits) }
}

/** Convert known raw meters, retaining the existing locale and display-unit behavior. */
internal fun toDistance(
    meters: Int,
    distanceUnits: String = "auto",
    displayValue: String? = null,
    displayUnit: String? = null
): androidx.car.app.model.Distance {
    // Prefer pre-formatted values from the phone
    if (!displayValue.isNullOrEmpty() && !displayUnit.isNullOrEmpty()) {
        val value = displayValue.toDoubleOrNull()
        if (value != null) {
            val unit = when (displayUnit) {
                "meters" -> androidx.car.app.model.Distance.UNIT_METERS
                "kilometers", "kilometers_p1" -> androidx.car.app.model.Distance.UNIT_KILOMETERS
                "miles", "miles_p1" -> androidx.car.app.model.Distance.UNIT_MILES
                "feet" -> androidx.car.app.model.Distance.UNIT_FEET
                "yards" -> androidx.car.app.model.Distance.UNIT_YARDS
                else -> null
            }
            if (unit != null) {
                return androidx.car.app.model.Distance.create(value, unit)
            }
        }
    }

    // Fallback: convert raw meters
    val useImperial = when (distanceUnits) {
        "imperial" -> true
        "metric" -> false
        else -> java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
    }
    return if (useImperial) {
        val miles = meters / 1609.344
        if (miles >= 0.2) {
            androidx.car.app.model.Distance.create(
                miles, androidx.car.app.model.Distance.UNIT_MILES
            )
        } else {
            val feet = (meters / 0.3048).toInt()
            androidx.car.app.model.Distance.create(
                feet.toDouble(), androidx.car.app.model.Distance.UNIT_FEET
            )
        }
    } else {
        if (meters >= 1000) {
            androidx.car.app.model.Distance.create(
                meters / 1000.0, androidx.car.app.model.Distance.UNIT_KILOMETERS
            )
        } else {
            androidx.car.app.model.Distance.create(
                meters.toDouble(), androidx.car.app.model.Distance.UNIT_METERS
            )
        }
    }
}
