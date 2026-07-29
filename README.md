# **🔴 RedstoneDetector** — Automatic Lag-Machine Defense for Minecraft ⚡🛡️

Find and stop redstone lag machines **before** your players start complaining about TPS. **RedstoneDetector** measures the real cost of every chunk, names the machine it found, and can **automatically suspend** the guilty mechanisms — while the rest of the server keeps running normally.

No more flying around the map with `/tp` and guessing which farm is killing your tick. The plugin tells you the world, the chunk, the block types, the updates per second and the estimated MSPT — and freezes it for you.

> 💡 **Key idea of 1.2.0**
>

> Freezing is no longer "cancel the event and hope for the best". Frozen mechanisms are **actually suspended**, so the CPU cost really drops instead of staying the same.
>

[![Discord](https://img.shields.io/badge/Discord-Join-blue?logo=discord&logoColor=white)](https://discord.gg/PXDzCQZUch)
[![YouTube](https://img.shields.io/badge/YouTube-Subscribe-red?logo=youtube&logoColor=white)](https://www.youtube.com/@Stepanyaa)
[![GitHub](https://img.shields.io/badge/GitHub-Repo-yellow?logo=github&logoColor=white)](https://github.com/Stepanyaa/RedstoneDetector)
[![Modrinth](https://img.shields.io/badge/Modrinth-Download-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/project/redstonedetector)
[![FastStats](https://img.shields.io/badge/FastStats-Metrics-orange)](https://faststats.dev/project/redstonedetector)

*Scroll down for the Russian version / Прокрутите вниз для русской версии* 🇷🇺

---

## 🚀 **Why RedstoneDetector?**

- 🔍 **Real cost detection** — chunks are ranked by measured impact (MSPT contribution), not just by block counts
- 🧊 **True auto-freeze** — suspends redstone, pistons, hoppers, observers, comparators, sculk sensors and trapdoors instead of merely cancelling events
- 🤖 **Automatic stop of lag machines** — a chunk that keeps generating extreme activity is frozen on its own, with automatic release afterwards
- 🐛 **Dedicated Sculk sensor detector** — vibration loops, excessive activations, synchronized sensor groups and repeat bursts
- 🚪 **Dedicated Trapdoor detector** — fast open/close loops, clusters and redstone-driven spam
- 🌐 **Global freeze** — one click stops all redstone on the server, one click brings it back
- 🧠 **Smart freeze** — freezes only the guilty chunks, so normal player farms keep working
- 📊 **Live GUI dashboard** — refreshes itself every second, no manual refresh button, no menu flicker
- 🔎 **Chunk search from chat** — type chunk or block coordinates and jump straight to the report
- 🧾 **Freeze journal** — start time, end time, suspended blocks and estimated improvement in the log
- 🌍 **13 built-in languages** — with per-player client language detection
- 🧩 **Two platforms** — Bukkit/Spigot/Paper/Purpur/Folia **and** a native Sponge build

---

## 🧊 **Freeze system**

### 🤖 Automatic stop

- A chunk is frozen automatically when the server is genuinely lagging **and** the chunk stays expensive for several seconds in a row
- Extremely active chunks are frozen regardless of TPS (`auto-freeze-updates-per-second`, default `800`)
- Sculk and trapdoor lag machines are frozen by their own detectors, either the single chunk or a configurable radius around it
- Frozen chunks are released automatically after `auto-unfreeze-seconds` (default `60`) so they can be re-evaluated
- Suspension and restoration are budgeted per tick, so freezing a huge farm never spikes the tick itself
- Sculk sensors, calibrated sensors and shriekers are returned to a clean default state on release, so they keep working normally afterwards

### 🌐 Global freeze

- Stops redstone activity across every world at once
- Keeps working while the server is under load — detection, ranking and enforcement continue running
- Fully reversible: **Resume all** restores every suspended mechanism

### ✋ Manual control

- Freeze / unfreeze a single chunk from the GUI or from chat
- Remove redstone blocks with a restore option
- Remove non-player entities in a chunk
- Forget a chunk so it stops being tracked

---

## 📊 **Live dashboard & GUI**

- **Server status** — TPS, MSPT, suspicious chunks, frozen chunks, global freeze state, last scan
- **Activity per second** — redstone, pistons, hoppers, observers, comparators, sculk sensors, trapdoors, scheduled updates, entities, block entities, lag score
- **Active detections** — active freezes, sculk watch, trapdoor watch, suspended chunks
- **Chunk list** — sortable by **server cost**, **updates per second**, **mechanisms** or **entities**, with working pagination
- **Chunk screen** — world, coordinates, component counts, activity, impact, detector name, machine type, suspended blocks, chunk MSPT vs server MSPT
- **Quick actions** — teleport, freeze/unfreeze, stop/resume, remove redstone, remove entities, forget
- **Auto refresh** — the open screen updates every second without recreating the inventory, so your cursor never jumps

Recognized machine types: flying machine, observer clock, piston clock, redstone clock, rapid-update machine, infinite update loop, **sculk lag machine**, **trapdoor lag machine**, **item transport machine**.

---

## ⚡ **Platform & version support**

| Platform | Status |
| --- | --- |
| Bukkit / Spigot | ✅ Supported |
| Paper / Purpur | ✅ Supported |
| Mohist / Arclight (hybrid) | ✅ Supported |
| Folia | ✅ Supported (region-aware scheduling) |
| Sponge / SpongeVanilla | ✅ Separate native build |

**Minecraft versions:** `1.16.1 – latest`

**Java:** 11+ for the Bukkit build, 21+ for the Sponge build

**Dependencies:** none required

---

## 📋 **Commands**

Main command: `/redstonedetector`, aliases `/rd`, `/reddetect`.

| Command | Description | Permission |
| --- | --- | --- |
| `/rd gui` | Open the live dashboard | `redstonedetector.gui` |
| `/rd status` | Server status: TPS, suspicious and frozen chunks | `redstonedetector.gui` |
| `/rd info [world x z]` | Full report for a chunk (yours by default) | `redstonedetector.gui` |
| `/rd list` (`/rd top`) | The heaviest chunks right now | `redstonedetector.gui` |
| `/rd search <x> <z>` | Find a chunk by chunk or block coordinates | `redstonedetector.gui` |
| `/rd scan` | Start a full scan | `redstonedetector.scan` |
| `/rd scan cancel` | Cancel the running scan | `redstonedetector.scan` |
| `/rd freeze [world x z]` | Freeze redstone globally or in one chunk | `redstonedetector.freeze` |
| `/rd unfreeze [world x z]` | Release the freeze | `redstonedetector.freeze` |
| `/rd stop [world x z]` | Suspend mechanisms without breaking blocks | `redstonedetector.freeze` |
| `/rd resume [world x z]` | Resume suspended mechanisms | `redstonedetector.freeze` |
| `/rd entities <world> <x> <z>` | Remove non-player entities in a chunk | `redstonedetector.admin` |
| `/rd tp <world> <x> <z>` | Teleport to a chunk | `redstonedetector.gui` |
| `/rd forget <world> <x> <z>` | Stop tracking a chunk | `redstonedetector.admin` |
| `/rd lang [code\list]` | Show, list or change the language |
| `/rd version` | Plugin version and update state | `redstonedetector.gui` |
| `/rd reload` | Reload all configuration files | `redstonedetector.reload` |
| `/rdcancel` | Cancel chat input (search / pagination) | — |

---

## 🔐 **Permissions**

| Permission | Description | Default |
| --- | --- | --- |
| `redstonedetector.admin` | Full access to every feature | OP |
| `redstonedetector.command` | Base access to the commands | OP |
| `redstonedetector.gui` | Dashboard, status, info, list, teleport | OP |
| `redstonedetector.scan` | Start and cancel chunk scans | OP |
| `redstonedetector.freeze` | Freeze, unfreeze, stop and resume redstone | OP |
| `redstonedetector.reload` | Reload the configuration | OP |

---

## ⚙️ **Configuration**

Split into readable files: `config.yml`, `performance.yml`, `blocks.yml`, `gui.yml` and the `lang/` folder.

**Detection** — critical TPS (`15.0`), max redstone per chunk (`100`), max entities (`100`), freeze duration (`300 s`), chunk data retention (`24 h`)

**Performance** — chunks per tick (`3`), scan interval, scan on low TPS, loaded chunks only, statistics cache, scan cooldown

**Smart freeze** — intelligent freeze toggle, update score threshold, auto-freeze at `800` updates/s, low-TPS freeze at `300` updates/s, auto-unfreeze after `60 s`, server MSPT threshold (`45.0`)

**Detectors** — sensitivity, sculk activations/group size/loop window/repeat burst, trapdoor toggles/cluster size/loop window/repeat burst, freeze radius, max freeze seconds

**Filters** — ignored worlds, ignored chunks, ignored blocks, debug logging

---

## 🌍 **Available languages (13)**

- 🇬🇧 English (`en_us`)
- 🇷🇺 Russian (`ru_ru`)
- 🇺🇦 Ukrainian (`uk_ua`)
- 🇩🇪 German (`de_de`)
- 🇫🇷 French (`fr_fr`)
- 🇪🇸 Spanish (`es_es`)
- 🇮🇹 Italian (`it_it`)
- 🇳🇱 Dutch (`nl_nl`)
- 🇵🇱 Polish (`pl_pl`)
- 🇵🇹 Portuguese, Brazil (`pt_br`)
- 🇹🇷 Turkish (`tr_tr`)
- 🇯🇵 Japanese (`ja_jp`)
- 🇨🇳 Chinese, Simplified (`zh_cn`)

All locale files share **exactly the same key structure**, so translating a new language is a copy of `en_us.yml` away. Drop `lang/<locale>.yml` into the folder and it is picked up automatically; missing keys fall back to English. With `per-player-language: true` every player sees the plugin in their own client language.

---

## 💡 **Development & support**

- Found a bug or have an idea? → [Discord](https://discord.gg/PXDzCQZUch)
- Want to help translate? → the `#translation` channel on Discord
- Want to contribute? → [GitHub](https://github.com/Stepanyaa/RedstoneDetector)

**License:** MIT

> #### ⚠️ **A note from the developer**
>

> This plugin started as a personal solution for my private servers and is now free for everyone. It is in **active development**, and your feedback shapes it — please report issues and share ideas on Discord!
>

---

- <strong>Русская версия (Russian version) ▼</strong>

  # **🔴 RedstoneDetector** — автоматическая защита от лаг-машин ⚡🛡️

  Находите и останавливайте редстоун лаг-машины **до того**, как игроки начнут жаловаться на TPS. **RedstoneDetector** измеряет реальную стоимость каждого чанка, называет найденную машину и может **автоматически останавливать** виновные механизмы — при этом остальной сервер продолжает работать как обычно.

  Больше не нужно летать по карте и угадывать, какая ферма съедает тик. Плагин показывает мир, чанк, типы блоков, обновления в секунду и оценку MSPT — и замораживает проблему за вас.

  > 💡 **Главное в 1.2.0**
  >

  > Заморозка больше не «отменить событие и надеяться». Замороженные механизмы **действительно приостанавливаются**, поэтому нагрузка на процессор реально падает, а не остаётся прежней.
  >
    
  ---

  ## 🚀 **Почему RedstoneDetector?**

    - 🔍 **Определение реальной нагрузки** — чанки ранжируются по измеренному влиянию на MSPT, а не по количеству блоков
    - 🧊 **Настоящая авто-заморозка** — приостанавливает редстоун, поршни, воронки, наблюдатели, компараторы, скалк-сенсоры и люки, а не просто отменяет события
    - 🤖 **Автоматическая остановка лаг-машин** — чанк с экстремальной активностью замораживается автоматически, с последующим автоматическим снятием
    - 🐛 **Отдельный детектор скалк-сенсоров** — петли вибраций, избыточные срабатывания, синхронные группы сенсоров, серии повторов
    - 🚪 **Отдельный детектор люков** — быстрые циклы открытия/закрытия, кластеры, спам от редстоуна
    - 🌐 **Глобальная заморозка** — один клик останавливает весь редстоун на сервере, один клик возвращает всё обратно
    - 🧠 **Умная заморозка** — замораживает только виновные чанки, обычные фермы игроков продолжают работать
    - 📊 **Живая GUI-панель** — обновляется каждую секунду, без кнопки обновления и без мерцания меню
    - 🔎 **Поиск чанка через чат** — введите координаты чанка или блока и сразу получите отчёт
    - 🧾 **Журнал заморозок** — время начала и конца, число приостановленных блоков и оценка выигрыша в логе
    - 🌍 **13 встроенных языков** — с определением языка клиента для каждого игрока
    - 🧩 **Две платформы** — Bukkit/Spigot/Paper/Purpur/Folia **и** отдельная сборка для Sponge

    ---

  ## 🧊 **Система заморозки**

  ### 🤖 Автоматическая остановка

    - Чанк замораживается автоматически, когда сервер действительно лагает **и** чанк остаётся дорогим несколько секунд подряд
    - Чрезмерно активные чанки замораживаются независимо от TPS (`auto-freeze-updates-per-second`, по умолчанию `800`)
    - Скалк- и лаг-машины на люках замораживаются своими детекторами: сам чанк или настраиваемый радиус вокруг него
    - Заморозка снимается автоматически через `auto-unfreeze-seconds` (по умолчанию `60`) для повторной оценки
    - Приостановка и восстановление ограничены бюджетом на тик, поэтому заморозка крупной фермы не вызывает просадок тика
    - Скалк-сенсоры, откалиброванные сенсоры и шрайкеры возвращаются в корректное состояние при снятии заморозки и продолжают работать нормально

  ### 🌐 Глобальная заморозка

    - Останавливает активность редстоуна во всех мирах сразу
    - Продолжает работать под нагрузкой: обнаружение, ранжирование и контроль не отключаются
    - Полностью обратима: **Возобновить всё** восстанавливает каждый приостановленный механизм

  ### ✋ Ручное управление

    - Заморозка и разморозка отдельного чанка из GUI или чата
    - Удаление редстоун-блоков с возможностью восстановления
    - Удаление сущностей (кроме игроков) в чанке
    - Забыть чанк, чтобы он больше не отслеживался

    ---

  ## 📊 **Живая панель и GUI**

    - **Состояние сервера** — TPS, MSPT, подозрительные чанки, замороженные чанки, глобальная заморозка, последнее сканирование
    - **Активность в секунду** — редстоун, поршни, воронки, наблюдатели, компараторы, скалк-сенсоры, люки, запланированные обновления, сущности, блок-сущности, lag score
    - **Активные обнаружения** — активные заморозки, наблюдение за скалком и люками, приостановленные чанки
    - **Список чанков** — сортировка по **нагрузке на сервер**, **обновлениям в секунду**, **механизмам** или **сущностям**, с корректной пагинацией
    - **Экран чанка** — мир, координаты, количество компонентов, активность, влияние, имя детектора, тип машины, приостановленные блоки, MSPT чанка против MSPT сервера
    - **Быстрые действия** — телепорт, заморозка/разморозка, стоп/возобновление, удаление редстоуна, удаление сущностей, забыть чанк
    - **Авто-обновление** — открытый экран обновляется раз в секунду без пересоздания инвентаря, курсор не сбрасывается

  Распознаваемые типы машин: летающая машина, часы на наблюдателях, поршневые часы, редстоун-часы, машина быстрых обновлений, бесконечный цикл обновлений, **скалк лаг-машина**, **люковая лаг-машина**, **машина транспортировки предметов**.
    
  ---

  ## ⚡ **Платформы и версии**

  | Платформа | Статус |
      | --- | --- |
  | Bukkit / Spigot | ✅ Поддерживается |
  | Paper / Purpur | ✅ Поддерживается |
  | Mohist / Arclight (гибриды) | ✅ Поддерживается |
  | Folia | ✅ Поддерживается (региональные планировщики) |
  | Sponge / SpongeVanilla | ✅ Отдельная нативная сборка |

  **Версии Minecraft:** `1.16.1 – актуальная`

  **Java:** 11+ для Bukkit-сборки, 21+ для Sponge-сборки

  **Зависимости:** не требуются
    
  ---

  ## 📋 **Команды**

  Основная команда: `/redstonedetector`, алиасы `/rd`, `/reddetect`.

  | Команда | Описание | Право |
      | --- | --- | --- |
  | `/rd gui` | Открыть живую панель | `redstonedetector.gui` |
  | `/rd status` | Состояние сервера: TPS, подозрительные и замороженные чанки | `redstonedetector.gui` |
  | `/rd info [world x z]` | Полный отчёт по чанку (по умолчанию — вашему) | `redstonedetector.gui` |
  | `/rd list` (`/rd top`) | Самые тяжёлые чанки прямо сейчас | `redstonedetector.gui` |
  | `/rd search <x> <z>` | Поиск чанка по координатам чанка или блока | `redstonedetector.gui` |
  | `/rd scan` | Запустить полное сканирование | `redstonedetector.scan` |
  | `/rd scan cancel` | Отменить сканирование | `redstonedetector.scan` |
  | `/rd freeze [world x z]` | Заморозить редстоун глобально или в одном чанке | `redstonedetector.freeze` |
  | `/rd unfreeze [world x z]` | Снять заморозку | `redstonedetector.freeze` |
  | `/rd stop [world x z]` | Приостановить механизмы без разрушения блоков | `redstonedetector.freeze` |
  | `/rd resume [world x z]` | Возобновить приостановленные механизмы | `redstonedetector.freeze` |
  | `/rd entities <world> <x> <z>` | Удалить сущности в чанке | `redstonedetector.admin` |
  | `/rd tp <world> <x> <z>` | Телепортироваться к чанку | `redstonedetector.gui` |
  | `/rd forget <world> <x> <z>` | Прекратить отслеживание чанка | `redstonedetector.admin` |
  | `/rd lang [код/list]` | Показать, перечислить или сменить язык |
  | `/rd version` | Версия плагина и статус обновления | `redstonedetector.gui` |
  | `/rd reload` | Перезагрузить все конфигурации | `redstonedetector.reload` |
  | `/rdcancel` | Отменить ввод в чат (поиск / листание) | — |
    
  ---

  ## 🔐 **Права**

  | Право | Описание | По умолчанию |
      | --- | --- | --- |
  | `redstonedetector.admin` | Полный доступ ко всем функциям | OP |
  | `redstonedetector.command` | Базовый доступ к командам | OP |
  | `redstonedetector.gui` | Панель, статус, инфо, список, телепорт | OP |
  | `redstonedetector.scan` | Запуск и отмена сканирований | OP |
  | `redstonedetector.freeze` | Заморозка, разморозка, стоп и возобновление | OP |
  | `redstonedetector.reload` | Перезагрузка конфигурации | OP |
    
  ---

  ## ⚙️ **Настройка**

  Конфигурация разделена на понятные файлы: `config.yml`, `performance.yml`, `blocks.yml`, `gui.yml` и папка `lang/`.

  **Обнаружение** — критический TPS (`15.0`), лимит редстоуна на чанк (`100`), лимит сущностей (`100`), длительность заморозки (`300 с`), хранение данных чанков (`24 ч`)

  **Производительность** — чанков за тик (`3`), интервал сканирования, скан при низком TPS, только загруженные чанки, кэш статистики, кулдаун сканирования

  **Умная заморозка** — включение интеллектуальной заморозки, порог update score, авто-заморозка при `800` обновлений/с, заморозка при низком TPS от `300` обновлений/с, авто-разморозка через `60 с`, порог MSPT сервера (`45.0`)

  **Детекторы** — чувствительность, параметры скалка (срабатывания, размер группы, окно петли, серии повторов), параметры люков (переключения, кластер, окно петли, серии), радиус заморозки, максимальное время заморозки

  **Фильтры** — игнорируемые миры, чанки и блоки, отладочное логирование
    
  ---

  ## 🌍 **Доступные языки (13)**

    - 🇬🇧 English (`en_us`)
    - 🇷🇺 Русский (`ru_ru`)
    - 🇺🇦 Українська (`uk_ua`)
    - 🇩🇪 Deutsch (`de_de`)
    - 🇫🇷 Français (`fr_fr`)
    - 🇪🇸 Español (`es_es`)
    - 🇮🇹 Italiano (`it_it`)
    - 🇳🇱 Nederlands (`nl_nl`)
    - 🇵🇱 Polski (`pl_pl`)
    - 🇧🇷 Português (Brasil) (`pt_br`)
    - 🇹🇷 Türkçe (`tr_tr`)
    - 🇯🇵 日本語 (`ja_jp`)
    - 🇨🇳 简体中文 (`zh_cn`)

  Все языковые файлы имеют **одинаковую структуру ключей**, поэтому новый перевод — это копия `en_us.yml`. Положите `lang/<locale>.yml` в папку, и он подхватится автоматически; отсутствующие ключи берутся из английского. При `per-player-language: true` каждый игрок видит плагин на языке своего клиента.
    
  ---

  ## 💡 **Разработка и поддержка**

    - Нашли ошибку или есть идея? → [Discord](https://discord.gg/PXDzCQZUch)
    - Хотите помочь с переводом? → канал `#translation` в Discord
    - Хотите внести вклад? → [GitHub](https://github.com/Stepanyaa/RedstoneDetector)

  **Лицензия:** MIT

  > #### ⚠️ **Записка от разработчика**
  >

  > Плагин начинался как решение для моих частных серверов, а теперь доступен всем бесплатно. Он в стадии **активной разработки**, и ваши отзывы напрямую влияют на него — сообщайте о проблемах и делитесь идеями в Discord!
>