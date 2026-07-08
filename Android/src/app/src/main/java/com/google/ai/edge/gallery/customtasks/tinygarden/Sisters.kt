package com.google.ai.edge.gallery.customtasks.tinygarden

/**
 * Сёстры × Кубик — онтология мира как данные (SPEC §8/§15).
 *
 * d6 — не рандомайзер. Каждая грань — принцип реальности. Выпавшая грань
 * определяет, кто говорит в диалоге, кто действует в бою и что происходит
 * в мире, когда кубик брошен на клетку.
 */

enum class SisterPrinciple(val display: String) {
    EGO("Ego, calculation"),
    COOPERATION("Co-operation, game theory"),
    HARMONY("Harmony, nature"),
    SACRIFICE("Sacrifice, chaos"),
    CREATION("Creation, death"),
    DUALITY("Choice, duality, spacetime"),
}

data class Sister(
    val face: Int,                      // 1-6
    val id: String,                     // "one".."six" — совпадает с ключами профилей
    val principle: SisterPrinciple,
    val spriteBase: String,             // "hero_white", "hero_ice", "sister_3".."sister_6"
    var isCompanion: Boolean = true,    // членство в партии переключается в рантайме
    var entityRef: String? = null,      // Entity.id, когда заспавнена как компаньон
    // Как она ходит по земле (SPEC §19.4). Промоушен Five — постоянная замена
    // паттерна на Queen, как в настоящих шахматах (SPEC §19.5)
    var currentPattern: MovementPattern = MovementPattern.Walker,
) {
    val displayName: String get() = id.replaceFirstChar { it.uppercase() }
}

sealed class DieFace {
    data class Occupant(val sister: Sister) : DieFace()
    data class Spell(val spellId: String, val label: String) : DieFace()
    object Blank : DieFace()            // сожжённый бросок — "трата очков просто"
}

data class Dice(
    val id: String,
    val faceCount: Int,                 // поддерживает d2, d4, d6, d8, d20
    val faces: Map<Int, DieFace>,
) {
    fun roll(rng: kotlin.random.Random = kotlin.random.Random.Default): Int =
        rng.nextInt(1, faceCount + 1)

    fun resolve(rollResult: Int): DieFace = faces[rollResult] ?: DieFace.Blank

    /** Редактор кубика — назначение грани это чистая мутация map. */
    fun withFace(faceIndex: Int, face: DieFace): Dice =
        copy(faces = faces + (faceIndex to face))
}

/**
 * Бросок кубика на клетку мира — механика "кидаем кубик под ноги".
 * Разные грани — разные эффекты в точке падения.
 */
object DiceCaster {
    fun castOnTile(die: Dice, targetCol: Int, targetRow: Int, engine: GameEngine): DieFace =
        applyRoll(die, die.roll(), targetCol, targetRow, engine)

    /**
     * Резолв уже известного значения броска. Физический кубик (DiceRoller)
     * бросается настоящей симуляцией и сообщает выпавшую грань сюда — не
     * бросаем второй раз, иначе картинка и эффект разойдутся.
     */
    fun applyRoll(die: Dice, roll: Int, targetCol: Int, targetRow: Int, engine: GameEngine): DieFace {
        val face = die.resolve(roll)
        when (face) {
            is DieFace.Occupant -> {
                if (face.sister.isCompanion) {
                    // Грань определяет активного актёра/голос до следующего броска
                    engine.setActiveSister(face.sister)
                } else {
                    // Сестра вне партии — бросок сгорает (правило из спеки)
                    engine.logMessage("The die shows ${face.sister.displayName}, but she is not with you. The moment passes.")
                }
            }
            is DieFace.Spell -> {
                engine.emitCustomEvent(
                    x = targetCol.toFloat(), y = targetRow.toFloat(),
                    radius = 64f, intensity = 1f,
                    payload = mapOf("spell" to face.spellId),
                )
                engine.logMessage("The die lands: ${face.label}.")
            }
            DieFace.Blank -> engine.logMessage("The die lands blank. Nothing stirs.")
        }
        return face
    }
}

/**
 * Профиль способностей Сестры — что доступно в сетке кнопок боя (SPEC §19.6).
 * Оглушение (canBeStunned) гейтится поверх профиля на уровне UI, не здесь.
 */
data class SisterAbilityProfile(
    val canMove: Boolean = true,
    val canScout: Boolean = true,
    val canFight: Boolean,
    val canCastMagic: Boolean,
    val canBeStunned: Boolean = true,
    val magicButtonLabelOverride: String? = null,  // у Four кнопка MAGIC читается DEFEND
)

val SISTER_PROFILES: Map<String, SisterAbilityProfile> = mapOf(
    "one"   to SisterAbilityProfile(canFight = true,  canCastMagic = false),
    "two"   to SisterAbilityProfile(canFight = true,  canCastMagic = false),
    "three" to SisterAbilityProfile(canFight = false, canCastMagic = true),
    "four"  to SisterAbilityProfile(canFight = false, canCastMagic = true,
                                     magicButtonLabelOverride = "DEFEND"),
    "five"  to SisterAbilityProfile(canFight = false, canCastMagic = false),  // никаких атак вовсе
    "six"   to SisterAbilityProfile(canFight = true,  canCastMagic = true),   // обе, без ограничений
)

/** Реестр шести Сестёр и кубик по умолчанию. */
object Sisters {
    // Подтверждённые фигуры: One — King, Five — Pawn, Six — Queen (SPEC §19.4).
    // Two/Three/Four — слоты Knight/Bishop/Rook зарезервированы, пока Walker.
    val ALL: List<Sister> = listOf(
        Sister(1, "one",   SisterPrinciple.EGO,         "hero_white",
               currentPattern = MovementPattern.King),
        Sister(2, "two",   SisterPrinciple.COOPERATION, "hero_ice"),
        Sister(3, "three", SisterPrinciple.HARMONY,     "sister_3"),
        Sister(4, "four",  SisterPrinciple.SACRIFICE,   "sister_4"),
        Sister(5, "five",  SisterPrinciple.CREATION,    "sister_5",
               currentPattern = MovementPattern.Pawn()),
        Sister(6, "six",   SisterPrinciple.DUALITY,     "sister_6",
               currentPattern = MovementPattern.Queen),
    )

    fun byId(id: String): Sister? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
    fun byFace(face: Int): Sister? = ALL.firstOrNull { it.face == face }

    fun profileOf(sister: Sister): SisterAbilityProfile =
        SISTER_PROFILES.getValue(sister.id)

    /** d6 по умолчанию: все шесть Сестёр по своим граням. */
    fun defaultDie(): Dice = Dice(
        id = "d6-default",
        faceCount = 6,
        faces = ALL.associate { it.face to (DieFace.Occupant(it) as DieFace) },
    )
}
