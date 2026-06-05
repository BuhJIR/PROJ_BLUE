package com.google.ai.edge.gallery.customtasks.tinygarden.korge

import korlibs.korge.scene.Scene
import korlibs.korge.view.*
import korlibs.image.color.Colors
import korlibs.math.geom.Point
import korlibs.time.seconds
import kotlinx.coroutines.launch
import com.google.ai.edge.gallery.customtasks.tinygarden.TinyGardenCommand
import com.google.ai.edge.gallery.customtasks.tinygarden.JrpgAction

class JrpgBattleScene : Scene() {
    private lateinit var playerSprite: SolidRect
    private lateinit var goblinSprite: SolidRect
    private lateinit var hpTextPlayer: Text
    private lateinit var hpTextGoblin: Text

    override suspend fun SContainer.sceneMain() {
        // Задний фон (Заглушка для леса/подземелья)
        solidRect(views.virtualWidth, views.virtualHeight, Colors["#1e272e"])

        // UI Контейнеры для Боевки
        val battleField = container {
            position(0, 0)
        }

        // Враг (Гоблин)
        val goblinContainer = battleField.container {
            position(views.virtualWidth * 0.7, views.virtualHeight * 0.4)
            goblinSprite = solidRect(100, 100, Colors.GREEN).xy(-50, -100)
            hpTextGoblin = text("HP: 50", textSize = 24f, color = Colors.RED).xy(-30, -130)
            text("GOBLIN", textSize = 20f, color = Colors.WHITE).xy(-30, -160)
        }

        // Герой (PROJ BLUE)
        val playerContainer = battleField.container {
            position(views.virtualWidth * 0.2, views.virtualHeight * 0.6)
            playerSprite = solidRect(80, 120, Colors.BLUE).xy(-40, -120)
            hpTextPlayer = text("HP: 100", textSize = 24f, color = Colors.CYAN).xy(-30, -150)
            text("HERO", textSize = 20f, color = Colors.WHITE).xy(-20, -180)
        }

        // Подписываемся на команды от Gemma AI
        GameContext.commandFlow?.let { flow ->
            launch {
                flow.collect { command ->
                    processAiCommand(command)
                }
            }
        }
    }

    private suspend fun processAiCommand(command: TinyGardenCommand) {
        when (command.action) {
            JrpgAction.ATTACK -> {
                // Анимация рывка к врагу и удар
                val startPos = Point(playerSprite.x, playerSprite.y)
                playerSprite.tween(playerSprite::x[startPos.x + 200], time = 0.2.seconds)
                showFloatingDamage(command.damage, goblinSprite.parent!!)
                playerSprite.tween(playerSprite::x[startPos.x], time = 0.2.seconds)
                // Обновляем HP
                // hpTextGoblin.text = "HP: ..." (логика высчитывается во ViewModel, здесь только визуал)
            }
            JrpgAction.MAGIC -> {
                // Анимация заклинания
                playerSprite.color = Colors.PURPLE
                // Частицы магии на враге
                val magicFX = goblinSprite.parent!!.solidRect(120, 120, Colors.YELLOW).xy(-60, -110)
                magicFX.tween(magicFX::alpha[0.0], time = 0.5.seconds)
                magicFX.removeFromParent()
                showFloatingDamage(command.damage, goblinSprite.parent!!)
                playerSprite.color = Colors.BLUE
            }
            JrpgAction.ENEMY_ATTACK -> {
                // Анимация атаки гоблина
                val startPos = Point(goblinSprite.x, goblinSprite.y)
                goblinSprite.color = Colors.RED
                goblinSprite.tween(goblinSprite::x[startPos.x - 200], time = 0.2.seconds)
                showFloatingDamage(command.damage, playerSprite.parent!!)
                goblinSprite.tween(goblinSprite::x[startPos.x], time = 0.2.seconds)
                goblinSprite.color = Colors.GREEN
            }
            else -> {}
        }
    }

    private suspend fun showFloatingDamage(damage: Int, targetContainer: Container) {
        val dmgText = targetContainer.text("-$damage", textSize = 40f, color = Colors.RED).xy(-20, -50)
        dmgText.tween(dmgText::y[dmgText.y - 100], dmgText::alpha[0.0], time = 1.seconds)
        dmgText.removeFromParent()
    }
}
