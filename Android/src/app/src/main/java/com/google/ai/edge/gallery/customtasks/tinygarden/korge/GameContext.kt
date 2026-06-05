package com.google.ai.edge.gallery.customtasks.tinygarden.korge

import kotlinx.coroutines.flow.Flow
import com.google.ai.edge.gallery.customtasks.tinygarden.TinyGardenCommand

/**
 * Архитектура Игрового Движка (NeuroTale)
 * Здесь определяется инжект команд от Gemma AI в сцены движка.
 */
object GameContext {
    var commandFlow: Flow<TinyGardenCommand>? = null
}
