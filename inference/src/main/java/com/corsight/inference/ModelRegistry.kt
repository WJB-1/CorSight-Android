package com.corsight.inference

import android.content.Context

object ModelRegistry {
    private val engines = mutableMapOf<String, InferenceEngine>()

    fun <T : InferenceEngine> register(id: String, engine: T): T {
        engines[id] = engine
        return engine
    }

    fun load(id: String, context: Context) {
        engines[id]?.load(context)
    }

    fun getDetector(id: String): ObjectDetector? {
        return engines[id] as? ObjectDetector
    }

    fun release(id: String) {
        engines[id]?.release()
    }

    fun releaseAll() {
        engines.values.forEach { it.release() }
        engines.clear()
    }
}
