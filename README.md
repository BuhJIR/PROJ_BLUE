# PROJ☆BLUE

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Build](https://github.com/BuhJIR/PROJ_BLUE/actions/workflows/build_android.yaml/badge.svg)](https://github.com/BuhJIR/PROJ_BLUE/actions)

**Edge AI · Final Fantasy × Deus Ex · On-device LLM Game Master**

PROJ☆BLUE — это изометрическая JRPG на Android, где живым Мастером игры является локальная языковая модель Gemma, запущенная прямо на устройстве. Никаких серверов. Никаких облаков. Мир живёт внутри твоего телефона.

---

## Философия

> *Final Fantasy дала нам глубину мира.  
> Deus Ex дал нам свободу выбора.  
> Edge AI даёт нам живого Мастера.*

Игра строится на двух принципах:

**FF-боёвка** — пошаговая, тактическая, с весом каждого решения.  
**Deus Ex-философия** — мир реагирует на игрока. Каждый NPC живёт по своим флагам и нуждам, а не по скрипту.

---

## Архитектура

```
┌─────────────────────────────────────────┐
│           PROJ☆BLUE                     │
├─────────────────────────────────────────┤
│  Gemma (on-device LLM)                  │
│  └── Game Master "Soul"                 │
│       ├── Narrative (русский)           │
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
│  ├── Procedural world (WORLD_SEED)      │
│  ├── Layered tiles with height 0-4      │
│  ├── 4-directional sprites              │
│  ├── Path highlight (голубой)           │
│  └── Segmented HP bars                  │
└─────────────────────────────────────────┘
```

---

## Шесть Сестёр

Кубик d6 — это не рандом. Это **онтология мира**.  
Каждая грань — принцип реальности воплощённый в существо.

| # | Сестра | Принцип | Цвет |
|---|--------|---------|------|
| ⚀ | **One** | Эго, расчёт | Белый плащ |
| ⚁ | **Two** | Кооперация, теория игр | Ледяной плащ |
| ⚂ | **Three** | Гармония, природа, цветы | Жёлтый плащ |
| ⚃ | **Four** | Жертвенность, удача, хаос | Синий плащ |
| ⚄ | **Five** | Спокойствие, созидание, смерть | Чёрный плащ |
| ⚅ | **Six** | Выбор, дуальность, пространство-время | Фиолетовый |

Кубик определяет кто говорит в диалоге, кто атакует в бою,  
что вырастает под ногами при броске в мир.

---

## Мир живёт сам

NPC управляются флагами, а не скриптами:

```kotlin
// Все гоблины-крестьяне сегодня празднуют
engine.bulkFlag(
    matchFlags = listOf("GOBLIN", "PEASANT"),
    removeGroup = "WORK",
    addFlags = listOf("CELEBRATE")
)
// → EventBus.emit(CROWD_CHEER)
// → все кто в радиусе слышат и реагируют
// → кто с флагом FORAGER ищет алкоголь
// → кто с флагом SOCIAL ищет компанию
```

Взрыв будит всех в радиусе через `SpatialHash.query()`.  
Орк вбежал в город — `GUARD` entity просыпаются без тика, без решения модели.

---

## Техническая база

- **Основа:** [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)
- **Package:** `com.google.ai.edge.gallery` — системные привилегии, Vulkan GPU scheduling
- **LLM:** Gemma (on-device, полностью офлайн)
- **UI:** Jetpack Compose + Canvas (изометрический рендерер)
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

APK: `app/build/outputs/apk/debug/app-debug.apk`

Или скачать из [**Actions → последний успешный билд**](https://github.com/BuhJIR/PROJ_BLUE/actions).

---

## Статус

Проект в активной разработке. Ежедневные коммиты.

```
✓ Изометрический рендерер с высотами
✓ Процедурный мир (единый seed, бесконечный)  
✓ 6 сестёр × 4 направления (спрайты)
✓ On-device LLM Game Master (русский нарратив)
✓ Event-driven NPC поведение
✓ BFS pathfinding + L-образный путь
✓ Анимированное перемещение по пути
◐ Боевая система
◐ Кубики d6
○ PS1-меню
○ Музыка
```

---

*"Запах сырой земли и гниющего мяса висит в воздухе.  
Слышен глухой скрежет, будто что-то тяжёлое волокут по камню."*  
— Душа

