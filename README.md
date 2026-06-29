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

#### Engine & World
```
✓ Isometric renderer with per-tile height (0–4)
✓ Procedural world generation (single deterministic seed)
✓ Infinite world buffer — regenerates around the player
✓ 4-directional pixel-art sprite system
✓ Sprite sheet validation (integer frame width, zero drift)
✓ Event-driven NPC behaviour (flags, needs, memory, groups)
✓ Spatial hash — O(1) proximity queries at any scale
✓ BFS pathfinding with L-shaped movement beautification
✓ Animated step-by-step path execution
✓ On-device LLM Game Master with tool calling
```

#### In Progress
```
◐ Combat system — turn order, action resolution
◐ Dice mechanics — d6 face assignment, world casting
◐ Enemy AI — threat response, patrol, engagement radius
◐ Main menu (PS1 aesthetic)
```

#### Planned
```
○ Enemy catalogue — unique flags, behaviours, loot tables
○ Fauna — passive creatures, aggro radius, flee logic
○ Flora — harvestable, seasonal, reactive to world events
○ Structures — buildings, interiors, destructible elements
○ Caves & dungeons — procedural underground layers
○ Inventory & crafting system
○ Companion system — Sisters as active party members
○ Dialogue tree — branching, memory-aware, Soul-driven
○ Status effects — poison, fatigue, fear, blessing
○ Day/night cycle with behaviour shifts
○ Weather system affecting tile traversal and NPC mood
○ Save & load — world state serialisation
○ Audio — ambient, combat, narrative cues
```

---

*"The smell of damp earth and rotting flesh hangs in the air.  
A low grinding sound — as though something heavy is being dragged across stone."*

