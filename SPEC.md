# PROJ☆BLUE — Technical Specification & Architecture Audit

**Document version:** 1.0
**Audit date:** as of commit `48c09803` (README rewrite) + subsequent structure/audit commits
**Scope:** Full read of all 14 source files under `customtasks/tinygarden/` (2,338 lines)
**Purpose:** Exhaustive map of what exists, what is broken, what is missing, and how the world generator should scale.

---

## 0. How to read this document

Findings are graded by severity:

- 🔴 **CRITICAL** — breaks a core promise of the game (the Soul can't act, the world can't be saved, combat doesn't resolve)
- 🟠 **STRUCTURAL** — works today but will not survive scale (O(n) where it should be O(log n), dead code paths, silent failures)
- 🟡 **GAP** — a system that is designed but not implemented, or implemented but disconnected
- 🟢 **POLISH** — cosmetic, ergonomic, or DX improvements

Each finding names the exact file and, where useful, the exact function.

---

## 1. 🔴 CRITICAL — Dual GameEngine instantiation

**Files:** `TinyGardenTask.kt` (`initializeModelFn`), `TinyGardenViewModel.kt` (class body)

`TinyGardenViewModel` owns the canonical engine:

```kotlin
val engine = GameEngine()
val aiBridge = AiSoulBridge(engine)
```

This is the engine the renderer reads from (`IsoMapRenderer(engine = viewModel.engine, ...)`) and the engine `resetEngine()` correctly re-wires on reset.

But `TinyGardenTask.initializeModelFn` — called once, on first model load — does this instead:

```kotlin
val freshBridge = com.google.ai.edge.litertlm.tool(
  com.google.ai.edge.gallery.customtasks.tinygarden.AiSoulBridge(
    com.google.ai.edge.gallery.customtasks.tinygarden.GameEngine()
  )
)
```

A **second, orphaned `GameEngine`** is created and wired to the model's tool set. The model's very first tool calls — `executeMove`, `executeDamage`, `buildStructure` — all mutate a `GameEngine` instance that no `Compose` renderer ever observes and that `TinyGardenViewModel.engine` never touches.

**Symptom this explains:** every bug report of "the Soul described the world but the character didn't move" on first load, before any `resetEngine()` call has run. `resetEngine()` — which *does* use `aiBridge` (the ViewModel's) — masks the bug once triggered, which is why it wasn't caught earlier: the game "heals itself" after the first manual reset.

**Fix:** `initializeModelFn` must accept the engine/bridge from the caller rather than constructing its own. `CustomTask.initializeModelFn` doesn't currently have a channel for this — either:
- (a) move engine construction fully into `TinyGardenViewModel` and have `initializeModelFn` receive `aiBridge` as a parameter, or
- (b) make `AiSoulBridge`/`GameEngine` a `@Singleton` provided by Hilt so both call sites resolve to the same instance.

(b) is architecturally cleaner and prevents this class of bug from recurring when a third call site is added later.

---

## 2. 🔴 CRITICAL — Behaviour is decided but never executed

**Files:** `Behaviour.kt` (`BehaviourDecider.decide`), `GameEngine.kt` (`wakeEntity`)

`wakeEntity` calls `BehaviourDecider.decide(...)` and stores the result:

```kotlin
entity.currentBehaviour = newBehaviour
```

That's it. Nothing ever reads `entity.currentBehaviour` and turns it into a `moveEntity` call, an `applyDamage` call, or anything else. The sealed class `Behaviour` has seven variants — `Attack`, `Navigate`, `Collect`, `Flee`, `Socialize`, `Wander`, `Idle` — and **all seven are dead ends**. An NPC can correctly *decide* to flee an explosion and will silently stand still forever.

This is the single largest gap in the project relative to what was described in conversation (`"орк вбежал в город — сами побежали бить, без тика, без решения модели"`). The stimulus-response wiring (SpatialHash → EventBus → BehaviourDecider) is real and correct. The missing piece is the **actuator** — something that runs on a schedule (or on each `wakeEntity` call) and turns `Behaviour.Attack(targetId)` into `engine.applyDamage(targetId, ...)`, or `Behaviour.Navigate(tx, ty, reason)` into `engine.executePath(entity.id, Pathfinder.findPath(...))`.

**Required addition — `BehaviourExecutor`:**

```kotlin
object BehaviourExecutor {
    fun execute(entity: Entity, engine: GameEngine, map: IsoMap) {
        when (val b = entity.currentBehaviour) {
            is Behaviour.Attack    -> engine.applyDamage(b.targetId, computeDamage(entity))
            is Behaviour.Navigate  -> {
                val path = Pathfinder.findPath(entity.col, entity.row, b.tx, b.ty, map)
                path?.let { engine.executePath(entity.id, it) }
            }
            is Behaviour.Flee      -> { /* path away from threat, opposite vector */ }
            is Behaviour.Collect   -> { /* navigate to item, then engine.pickUpItem(...) */ }
            is Behaviour.Socialize -> { /* navigate adjacent to targetId, emit SOCIAL event */ }
            Behaviour.Wander       -> { /* pick random adjacent walkable tile, navigate */ }
            Behaviour.Idle         -> {} // no-op, correct
            is Behaviour.Custom    -> { /* dispatch table for model-defined behaviours */ }
        }
    }
}
```

This must be called from `wakeEntity` immediately after `decide()`, and `Behaviour.Collect`/`Behaviour.Attack` resolution needs a defined damage/pickup formula (see §7, Combat Math).

---

## 3. 🔴 CRITICAL — No persistence layer

**Files:** none exist. `GameEngine.currentState()` returns an in-memory `GameState`; there is no `save()`/`load()` anywhere in the 14 files.

Closing the app — or Android reclaiming the process, which happens routinely on a phone — erases: entity positions, flags, memory maps (including `home` addresses set by NPCs), `structureOverrides` (every building the Soul has ever built), and the entire conversation history the Soul's context depends on.

For a game whose core pitch is "the world lives on its own," a world that cannot outlive a phone call is a contradiction of the premise.

**Required:** `GameState` and `Entity` need a serialisable form. `Entity.memory: MutableMap<String, Any>` is the immediate blocker — `Any` doesn't round-trip through `kotlinx.serialization` or `Gson` without a sealed wrapper. Suggest:

```kotlin
sealed class MemoryValue {
    data class Str(val v: String) : MemoryValue()
    data class Num(val v: Double) : MemoryValue()
    data class Coord(val col: Int, val row: Int) : MemoryValue()
    data class Bool(val v: Boolean) : MemoryValue()
}
```

replacing `MutableMap<String, Any>` with `MutableMap<String, MemoryValue>`. This is a breaking change to every call site that does `memory["direction"] = "SOUTH"` (a raw `String`) or `memory["home"] as Pair<*,*>` (an unchecked cast already flagged in §9) — worth doing once, early, before more call sites accumulate.

Persistence target: Android's `DataStore` (already a dependency — `DataStoreRepository` is injected into `TinyGardenViewModel`) is the natural fit; serialise `GameState` to JSON via `Gson` (already used in `AiSoulBridge`'s `JSONObject` parsing) and write on a debounced timer or on `onPause`.

---

## 4. 🔴 CRITICAL — `isWalkable` does not account for structures

**Files:** `IsoRenderer.kt` (`IsoMap.isWalkable`), `GameEngine.kt` (`structureOverrides`)

```kotlin
fun isWalkable(col: Int, row: Int): Boolean {
    val t = tileAt(col, row).base
    return t != TileType.WATER
}
```

`isWalkable` only ever consults the procedural `IsoMap` tile — it has no reference to `GameEngine.structureOverrides`. A player can path directly through the middle of any building the Soul constructs with `buildStructure(...)`. The pyramid in the reference screenshot would be fully walkable air as far as `Pathfinder.findPath` is concerned.

**Fix:** `isWalkable` needs either an `engine: GameEngine?` parameter, or (cleaner) `structureOverrides` needs to live *inside* `IsoMap` rather than bolted onto `GameEngine` as a side-channel `HashMap`. The latter also fixes §11 (structures are per-engine-instance state that doesn't survive `regenerateAroundPlayer`'s new `IsoMap` construction — see next finding).

---

## 5. 🟠 STRUCTURAL — Structures live outside the map buffer and don't compose with regeneration

**Files:** `GameEngine.kt` (`structureOverrides`), `IsoRenderer.kt` (`generateMapAround`, the `LaunchedEffect(player.col, player.row)` block)

`structureOverrides` is a flat `HashMap<Pair<Int,Int>, LayeredTileEx>` on `GameEngine`, consulted at render time via `engine?.structureTileAt(wc, wr)`. This *currently* works because the check happens per-tile at draw time regardless of which 48×48 `liveMap` buffer is active. But it means:

- Structures are **not** part of `IsoMap.tileAt()` — anything that calls `map.tileAt()` directly (Pathfinder, `isWalkable`, any future minimap/fog-of-war system) sees the *procedural* tile only, never the structure. This is the same root cause as §4, generalised: the override is a rendering-layer patch, not a world-model fact.
- There is no bound on `structureOverrides` size. Every tile of every building ever constructed accumulates for the lifetime of the `GameEngine` instance with no eviction — a session with a dozen `buildStructure` calls (5-level ziggurat ≈ 25–49 tiles per call, per the `baseSize=7` default) reaches thousands of entries. Not fatal at Android scale today, but it's an unbounded growth pattern with no persistence (§3) and no spatial indexing (a linear key lookup happens every tile, every frame — see §6).

**Fix:** fold structure tiles into `IsoMap` itself. When `applyStructure` runs, write directly into the currently-live `IsoMap.tiles` array (if the coordinates fall inside the current buffer) *and* keep the override list for re-application when `generateMapAround` rebuilds the buffer during edge-regeneration. This makes `tileAt()` structure-aware everywhere, for free, and gives `isWalkable` correct behaviour without a parameter change.

---

## 6. 🟠 STRUCTURAL — SpatialHash is built but only the player is ever inserted correctly

**File:** `GameEngine.kt`

`spatialHash.insert(state.player)` runs once, in `init`. `spawnEntity` correctly calls `spatialHash.insert(entity)`. `moveEntity` correctly calls `spatialHash.move(...)`. This part is right.

But `SpatialHash.query()` (in `SpatialHash.kt`) has a **coordinate bug** worth flagging even though it's not fatal:

```kotlin
fun query(px: Float, py: Float, radius: Float): List<Entity> {
    ...
    for (dx in -r..r) for (dy in -r..r) {
        cells[key(cx + dx, cy + dy)]?.forEach { e ->
            val ex = e.x - px / cellSize * cellSize   // ← this line
            val ey = e.y - py / cellSize * cellSize
            if (ex * ex + ey * ey <= radius * radius) result.add(e)
        }
    }
```

`e.x` is a **grid coordinate** (`Entity.x: Int`, aliased as `col`), but `px`/`py` passed into `query()` are usually **world/pixel-scale floats** — e.g. `propagateEvent` calls `spatialHash.query(event.x, event.y, event.radius)` where `event.radius` defaults to `128f` (pixel-scale, per the `WorldEvent` doc comment: *"radius: Float = 128f, // пиксели / grid units"*). The distance check mixes a grid-space entity position against a pixel-space query point without a consistent conversion. In practice this makes `query()`'s actual catchment radius wrong by whatever factor separates grid units from `cellSize` (64f) — either too permissive or too restrictive depending on which events are near the origin, and it degrades unpredictably as the world scrolls away from (0,0).

**Fix:** decide once whether `Entity.x/y` and `WorldEvent.x/y` are the same unit space (recommend: **grid units everywhere**, multiply by `TILE_W`/`TILE_H` only at render time) and make `SpatialHash.cellSize` match that space consistently. Then simplify `query`'s distance check to plain `(e.x - px)² + (e.y - py)²` with no `cellSize` division.

---

## 7. 🟡 GAP — No combat resolution system at all

**Files:** `AiSoulBridge.kt` (`executeDamage`), `GameEngine.kt` (`applyDamage`)

Damage is a bare integer subtraction:

```kotlin
fun applyDamage(amount: Int): Boolean {
    hp = (hp - amount).coerceAtLeast(0)
    return hp == 0
}
```

There is no defence stat, no damage type, no critical hit, no miss chance, no `mp` cost for the `executeDamage` tool (despite `Entity` having `mp`/`maxMp` fields that are set at construction and then **never read again anywhere in the codebase** — confirmed by grep across all 14 files), no status effects, no turn order, no action economy. `GameMode.BATTLE` exists as an enum value and `transitionMode` logs `"⚔ Battle begins!"` — and that is the entire implementation of the battle mode. Nothing branches on `gameState.mode == GameMode.BATTLE` anywhere in `IsoRenderer.kt` or `GameEngine.kt` to change available actions, UI, or turn structure.

This is not a bug — it's an entire unbuilt system, correctly flagged `◐ In Progress` in the README. Listed here so the specification below (§14) can be load-bearing rather than aspirational.

---

## 8. 🟡 GAP — Six Sisters exist as sprites, not as data

**Files:** `IsoRenderer.kt` (`spritePath`), `AiSoulBridge.kt`, `GameEngine.kt`

The Sisters are currently a **string-matching function**:

```kotlin
fun spritePath(charName: String, dir: Direction): String {
    val base = when {
        charName.contains("sister_4", ignoreCase = true) -> "sprites/sister_4"
        ...
```

There is no `Sister` data class, no principle/flag mapping (the README's table — *One: ego/calculation, Two: co-operation* etc. — exists only in prose, nowhere in code), no dice-face assignment structure, and `sprite_meta.json`'s `"dice"` block (`"d6": { "faces": 6, "default_assignment": [...] }`) is **read by nothing** — no Kotlin file references `dice` or parses that JSON key. The asset metadata describes a system that has zero corresponding runtime code.

**Required — minimum viable data model** (expand in §15):

```kotlin
enum class SisterPrinciple { EGO, COOPERATION, HARMONY, SACRIFICE, CREATION, DUALITY }

data class Sister(
    val face: Int,                 // 1-6
    val name: String,              // "One".."Six"
    val principle: SisterPrinciple,
    val spriteKey: String,         // "sister_3" etc — already exists as a string, formalise it
    val isCompanion: Boolean = false,  // active party member vs off-die
)

data class Die(
    val faces: Map<Int, DieFace>,   // 1..N (supports non-d6 dice per Serj's spec)
)
sealed class DieFace {
    data class SisterFace(val sister: Sister) : DieFace()
    data class SpellFace(val spellId: String) : DieFace()
    object Blank : DieFace()
}
```

---

## 9. 🟡 GAP — Unsafe casts on `Entity.memory`

**File:** `Behaviour.kt` (`BehaviourDecider.decide`), `GameEngine.kt` (multiple)

```kotlin
if (entity.hasFlag("TIRED")) {
    val home = entity.memory["home"]
    if (home is Pair<*, *>) {
        return Behaviour.Navigate(home.first as Int, home.second as Int, "rest")
    }
}
```

`home.first as Int` is an unchecked cast — if anything ever stores `memory["home"]` as something other than `Pair<Int,Int>` (e.g. a `Coord` data class added for §3's persistence fix), this throws `ClassCastException` at runtime with no recovery, silently killing whatever coroutine called `BehaviourDecider.decide` (likely inside `wakeEntity`, itself inside an `EventBus` callback — an exception there could take down event propagation for *every* entity mid-loop, not just the one with bad memory).

Same class of issue in `GameEngine.moveEntity`'s direction-string logic and everywhere `memory["direction"] as? String` appears — those are at least guarded with `as?`, which is correct defensive style; `home.first as Int` is not, and is the only unguarded cast found in the audit.

**Fix:** ties directly into §3's `MemoryValue` sealed class — once memory has a typed wrapper, this pattern becomes exhaustive `when` with no unchecked casts possible.

---

## 10. 🟡 GAP — Pure JSON parsing is fragile against mixed model output

**File:** `TinyGardenViewModel.kt` (`getCommand`)

```kotlin
if (response.trim().startsWith("{") && response.trim().endsWith("}")) {
    aiBridge.processPureJson(response)
} else {
    engine.logMessage("Soul: " + response)
}
```

This only recognises a JSON command if the model's **entire response** is exactly one JSON object with no surrounding text. The system prompt doesn't forbid the model from writing narrative *and* a command in the same turn (in fact tool-calling and pure-JSON are documented as two co-existing channels), but the parser can't extract a JSON block embedded in prose — `"The goblin snarls. {\"action\":\"DAMAGE\",...}"` falls through to `logMessage` as raw text, silently dropping the command. Given the earlier observed behaviour (§ conversation history: *"путь готов, вызови ход"* → model described movement in words instead of acting), this exact failure mode — model embeds a command inside a sentence — is a live risk, not theoretical.

**Fix:** extract the first balanced `{...}` substring from the response (regex or a small bracket-counting scanner) regardless of surrounding text, run `processPureJson` on that substring if found, and treat everything outside it as narrative for `logMessage`.

---

## 11. 🟡 GAP — `buildStructure` tool is wired but invisible to the model

**Files:** `AiSoulBridge.kt` (tool exists), `TinyGardenTask.kt` (`SYSTEM_PROMPT`)

The `@Tool fun buildStructure(...)` is correctly annotated and would appear in the tool-calling schema sent to Gemma. But `SYSTEM_PROMPT`'s hand-written tool list — the part the model reads as *prose*, separate from the structured schema — only documents four tools:

```
executeMove(target, direction, steps)
executeDamage(target, amount)
emitWorldEvent(x, y, radius, intensity)
bulkApplyFlag(matchFlags, removeGroup, addFlags)
```

`buildStructure` is missing from this list, and the DSL grammar (`"3×levels; stairs; non-trees; stone; open-type; flat"`) is nowhere in the prompt. A small on-device model without the DSL grammar in context is unlikely to reliably produce well-formed DSL strings purely from the tool's `@ToolParam` description, especially for a compact, unusual syntax like this one.

**Fix:** add `buildStructure` to the prompt's tool list with 2–3 example DSL strings inline, matching the density of examples already given for the JSON commands.

---

## 12. 🟡 GAP — Peak/pyramid top is a stub, not a shape

**File:** `StructureDSL.kt` (`StructureGenerator.generate`)

```kotlin
val shouldPlace = if (level == spec.levels - 1 && !spec.flatTop) {
    isEdge || (dr == 0 && dc == 0)
} else true
```

When `flatTop = false` ("peak"/"pyramid"), the top level places only its outer ring plus a single centre tile — this produces a hollow square with a dot in the middle, not a pyramid silhouette. The reference image (the multi-coloured pyramid stack) and the original ask ("создай исчерпывающий список") both imply a genuine tapering peak. This was flagged as a known limitation when `StructureDSL` shipped ("Ограничение сейчас: пик работает грубо") — recorded here so it isn't lost.

**Fix direction:** a true peak needs the *top* level's footprint to shrink by more than `margin` per level — effectively an inner recursive call to `generate()` with a smaller `baseSize` and `levels=1`, `flatTop=true`, stacked on top of the ziggurat body. Or: interpolate a cone by placing single-tile columns whose height increases toward the centre, similar to the pyramid apex in the reference PNG (magenta triangle capping a cyan capping a black platform).

---

## 13. 🟠 STRUCTURAL — Dead code: `TinyGardenTools.kt` and `ConversationHistoryPanel` command flow

**Files:** `TinyGardenTools.kt` (entire file), `TinyGardenTask.kt` (`commandFlow`), `TinyGardenScreen.kt` (`commandFlow: Flow<TinyGardenCommand>, // kept for signature compatibility`)

`TinyGardenTools.kt` defines a second, unrelated tool set — `performAttack`, `castMagic`, `enemyAttack` — built around a `TinyGardenCommand`/`JrpgAction` enum that predates `AiSoulBridge`. It is never instantiated: no call site does `TinyGardenTools(onFunctionCalled = ...)`. `_updateChannel: Channel<TinyGardenCommand>` in `TinyGardenTask` is created and drained (`clearQueue()`) but nothing ever sends to it — `commandFlow` is a `Flow` that will never emit. This is confirmed leftover from an earlier architecture (the JSON-based `AiSoulBridge` superseded it) that was never deleted.

**Fix:** delete `TinyGardenTools.kt` entirely; remove `commandFlow` parameter from `TinyGardenScreen`'s signature and `_updateChannel`/`clearQueue()` from `TinyGardenTask`. Zero behaviour change, removes ~100 lines of dead surface area that a future contributor (or a future audit) will otherwise re-investigate from scratch.

---

## 14. 🟢 POLISH — Miscellaneous smaller findings

- **`Need.satisfiedByFlags` is never populated.** `Entity.needs: MutableList<Need>` exists and `BehaviourDecider` correctly consults it, but no code anywhere (`AiSoulBridge`, `GameEngine`, the `SPAWN` JSON handler) ever adds a `Need` to a spawned entity. The hunger/fatigue system described in conversation ("*если у них есть усталость они идёт в дом*") has the decision logic built and the data structure ready, with no producer.
- **`WorldItem.quantity` is never decremented.** Picking up a `FRUIT` item has no code path that reduces or removes it — `Behaviour.Collect` isn't executed at all (§2), so this is currently unreachable, but the field itself has no consumer even in isolation.
- **`FlagGroup` circular registration risk.** `GameObject.registerGroup`/`addGroup` has no cycle detection — a `FlagGroup` whose `.flags` set names another group (rather than a leaf flag) would silently no-op rather than error, since `addFlag` only ever adds to the flat `flags: MutableSet<String>`. Not currently exploitable (nothing generates nested groups) but worth a guard before `buildStructure`-style DSL generation extends to groups.
- **`GameEngine.scope` has no cancellation hook.** `CoroutineScope(Dispatchers.Default + SupervisorJob())` lives for the process lifetime of `GameEngine`. Since §1's fix will likely make `GameEngine` a Hilt singleton, this is fine long-term, but until then every `TinyGardenTask.initializeModelFn` call (§1) leaks a fresh, never-cancelled `SupervisorJob`.
- **Segmented HP bar caps at 12 segments for any `maxHp` above 72** (`segments = (maxHp / 6).coerceIn(1, 12)`), meaning a boss with 200 HP shows identical bar granularity to one with 72 HP. Fine for early-game balance, worth revisiting once enemy HP ranges are finalised (§16).

---

## 15. Sisters × Dice — full data specification

Expanding §8 into the complete system as discussed:

```kotlin
enum class SisterPrinciple(val display: String) {
    EGO("Ego, calculation"),
    COOPERATION("Co-operation, game theory"),
    HARMONY("Harmony, nature"),
    SACRIFICE("Sacrifice, chaos"),
    CREATION("Creation, death"),
    DUALITY("Choice, duality, spacetime"),
}

data class Sister(
    val face: Int,
    val id: String,                     // "one".."six" — matches sprite key suffix
    val principle: SisterPrinciple,
    val spriteBase: String,              // "hero_white", "hero_ice", "sister_3".."sister_6"
    var isCompanion: Boolean = false,    // party membership toggled at runtime
    var entityRef: String? = null,       // Entity.id when spawned as companion
)

sealed class DieFace {
    data class Occupant(val sister: Sister) : DieFace()
    data class Spell(val spellId: String, val label: String) : DieFace()
    object Blank : DieFace()             // burns the roll — "если ONE выпало но её нет в команде"
}

data class Dice(
    val id: String,
    val faceCount: Int,                  // supports d2, d4, d6, d8, d20 per spec
    val faces: Map<Int, DieFace>,
) {
    fun roll(rng: kotlin.random.Random = kotlin.random.Random.Default): Int =
        rng.nextInt(1, faceCount + 1)

    fun resolve(rollResult: Int): DieFace = faces[rollResult] ?: DieFace.Blank
}

/**
 * Casting a die onto the world — the "кидаем кубик под ноги" mechanic.
 * Different faces trigger different world effects at the target tile.
 */
object DiceCaster {
    fun castOnTile(die: Dice, targetCol: Int, targetRow: Int, engine: GameEngine) {
        val roll = die.roll()
        when (val face = die.resolve(roll)) {
            is DieFace.Occupant -> {
                // face determines active speaker/actor for this context
                engine.setActiveSister(face.sister)
            }
            is DieFace.Spell -> {
                engine.emitCustomEvent(
                    x = targetCol.toFloat(), y = targetRow.toFloat(),
                    radius = 64f, intensity = 1f,
                    payload = mapOf("spell" to face.spellId)
                )
            }
            DieFace.Blank -> {
                engine.logMessage("The die lands blank. Nothing stirs.")
            }
        }
    }
}
```

**Combat integration:** each combat turn opens with a roll. The resolved `DieFace` determines who acts (`Occupant`) — if that Sister isn't `isCompanion`, the turn is spent (per Serj's original spec: *"если выпало 2, но TWO нет в команде — ничего, трата очков просто"*).

**Dialogue integration:** a roll before a dialogue exchange picks which Sister's `principle` colours the Soul's response tone for that exchange — this is a `SYSTEM_PROMPT` injection point, not new game logic: append `"Currently speaking through: ${activeSister.principle.display}"` to the per-turn context sent to Gemma.

**World-cast integration (flowers/trees example from spec):** `payload["spell"]` on cast maps to world mutation — e.g. `"grow_flowers"` → `engine.applyStructure` with a small `FlowerPatch` tile stamp at the target tile using the *same* `structureOverrides` mechanism as buildings (§5's fix makes this and building generation share one code path).

**Dice editor:** the requested "редактор кубика" — assigning a Sister or a spell to a face — is a pure `Dice.faces` map mutation, `faces = faces + (faceIndex to DieFace.Spell(...))`. No engine changes needed once the data model above exists; it's a UI screen over this map.

---

## 16. Generator scaling — how `StructureGenerator` should grow

The current generator (§12 covers its one known bug) handles exactly one shape family: square ziggurats. Scaling this into "the generator" the world needs means separating **shape**, **material**, and **decoration** into independently composable passes, matching the DSL's own token design (`material`, `shape`, `feature` tokens are already orthogonal in the grammar — the generator should be too):

```kotlin
interface StructureShape {
    fun footprint(level: Int, spec: StructureSpec): List<Pair<Int,Int>>  // local offsets
}

object ZigguratShape : StructureShape { /* current implementation, extracted */ }
object ConeShape      : StructureShape { /* fixes §12 — true tapering peak */ }
object RingShape       : StructureShape { /* hollow — walls only, for courtyards */ }
object TowerShape      : StructureShape { /* constant footprint, height-only growth — single-tile columns */ }

object StructureGenerator {
    fun generate(spec: StructureSpec, shape: StructureShape, centerCol: Int, centerRow: Int): List<...> {
        // shape.footprint() replaces the current inline square-loop
    }
}
```

This lets the DSL grow a `shape` token (`"cone"`, `"ring"`, `"tower"`) without touching the parser's core loop, and lets each shape be unit-tested against the reference geometry independently (the reference PNG's stair placement, for instance, is shape-specific — a `RingShape` courtyard needs stairs on the *inner* face, a `ZigguratShape` needs them on the outer face, which today is hardcoded to `StepDirection.SOUTH` regardless of shape).

**Decoration pass (fauna/flora hooks, §17):** once §5 folds structures into `IsoMap` proper, a second generator pass can walk the *finished* structure's footprint and stamp features — torches on stair landings, banners on flat-top platforms, moss on WOOD material below a height threshold — using the same `payload`-driven event system the Sisters' world-casting already needs (§15). One system, two call sites.

**Biome-aware generation:** `generateTile`'s noise function (`IsoRenderer.kt`) currently has no concept of biome — everything within noise range is `GRASS`/`DIRT`/`STONE`/`WATER`/`WOOD` from one formula. Structures generated via DSL are material-agnostic to what surrounds them (a `stone` ziggurat can spawn mid-lake). A biome layer — a second, lower-frequency noise pass that gates which `TileType`s and which `StructureShape`s are eligible at a given world coordinate — is the natural next scaling step once fauna/flora (§17) need habitat rules ("Three notices flowers before anyone else" implies flowers have a biome-conditioned spawn rule, not a uniform one).

---

## 17. Full missing-systems catalogue

Everything below is absent from the 14 audited files. Grouped by dependency order — earlier entries unblock later ones.

### 17.1 Combat resolution (blocks: dice integration §15, enemy AI §17.3)
Turn order, action point economy, hit/miss/crit formulas, defence stat on `Entity`, `mp` cost enforcement on spell-casting tools (currently `mp`/`maxMp` are set and never read — §7), status effect application hooks on the segmented HP bar.

### 17.2 Inventory & items
`WorldItem` exists as a world-placed object; there is no player-carried inventory data structure, no `pickUp`/`drop`/`use` engine methods, no UI surface for it. Blocks: crafting, equipment stat modifiers, quest items.

### 17.3 Enemy AI beyond flag-reaction
`BehaviourDecider` (once §2 is fixed) gives enemies *reactive* behaviour — they respond to events in range. There is no patrol route data, no home/spawn-point leash radius, no aggro-then-give-up-and-return-to-post cycle. `Entity.memory["home"]` exists for NPCs (§9) but nothing populates or reads it for enemy-specific patrol logic.

### 17.4 Fauna
No passive-creature category exists — `hasFlag("ENEMY")` is binary. A rabbit that flees but doesn't fight needs a third disposition (`hasFlag("SKITTISH")`, already anticipatable in `BehaviourDecider`'s existing `Flee` branch, but the spawn-time flag assignment and the "harmless unless cornered" logic doesn't exist).

### 17.5 Flora
`WorldItem(type = "FRUIT")` is the entire flora system today — a static pickup, not a growing plant. No regrowth timer, no seasonal variation, no "Three notices it first" perception-radius bonus (which would be a `Sister.principle == HARMONY` check feeding into `BehaviourDecider`'s nearby-item search radius — an easy addition once §15's data model exists).

### 17.6 Structures — interiors
§16 covers exterior shape generation. Nothing generates interior floor plans, doorways, or a mode-switch when a player enters a building (comparable to the existing `GameMode.OVERWORLD ↔ BATTLE` transition, likely a third `GameMode.INTERIOR` case).

### 17.7 Caves & dungeons
No underground layer exists. `IsoMap`'s height system (§ existing, positive-only 0–4) has no negative-height / subterranean equivalent, and the renderer's `TILE_LIFT` math (`y = ... - height * TILE_LIFT`) would need sign-handling verified for below-ground values before this is safe to add.

### 17.8 Companion system
Sisters are sprites selectable as the *player* skin (`spritePath` picks one based on name matching) but there is no multi-entity party — only one `GameState.player` exists. Turning Sisters into active party members (§15's `isCompanion` flag) needs `GameState` to hold `party: List<Entity>` alongside the single `player`, and every system that currently special-cases `entityId.equals(player.name, ...)` (moveEntity, applyDamage, executePath — three separate call sites in `GameEngine.kt` today) needs to generalise to "any party member," not just the one player.

### 17.9 Dialogue trees
The Soul currently free-narrates per-turn with no memory of past branches beyond raw chat history. A structured dialogue-tree layer — where NPC conversations have persistent state machines the Soul navigates rather than freely improvises — doesn't exist.

### 17.10 Status effects
No poison/fatigue/fear/blessing implementation. `hasFlag("POISONED")` is settable via the existing `SET_FLAG` JSON command (demonstrated in the system prompt's own example) but nothing *ticks* — no periodic damage, no duration/expiry, no stacking rules.

### 17.11 Day/night cycle
No `GameState.timeOfDay` field, no tick-based advancement, no behaviour-flag activation gated by time.

### 17.12 Weather
No weather state, no tile-traversal-speed modifier, no interest-radius modifier (fog narrowing `Entity.interestRadius`).

### 17.13 Audio
Zero audio code anywhere in the 14 files.

---

## 18. Recommended fix order

Given the dependency graph above, the sequence that unblocks the most downstream work per fix:

1. **§1 — dual engine.** Everything else is unverifiable while the model might be talking to a ghost engine.
2. **§2 — BehaviourExecutor.** The entire "world lives on its own" pitch is inert without this.
3. **§9 — MemoryValue sealed class.** Small, mechanical, but §3 (persistence) and §2 (behaviour navigate-home) both need it and will be harder to retrofit later.
4. **§5 + §4 — fold structures into IsoMap.** Unblocks correct pathfinding around buildings and is a prerequisite for §16's shape-composition refactor.
5. **§10 — robust JSON extraction from mixed model output.** Directly addresses an already-observed failure mode.
6. **§11 — buildStructure in the prompt.** One paragraph, immediate payoff.
7. **§13 — delete dead code.** Zero risk, immediate clarity gain for the next person reading this codebase (including future audits).
8. **§3 — persistence.** Now safe to build on a stable memory model (step 3) and a settled world-mutation surface (step 4).
9. **§15 — Sisters/Dice data model.** Everything needed to start the dice mechanic exists in spec form here; implementation can begin independently of combat (§17.1).
10. **§17.1 — combat.** The largest remaining system; best attempted after the dice data model exists so combat can be designed *around* the die-roll-picks-actor mechanic from day one rather than bolted on after.

Everything in §17.2 onward is content-and-systems work that can proceed in parallel once the above foundation is solid, in whatever order matches what's most fun to build next.

---

*Compiled from a full read of every `.kt` file under `customtasks/tinygarden/` as of this audit. No file was skimmed; every finding above traces to a specific line or absence thereof in the current `main` branch.*

---

## 19. Addendum — Chess Movement, World Law, and NPC Micro-Agents

*Added following extended design discussion after the initial audit (§1–§18). This section is additive — nothing above is superseded.*

### 19.1 The problem this section solves

Two separate design conversations converged on the same architectural need:

1. **World-scale narrative causality.** A plot event ("an ancient artefact extinguishes the sun") needs to become *ground truth* for every NPC simultaneously — not a flag one entity has and others don't, but a rewritten law of reality that all perception runs through from that point forward.
2. **Deliberate movement friction.** Four-directional BFS movement (§ existing `Pathfinder.kt`) makes every tile reachable by every actor at the same cost. Assigning each Sister a **chess piece's movement pattern** turns "where do I go" into "which Sister do I need to be to get there" — a puzzle layer bolted onto exploration, not combat (combat, per this conversation, has no movement — it's turn-based JRPG resolution with no positional grid).

Both are specified below as concrete data models and call sites, following the same standard as §1–§18: file-level, not aspirational.

---

### 19.2 World Law — the Soul rewrites the system prompt, NPCs only read it

**Current state (confirmed by audit):** `SYSTEM_PROMPT` in `TinyGardenTask.kt` is a `private const val` — compiled once, immutable for the life of the app. There is no mechanism for in-game events to alter what the Soul believes about the world, and — critically for this addendum — **no NPC-level agent exists at all**. `BehaviourDecider.decide()` (§2) is a pure deterministic function, not an LLM call. NPCs today have no perception of narrative, only of flags and spatial events.

**Required addition — `worldLaw` as live game state:**

```kotlin
// GameState gains a field alongside mode, player, entities, items, battleLog:
data class GameState(
    ...
    val worldLaw: String = DEFAULT_WORLD_LAW,
)

const val DEFAULT_WORLD_LAW = "A world of quiet fields and old stone. The sun rises and sets as it always has."
```

**New Soul tool — the only thing permitted to change it:**

```kotlin
@Tool(description = "Rewrite the fundamental law of the world. Use only for major plot events that " +
    "change reality itself for every inhabitant simultaneously — e.g. an artefact extinguishing the sun, " +
    "a plague of silence, the invention of fire. Every NPC agent will perceive this as ground truth " +
    "from their next wake cycle. This is not a flag on one entity — it rewrites what all entities believe " +
    "to be true about existence.")
fun rewriteWorldLaw(
    @ToolParam(description = "The new law, written as a short declarative statement of fact, " +
        "e.g. 'The sun has gone out. Darkness is permanent. No one remembers warmth.'") newLaw: String,
): Map<String, Any> {
    engine.setWorldLaw(newLaw)
    engine.emitCustomEvent(x = 0f, y = 0f, radius = Float.MAX_VALUE, intensity = 1f,
        payload = mapOf("type" to "WORLD_LAW_CHANGE"))
    return mapOf("result" to "success", "law" to newLaw)
}
```

`GameEngine.setWorldLaw` is a plain `updateState { copy(worldLaw = newLaw) }` — trivial once §1's dual-engine bug is fixed and there's exactly one `GameEngine` for this to mutate.

**Negative-space law entries.** The earlier "no money in this world" discussion resolves as a *specialisation* of `worldLaw`, not a separate system: `rewriteWorldLaw("Money was never invented. No one has a word for it.")` is the same mechanism, same tool, same propagation. The distinction Serj drew — global negatives vs local positive lore — maps to:

```kotlin
data class GameState(
    ...
    val worldLaw: String = DEFAULT_WORLD_LAW,           // global, exclusionary, rewritten rarely
    val settlementLore: Map<String, String> = emptyMap(),  // local, additive, keyed by region/settlement id
)
```

`settlementLore["riverside_village"] = "Here, Three is honoured above all others. They fear the deep water."` is additive flavour a specific NPC agent's prompt includes; `worldLaw` is the one global constraint every agent's prompt always includes, unconditionally.

---

### 19.3 NPC Micro-Agents — isolated Edge AI calls, no chat history

**The core design decision (Serj, verbatim intent):** significant NPCs are not puppeted by `BehaviourDecider`'s deterministic flag logic alone — they are small, independent Edge AI (on-device LLM) calls. Each such call receives:

```
┌─────────────────────────────────────────┐
│  NPC Agent Prompt (constructed fresh,    │
│  every wake — NO conversation history)   │
├─────────────────────────────────────────┤
│  1. worldLaw          (global, current)  │
│  2. settlementLore[x] (if applicable)    │
│  3. localRole         (this NPC's brief) │
│  4. currentPerception (flags/events only,│
│     never player chat transcript)        │
└─────────────────────────────────────────┘
        ↓
   short LLM response → mapped to a
   Behaviour (§2's sealed class) → executed
   by BehaviourExecutor (§2's fix)
```

This is architecturally distinct from the Soul's own conversation (`TinyGardenViewModel.getCommand`, which *does* carry full chat history by design — the Soul remembers, the NPC does not). It's also distinct from `BehaviourDecider.decide()` (§2), which remains the **cheap fallback path** — not every NPC wake needs an LLM call; most should stay on deterministic flag logic for performance, and only NPCs flagged significant (`hasFlag("NAMED")`, `hasFlag("QUEST_GIVER")`, or similar) escalate to a micro-agent call.

**Minimum data model:**

```kotlin
data class NpcAgentProfile(
    val entityId: String,
    val localRole: String,           // "You are a blacksmith. Metal matters to you above all else."
    val usesMicroAgent: Boolean = false,  // false → falls back to BehaviourDecider (cheap path)
)

object NpcAgentRunner {
    /**
     * Constructs an isolated prompt (no history) and runs a short on-device inference.
     * Falls back to BehaviourDecider.decide() if usesMicroAgent is false, or if the
     * inference call fails/times out — the deterministic path is always the safety net.
     */
    suspend fun resolveBehaviour(
        entity: Entity,
        profile: NpcAgentProfile,
        engine: GameEngine,
        nearbyEntities: List<Entity>,
        nearbyItems: List<WorldItem>,
        event: WorldEvent?,
    ): Behaviour {
        if (!profile.usesMicroAgent) {
            return BehaviourDecider.decide(entity, nearbyEntities, nearbyItems, event)
        }
        val prompt = buildString {
            appendLine(engine.currentState().worldLaw)
            engine.currentState().settlementLore[entity.memory["settlement"] as? String ?: ""]
                ?.let { appendLine(it) }
            appendLine(profile.localRole)
            appendLine("You perceive: ${describePerception(nearbyEntities, nearbyItems, event)}")
            appendLine("Respond with one short action.")
        }
        return runCatching { /* short, historyless LlmChatModelHelper call, parse to Behaviour */ }
            .getOrElse { BehaviourDecider.decide(entity, nearbyEntities, nearbyItems, event) }
    }
}
```

**Cost note, stated plainly:** an on-device LLM call per significant NPC per wake is real inference load, not free. The `usesMicroAgent` gate exists specifically so this is opt-in per entity — village background characters stay on the deterministic `BehaviourDecider` path (§2's fix, still required regardless), and only named/quest-relevant NPCs pay the inference cost. This is a performance decision that must be respected in implementation, not an afterthought.

---

### 19.4 Chess Movement Patterns

**Confirmed piece assignment (Serj, this conversation):**

| Sister | Piece | Movement rule |
|---|---|---|
| **One** | King | One tile, any direction |
| **Six** | Queen | Any direction, unlimited distance (until blocked) |
| **Five** | Pawn | One tile forward only; captures diagonally; **promotes** at board edge or a designated zone |
| Two, Three, Four | *(Knight, Bishop, Rook — assignment pending; slots reserved, not yet fixed)* | — |

**This governs overworld exploration movement only.** Confirmed explicitly: combat has no positional grid in this design (turn-based JRPG resolution, per §7/§17.1) — chess patterns apply exclusively to how each Sister traverses the `IsoMap` between encounters.

**Required refactor — `Pathfinder.kt` generalises away from hardcoded N/S/E/W:**

```kotlin
sealed class MovementPattern {
    object King    : MovementPattern()  // One
    object Queen   : MovementPattern()  // Six
    data class Pawn(val forwardDir: Pathfinder.Step, val hasPromoted: Boolean = false) : MovementPattern()  // Five
    object Knight  : MovementPattern()  // reserved
    object Bishop  : MovementPattern()  // reserved
    object Rook    : MovementPattern()  // reserved
}

object ChessMovement {
    /** Generates all tiles reachable in exactly one move, before terrain/water filtering. */
    fun candidateMoves(from: Pathfinder.Step, pattern: MovementPattern, map: IsoMap): List<Pathfinder.Step> =
        when (pattern) {
            is MovementPattern.King  -> KING_DELTAS.map { (dc, dr) -> from.offset(dc, dr) }
            is MovementPattern.Queen -> slideInAllDirections(from, map)          // stops at first obstruction
            is MovementPattern.Pawn  -> pawnMoves(from, pattern, map)            // one step forward, diagonal capture
            is MovementPattern.Knight -> KNIGHT_DELTAS.map { (dc, dr) -> from.offset(dc, dr) }  // leaps — see §19.5
            is MovementPattern.Bishop -> slideDiagonally(from, map)
            is MovementPattern.Rook   -> slideOrthogonally(from, map)
        }
}
```

`Pathfinder.findPath` itself changes from its current fixed `dirs = listOf(N,S,W,E)` BFS neighbour list to accepting a `MovementPattern` parameter and calling `ChessMovement.candidateMoves` for its neighbour expansion at each BFS step — the search algorithm (BFS, L-shaped beautify pass) stays the same; only the neighbour-generation function becomes pluggable.

**Which pattern is active is determined by the current die roll** (§15's `DieFace.Occupant(sister)` resolution) — when a Sister becomes the active occupant via `DiceCaster`, her `MovementPattern` becomes the one `Pathfinder.findPath` uses for all subsequent `selectTile`/`executePath` calls, until the die is re-cast.

---

### 19.5 Water as a Parametrised Barrier, Not a Boolean

**Current state:** `IsoMap.isWalkable` returns `t != TileType.WATER` — water is impassable for everyone, uniformly. This is no longer sufficient: pieces cross water differently, and some water is crossable by width.

```kotlin
data class WaterBarrier(val widthInTiles: Int)  // measured perpendicular to the crossing direction

fun canCrossWater(pattern: MovementPattern, barrier: WaterBarrier): Boolean = when (pattern) {
    is MovementPattern.Knight -> barrier.widthInTiles <= 1   // a leap clears exactly one tile of water
    is MovementPattern.Queen  -> barrier.widthInTiles <= 1   // formidable, but not exempt from geography
    is MovementPattern.Bishop,
    is MovementPattern.Rook,
    is MovementPattern.King   -> false                        // slide/step pieces never cross water
    is MovementPattern.Pawn   -> false                        // never — this is the point (see below)
}
```

**Five's promotion mechanic** is the deliberate exception to her own rule, not a special case bolted onto water-crossing logic. In real chess, a pawn reaching the far rank promotes — here, reaching a designated **promotion zone** (the world's edge, or a zone the Soul or level design marks via `worldLaw`/`settlementLore`, e.g. "the far shore is a place of transformation") changes her active `MovementPattern` from `Pawn` to another pattern (thematically, `Queen` — mirroring standard chess promotion, and echoing the framing of the pawn as *potentially the strongest piece — that's her lore*).

```kotlin
object PromotionZones {
    /** Returns true if (col,row) is a promotion trigger for Five specifically. */
    fun isPromotionZone(col: Int, row: Int, map: IsoMap): Boolean =
        col <= map.centerCol - map.cols / 2 + 1 ||   // world edge, west
        col >= map.centerCol + map.cols / 2 - 1 ||   // world edge, east
        row <= map.centerRow - map.rows / 2 + 1 ||   // world edge, north
        row >= map.centerRow + map.rows / 2 - 1      // world edge, south
        // additional designated zones (mid-map) are a worldLaw-driven extension point,
        // not hardcoded here — the Soul can narratively mark a location as a promotion
        // zone the same way it rewrites worldLaw, without an engine change.
}
```

Once promoted, this is a **standing change to `Sister.currentPattern`** for the remainder of the session (or until a narrative event reverses it) — not a one-tile toggle. This is deliberately consistent with real chess: promotion is permanent.

---

### 19.6 Battle Button Grid — availability driven by active Sister

**Confirmed UI (this conversation):**

```
┌─────────────────────────┐
│      [🎲 Die]            │  ← always visible; swipe gesture casts it
└─────────────────────────┘
┌────────┬────────┬───────┐
│  MOVE  │  WAIT  │ SCOUT │
├────────┼────────┼───────┤
│ COMBAT │ SPEAK  │ MAGIC │
└────────┴────────┴───────┘
```

All six buttons render **grey/disabled** until a die cast resolves an active Sister. Availability then follows her `SisterAbilityProfile` (§8's data model, extended here with the specifics this conversation confirmed):

```kotlin
data class SisterAbilityProfile(
    val canMove: Boolean = true,
    val canScout: Boolean = true,
    val canFight: Boolean,
    val canCastMagic: Boolean,
    val canBeStunned: Boolean = true,
    val magicButtonLabelOverride: String? = null,  // e.g. Four's MAGIC button reads DEFEND instead
)

val SISTER_PROFILES: Map<String, SisterAbilityProfile> = mapOf(
    "one"   to SisterAbilityProfile(canFight = true,  canCastMagic = false),
    "two"   to SisterAbilityProfile(canFight = true,  canCastMagic = false),
    "three" to SisterAbilityProfile(canFight = false, canCastMagic = true),
    "four"  to SisterAbilityProfile(canFight = false, canCastMagic = true,
                                     magicButtonLabelOverride = "DEFEND"),
    "five"  to SisterAbilityProfile(canFight = false, canCastMagic = false),  // no attacks of any kind
    "six"   to SisterAbilityProfile(canFight = true,  canCastMagic = true),   // both, no restriction
)
```

Confirmed constraints from this conversation, stated exactly as given: **three Sisters cannot enter COMBAT at all**; **one Sister (Five) has no attacks whatsoever** — her MAGIC-equivalent button is pure defence; **two Sisters cannot cast MAGIC**; **any Sister can be stunned** (a status effect, per §17.10, that this button grid must also gate — a stunned active Sister greys the whole bottom row regardless of her profile, which is a UI-layer check on top of the profile table, not a replacement for it).

**Wiring note:** this button grid is UI state derived from `GameEngine`'s currently-resolved `Sister` (via `DiceCaster`, §15), not new engine logic — `SisterAbilityProfile` lookup plus a stun-flag check is sufficient; no new `GameEngine` methods are required beyond what §15 already specifies (`engine.setActiveSister`).

---

### 19.7 Dependency note for the fix-order list (§18)

This addendum's systems have their own internal dependency order, layered onto §18's existing sequence:

- **World Law (19.2)** depends only on §1 (single engine) — can be built immediately after the top of §18's list, in parallel with §9/§3.
- **NPC Micro-Agents (19.3)** depend on World Law (19.2) existing (agents need `worldLaw` to read) *and* on §2's `BehaviourExecutor` (the fallback path for `usesMicroAgent = false` must already work, or the escalation path has nothing to fall back to).
- **Chess Movement (19.4–19.5)** depends on §15 (Sisters/Dice data model) for `DieFace.Occupant` resolution to exist, but is otherwise independent of World Law/NPC Agents — these two halves of this addendum can be built in parallel by different effort if the team ever splits.
- **Battle Button Grid (19.6)** is pure UI wiring once §15 and §19.4 both exist — last in this addendum's internal order, first thing a player will actually see change.
