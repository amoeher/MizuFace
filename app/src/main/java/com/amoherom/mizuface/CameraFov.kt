package com.amoherom.mizuface

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.atan
import kotlin.math.tan

object CameraFov {
    data class Fov(
        val hRad: Double, // Horizontal field of view in radians
        val vRad: Double, // Vertical field of view in radians
    )

    fun getFovRadians( context: Context, lenseFacing: Int): Fov {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            val ch = cm.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == lenseFacing
        } ?: cm.cameraIdList.first()

        val ch = cm.getCameraCharacteristics(cameraId)
        val sensorSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: throw IllegalStateException("No SENSOR_INFO_PHYSICAL_SIZE")
        val focallengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: throw IllegalStateException("No LENS_INFO_AVAILABLE_FOCAL_LENGTHS")

        val fMm = focallengths.first().toDouble()
        val sensorW = sensorSize.width.toDouble()
        val sensorH = sensorSize.height.toDouble()

        val hFov = 2.0 * atan(sensorW / (2.0 * fMm))
        val vFov = 2.0 * atan(sensorH / (2.0 * fMm))
        return Fov(hFov, vFov)
    }

    fun widthAtDistance(
        distanceCm: Double,
        fov: Fov
    ): Double =
        2.0 * distanceCm * tan(fov.hRad / 2.0)

    fun heightAtDistance(
        distanceCm: Double,
        fov: Fov
    ): Double =
        2.0 * distanceCm * tan(fov.vRad / 2.0)
}