package com.example.voicenavigation.collection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

object GridUtils {

    fun getTileCoordinate(lat: Double, lon: Double, zoom: Int = 16): Pair<Int, Int> {
        val x = floor((lon + 180) / 360 * 2.0.pow(zoom)).toInt()
        val y = floor(
            (1 - ln(tan(lat * PI / 180) + 1 / cos(lat * PI / 180)) / PI)
                    / 2 * 2.0.pow(zoom)
        ).toInt()
        return Pair(x, y)
    }

    fun getChunkId(lat: Double, lon: Double, zoom: Int = 16): String {
        val (x, y) = getTileCoordinate(lat, lon, zoom)
        return "${zoom}_${x}_${y}"
    }
}
