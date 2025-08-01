package com.amoherom.mizuface

import com.amoherom.mizuface.Blendshape

class BlenshapeMapper {

    companion object {
        val blendshapeBundle = mutableListOf(
            Pair("browInnerUp", 0.0f),
            Pair("browDownLeft", 0.0f),
            Pair("browDownRight", 0.0f),
            Pair("browOuterUpLeft", 0.0f),
            Pair("browOuterUpRight", 0.0f),
            Pair("eyeLookUpLeft", 0.0f),
            Pair("eyeLookUpRight", 0.0f),
            Pair("eyeLookDownLeft", 0.0f),
            Pair("eyeLookDownRight", 0.0f),
            Pair("eyeLookInLeft", 0.0f),
            Pair("eyeLookInRight", 0.0f),
            Pair("eyeLookOutLeft", 0.0f),
            Pair("eyeLookOutRight", 0.0f),
            Pair("eyeBlinkLeft", 0.0f),
            Pair("eyeBlinkRight", 0.0f),
            Pair("eyeSquintRight", 0.0f),
            Pair("eyeSquintLeft", 0.0f),
            Pair("eyeWideLeft", 0.0f),
            Pair("eyeWideRight", 0.0f),
            Pair("cheekPuff", 0.0f),
            Pair("cheekSquintLeft", 0.0f),
            Pair("cheekSquintRight", 0.0f),
            Pair("noseSneerLeft", 0.0f),
            Pair("noseSneerRight", 0.0f),
            Pair("jawOpen", 0.0f),
            Pair("jawForward", 0.0f),
            Pair("jawLeft", 0.0f),
            Pair("jawRight", 0.0f),
            Pair("mouthFunnel", 0.0f),
            Pair("mouthPucker", 0.0f),
            Pair("mouthLeft", 0.0f),
            Pair("mouthRight", 0.0f),
            Pair("mouthRollUpper", 0.0f),
            Pair("mouthRollLower", 0.0f),
            Pair("mouthShrugUpper", 0.0f),
            Pair("mouthShrugLower", 0.0f),
            Pair("mouthClose", 0.0f),
            Pair("mouthSmileLeft", 0.0f),
            Pair("mouthSmileRight", 0.0f),
            Pair("mouthFrownLeft", 0.0f),
            Pair("mouthFrownRight", 0.0f),
            Pair("mouthDimpleLeft", 0.0f),
            Pair("mouthDimpleRight", 0.0f),
            Pair("mouthUpperUpLeft", 0.0f),
            Pair("mouthUpperUpRight", 0.0f),
            Pair("mouthLowerDownLeft", 0.0f),
            Pair("mouthLowerDownRight", 0.0f),
            Pair("mouthPressLeft", 0.0f),
            Pair("mouthPressRight", 0.0f),
            Pair("mouthStretchLeft", 0.0f),
            Pair("mouthStretchRight", 0.0f),
            Pair("tongueOut", 0.0f)
        )

        /**
         * Returns the index of a blendshape in the blendshapeBundle by its name
         * @param name The name of the blendshape to find
         * @return The index of the blendshape, or -1 if not found
         */
        fun getBlendshapeIndex(name: String): Int {
            return blendshapeBundle.indexOfFirst { it.first == name }
        }
    }
}