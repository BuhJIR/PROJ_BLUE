# PROJ☆BLUE

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://github.com/BuhJIR/PROJ_BLUE/actions/workflows/build_android.yaml/badge.svg)](https://github.com/BuhJIR/PROJ_BLUE/actions)

**Edge AI · Isometric JRPG · On-device LLM Game Master**

PROJ☆BLUE — изометрическая RPG на Android, где Мастером игры является локальная языковая модель Gemma, запущенная прямо на устройстве. Никаких серверов. Никаких облаков. Мир живёт внутри твоего телефона.

---

## Концепция

Пошаговая тактическая RPG с живым нарративом. Каждый NPC существует автономно — управляется флагами и нуждами, а не скриптами. Мир реагирует на события волнами — взрыв будит всех в радиусе, угроза поднимает охрану, праздник меняет поведение целых групп.

Языковая модель — не чат-бот. Она описывает мир, создаёт события, управляет существами через систему инструментов. Игрок и модель взаимодействуют через общее игровое пространство.

---

## Архитектура

```
┌─────────────────────────────────────────┐
│           PROJ☆BLUE                     │
├─────────────────────────────────────────┤
│  Gemma (on-device LLM)                  │
│  └── Game Master "Душа"                 │
│       ├── Нарратив (русский)            │
│       ├── Tool Calling → GameEngine     │
│       └── Pure JSON commands            │
├─────────────────────────────────────────┤
│  GameEngine                             │
│  ├── SpatialHash — O(1) queries         │
│  ├── EventBus — stimulus/response       │
│  ├── BehaviourDecider — flag automaton  │
│  ├── Pathfinder — 4-way BFS + L-shape   │
│  └── executePath — animated movement    │
├─────────────────────────────────────────┤
│  IsoRenderer (Compose Canvas)           │
│  ├── Procedural world (single seed)     │
│  ├── Layered tiles with height 0-4      │
│  ├── 4-directional sprites              │
│  ├── Path highlight                     │
│  └── Segmented HP bars                  │
└─────────────────────────────────────────┘
```

---

## Шесть Сестёр

Кубик d6 — не рандом. Онтология мира.  
Каждая грань — принцип реальности, воплощённый в персонажа.

| # | Имя | Принцип |
|---|-----|---------|
| ⚀ | **One** | Эго, расчёт |
| ⚁ | **Two** | Кооперация, теория игр |
| ⚂ | **Three** | Гармония, природа |
| ⚃ | **Four** | Жертвенность, хаос |
| ⚄ | **Five** | Созидание, смерть |
| ⚅ | **Six** | Выбор, дуальность, время |

Грань кубика определяет кто говорит в диалоге, кто атакует в бою, что происходит при броске в мир.

---

## Мир живёт сам

```kotlin
// Группа NPC меняет поведение одной командой
engine.bulkFlag(
    matchFlags = listOf("GOBLIN", "PEASANT"),
    removeGroup = "WORK",
    addFlags = listOf("CELEBRATE")
)
// EventBus рассылает волну → все в радиусе реагируют
// FORAGER ищет еду, SOCIAL ищет компанию
```

---

## Техническая база

- **Основа:** [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
- **Package:** `com.google.ai.edge.gallery` — системные привилегии, Vulkan GPU scheduling
- **LLM:** Gemma (on-device, полностью офлайн)
- **UI:** Jetpack Compose + Canvas
- **Inference:** LiteRT / MediaPipe Tasks
- **DI:** Hilt
- **Build:** GitHub Actions → APK artifact

---

## Сборка

```bash
git clone https://github.com/BuhJIR/PROJ_BLUE.git
cd PROJ_BLUE/Android/src
./gradlew assembleDebug
```

Или скачать из [**Actions → последний успешный билд**](https://github.com/BuhJIR/PROJ_BLUE/actions).

---

## Статус

```
✓ Изометрический рендерер с высотами
✓ Процедурный мир (единый seed, бесконечный)
✓ 6 персонажей × 4 направления (пиксельарт спрайты)
✓ On-device LLM Game Master (русский нарратив)
✓ Event-driven NPC поведение (флаги, нужды, память)
✓ BFS pathfinding + L-образный путь + анимация
◐ Боевая система
◐ Механика кубиков
○ Главное меню
○ Звук
```

---

*"Запах сырой земли и гниющего мяса висит в воздухе.  
Слышен глухой скрежет, будто что-то тяжёлое волокут по камню."*

