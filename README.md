# PROJ☆BLUE

[![Licence](https://img.shields.io/badge/Licence-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://github.com/BuhJIR/PROJ_BLUE/actions/workflows/build_android.yaml/badge.svg)](https://github.com/BuhJIR/PROJ_BLUE/actions)

**Edge AI · Isometric RPG · On-device LLM Game Master**

PROJ☆BLUE is an isometric RPG for Android in which the Game Master is a local Gemma language model running entirely on-device. No servers. No cloud. The world lives inside your phone.

---

## Concept

A turn-based tactical RPG with a living narrative. Every NPC exists autonomously — driven by flags and needs, not scripts. The world responds to events in waves: an explosion wakes everyone within range, a threat raises the guards, a festival shifts the behaviour of entire groups.

The language model is not a chatbot. It describes the world, creates events, and commands entities through a tool-calling interface. The player and the model interact through a shared game space.

---

## Architecture

```
┌─────────────────────────────────────────┐
│              PROJ☆BLUE                  │
├─────────────────────────────────────────┤
│  Gemma (on-device LLM)                  │
│  └── Game Master "the Soul"             │
│       ├── Narrative                     │
│       ├── Tool Calling → GameEngine     │
│       └── Pure JSON commands            │
├─────────────────────────────────────────┤
│  GameEngine                             │
│  ├── SpatialHash   — O(1) queries       │
│  ├── EventBus      — stimulus/response  │
│  ├── BehaviourDecider — flag automaton  │
│  ├── Pathfinder    — 4-way BFS + L-path │
│  └── executePath   — animated movement  │
├─────────────────────────────────────────┤
│  IsoRenderer (Compose Canvas)           │
│  ├── Procedural world (single seed)     │
│  ├── Layered tiles with height 0–4      │
│  ├── 4-directional pixel-art sprites    │
│  ├── Path highlight                     │
│  └── Segmented HP bars                  │
└─────────────────────────────────────────┘
```

---

## The Six Sisters

The d6 die is not a randomiser. It is the ontology of the world.  
Each face is a principle of reality made manifest.

| # | Name | Principle |
|---|------|-----------|
| ⚀ | **One** | Ego, calculation |
| ⚁ | **Two** | Co-operation, game theory |
| ⚂ | **Three** | Harmony, nature |
| ⚃ | **Four** | Sacrifice, chaos |
| ⚄ | **Five** | Creation, death |
| ⚅ | **Six** | Choice, duality, spacetime |

The face that lands determines who speaks in dialogue, who strikes in combat, and what stirs in the world when the die is cast upon it.

---

## The World Lives on Its Own

```kotlin
// An entire group changes behaviour in one call
engine.bulkFlag(
    matchFlags = listOf("GOBLIN", "PEASANT"),
    removeGroup = "WORK",
    addFlags    = listOf("CELEBRATE")
)
// EventBus propagates a wave → all entities within range react
// FORAGER seeks food, SOCIAL seeks company
```

---

## Technical Foundation

| | |
|---|---|
| **Base** | [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) |
| **Package** | `com.google.ai.edge.gallery` — system privileges, Vulkan GPU scheduling |
| **LLM** | Gemma — fully offline, on-device |
| **UI** | Jetpack Compose + Canvas (isometric renderer) |
| **Inference** | LiteRT / MediaPipe Tasks |
| **DI** | Hilt |
| **CI** | GitHub Actions → APK artefact |

---

## Building

```bash
git clone https://github.com/BuhJIR/PROJ_BLUE.git
cd PROJ_BLUE/Android/src
./gradlew assembleDebug
```

Or download the latest artefact from [**Actions**](https://github.com/BuhJIR/PROJ_BLUE/actions).

---

## Development Status

#### ✓ Complete

The isometric renderer draws a procedurally generated world from a single deterministic seed — the same coordinates always produce the same terrain. Tiles carry individual height values, casting proper side faces and lifting sprites above the ground plane. The world buffer silently regenerates around the player as they move, with no visible seam.

Six playable characters are implemented, each with four directional sprite sheets hand-validated for zero frame drift. The on-device language model acts as Game Master — it narrates in full sentences, spawns entities, modifies world state, and responds to player actions through a structured tool-calling interface, all without a network connection.

NPC behaviour runs on a flag automaton: entities wake in response to world events, consult their needs and memory, and choose actions deterministically. A spatial hash ensures proximity queries remain O(1) regardless of world population. Pathfinding uses 4-way BFS with an L-shaped beautification pass, and the engine walks the resulting path frame by frame with directional sprite switching.

---

#### ◐ In Progress

**Combat.** The turn structure and action resolution are being designed around the dice system — each roll is not merely a number but a question of which Sister acts and on whose behalf.

**The Dice.** A d6 face carries a Sister, a spell, or is left blank — the player assembles the die before entering a situation. Casting it into the world tile-by-tile is a mechanic still being built.

**Main Menu.** A PS1-era title screen is partially implemented, awaiting integration as the application entry point.

---

#### ○ Planned

**Enemies.** Each enemy type will carry a distinct flag set that governs not just combat but daily routine — a skeleton patrol that genuinely has somewhere to be, and stops patrolling only when it finds you.

**Fauna.** Passive creatures with aggro radii and flee responses, woven into the event system. A rabbit bolting from a loud footstep is the same mechanism as a guard responding to a threat.

**Flora.** Plants that can be harvested, that grow back over time, and that Three in particular notices before anyone else does.

**Structures.** Buildings with traversable interiors, destructible elements, and ownership flags that determine how NPCs respond to your presence inside them.

**Caves & Dungeons.** Procedurally generated underground layers with their own seed offset — darker tile palette, restricted light radius, and encounters that scale with depth.

**Companions.** The Sisters are not decorative. Each one will join the party as an active member, contribute her principle to every roll, and disagree with you when the situation calls for it.

**Dialogue Trees.** Memory-aware branching conversations driven by the Soul — what you did three sessions ago may surface in what an NPC says today.

**Status Effects.** Poison, fatigue, fear, and blessing as first-class flag states, visible on the segmented HP bar and legible to the language model.

**Day / Night Cycle.** Behaviour shifts at dusk — merchants close, predators emerge, certain flags activate only in darkness.

**Weather.** Rain slows movement on dirt tiles. Fog narrows the interest radius of every entity on the map. Snow changes the tile palette and freezes shallow water.

**Save System.** Full world-state serialisation including entity memory, flag state, and the Soul's conversation history.

**Audio.** Ambient layers, positional combat sounds, and narrative cues triggered by the Soul mid-sentence.

---

*"The smell of damp earth and rotting flesh hangs in the air.  
A low grinding sound — as though something heavy is being dragged across stone."*

