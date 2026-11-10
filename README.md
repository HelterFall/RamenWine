# RamenWine

**RamenWine — Android-среда запуска визуальных новелл для максимально широкого спектра Windows-игр.**

Главная цель проекта — чтобы пользователь выбирал папку с визуальной новеллой, RamenWine находил подходящий исполняемый файл и запускал игру непосредственно в Android-приложении. Проект не должен ограничивать совместимость заранее заданным списком игровых движков: KiriKiri, Unity, старые DirectX/DirectDraw-игры, собственные движки и неизвестные варианты должны рассматриваться как цели поддержки.

Это архитектурное направление и план разработки, а не заявление о том, что runtime уже реализован или что каждая игра уже запускается.

## Продуктовый принцип

**Не переносить каждый движок отдельно, если игру можно запустить через совместимость с Windows.**

Основной путь — общий Windows compatibility runtime на базе Wine. Если конкретная игра или семейство движков требует другого подхода, Runtime Router может выбрать другой графический backend, профиль совместимости или специализированный нативный адаптер.

Ни один движок не исключается из проекта по названию. Неудачный запуск должен превращаться в диагностируемую задачу совместимости: определить отсутствующую возможность, подобрать альтернативный backend или добавить адаптер/исправление.

## Пользовательский сценарий

1. Пользователь выбирает папку или архив с игрой.
2. RamenWine безопасно импортирует и индексирует содержимое.
3. Детектор находит кандидатов на запуск, анализирует PE-архитектуру, зависимости и маркеры движка.
4. Пользователь выбирает предложенный EXE либо указывает другой файл вручную.
5. Runtime Manager подготавливает профиль игры и выбирает конфигурацию совместимости.
6. Игра запускается напрямую, без рабочего стола Windows и без необходимости вручную работать с оболочкой Wine.
7. Игровая сессия отображается внутри Android-приложения; пользователь может вернуться в библиотеку.

Выбор EXE не должен сводиться к запуску первого попавшегося файла. Нужны ранжирование кандидатов, обнаружение launcher/updater/helper-файлов и возможность ручного выбора.

## Целевая архитектура

```text
RamenWine Android App
  ├── Library / Import / Game Manager
  ├── Safe Game Scanner
  │     ├── PE32 / PE32+ inspection
  │     ├── dependency and engine hints
  │     └── executable candidate ranking
  ├── Runtime Router
  │     ├── Windows Runtime (primary)
  │     │     ├── Wine (Win32 / Win64 API)
  │     │     ├── WoW64 path for 32-bit Windows apps
  │     │     ├── Box64 (ARM64 host / x86-64 guest)
  │     │     ├── optional Box86 or another 32-bit path
  │     │     └── per-game Windows environment
  │     ├── Graphics Router
  │     │     ├── DXVK (supported D3D generations)
  │     │     ├── WineD3D
  │     │     ├── D8VK / legacy Direct3D path
  │     │     ├── DirectDraw compatibility path
  │     │     └── VKD3D for applicable D3D12 titles
  │     └── Native Runtime Adapters
  │           ├── HTML5
  │           ├── System-NNN
  │           └── future engine-specific adapters
  ├── Android Game Surface
  │     ├── touch / mouse / keyboard / controller input
  │     ├── display, orientation and fullscreen
  │     ├── audio focus and lifecycle
  │     └── pause / resume / exit
  └── Compatibility Database
        ├── game / engine profiles
        ├── device / GPU profiles
        └── reproducible test results
```

## Технологическое направление

| Область | Базовое направление | Примечание |
|---|---|---|
| Windows API | Wine | Основная совместимая среда Win32/Win64 |
| x86-64 на ARM64 | Box64 | Транслирующий слой для ARM64-хоста |
| 32-bit Windows apps | Wine WoW64 + проверенный x86-путь | Сравнить WoW64/Box64 и альтернативы, включая Box86; не считать один вариант универсальным |
| D3D 9–11 | DXVK | Использовать только при достаточной поддержке Vulkan и нужных возможностей драйвера |
| D3D 8 / legacy | D8VK или иной проверенный backend | Подбирать по игре и результатам тестов |
| D3D 12 | VKD3D | Дополнительный путь, а не обязательная часть минимальной сборки |
| Старые графические API | WineD3D / DirectDraw-совместимые реализации | Отдельные профили для старых новелл |
| Android UI | Kotlin + Jetpack Compose | Библиотека, импорт, настройки и управление сессией |
| Native integration | Android NDK + JNI | Интеграция runtime, графики, ввода и аудио |

Конкретные версии Wine, Box64, DXVK и других компонентов выбираются после прототипирования и тестов на реальных ARM64-устройствах. Не фиксировать версию только по принципу «самая новая»: важны совместимость, стабильность, размер, лицензия и поддержка Android Vulkan-драйверов.

## Ключевые инженерные требования

### Windows runtime

- Поддерживать как 32-bit, так и 64-bit Windows-приложения.
- Сосредоточиться на Win32/Win64 API, реально востребованных играми: файловая система, процессы, потоки, синхронизация, окна/ввод, память, шрифты, мультимедиа и графика.
- Не предполагать, что все игры используют один набор DLL или одну версию DirectX.
- Не удалять системные реализации по одному только названию подсистемы. Сеть, криптография, COM, установщики и другие компоненты могут быть косвенными зависимостями игры или launcher'а. Минимизировать сборку через проверенную конфигурацию и опциональные пакеты, а не через рискованное удаление нужных API.
- Не поставлять Windows Desktop/Explorer как пользовательский интерфейс. Запускать выбранный игровой EXE напрямую, сохраняя только необходимые внутренние службы Wine.

### Graphics Router

Графический backend выбирается по совокупности факторов: обнаруженный API, архитектура игры, Vulkan capabilities, GPU/драйвер, известный профиль игры и результаты предыдущих запусков.

Порядок не должен быть жёстко зашит для всех устройств. RamenWine должен уметь пробовать безопасные альтернативы и сохранять стабильную конфигурацию для конкретной игры.

DXVK не является универсальным графическим решением: нужны пути для старых DirectX/DirectDraw, D3D12 и случаев, где Vulkan-драйвер не предоставляет требуемых возможностей.

### Новелльные особенности

Планировать поддержку и тестирование:
- японских системных локалей, кодировок Shift-JIS/CP932 и соответствующих шрифтов;
- Unicode, IME и отображения текста;
- полноэкранного режима, нестандартных разрешений и ориентации;
- мыши, сенсорного ввода, экранной клавиатуры и геймпада;
- аудио, голоса, видео и синхронизации мультимедиа;
- сохранений, конфигураций и пользовательских файлов;
- оконных диалогов, выбора файлов и системных сообщений, которые могут вызывать старые движки.

### Профили и хранение

- Общие проверенные runtime-компоненты хранятся отдельно от данных игры.
- У каждой игры собственный изолированный Wine-prefix/профиль, настройки и сохранения.
- Использовать copy-on-write или иной экономный способ повторного использования общих файлов, если он надёжно работает на Android.
- Не копировать полный runtime для каждой игры без необходимости.
- Импортированные игры и их исполняемые файлы считаются недоверенными данными.
- Не запускать EXE на этапе сканирования; исполнение возможно только после явного действия пользователя.

### Android-интеграция

- Игровое окно должно занимать игровую поверхность Android-приложения, без Windows Desktop.
- Корректно обрабатывать pause/resume, потерю фокуса, блокировку экрана, смену ориентации и уничтожение Activity.
- Отдельно контролировать аудиофокус, ресурсы GPU, память и завершение дочерних процессов.
- Предусмотреть переназначение сенсорных зон на мышь/клавиатуру и пользовательские профили управления.

## Совместимость: как измеряем результат

Статусы должны быть доказательными и не смешиваться:

1. **Detected** — найден кандидат на движок или исполняемый файл.
2. **Analyzed** — проверена структура PE, архитектура и доступные зависимости.
3. **Launch attempted** — запуск действительно был произведён.
4. **Booted** — игра дошла до интерактивного игрового экрана.
5. **Playable** — проверены текст, изображения, звук и базовое управление.
6. **Validated** — проверены сохранения, загрузка, пауза/возврат, длительная сессия и заявленная конфигурация устройства.

Для каждой записи совместимости фиксировать:
- игру и версию;
- EXE и архитектуру;
- движок и его версию, если удалось определить;
- Android-версию, SoC, GPU, драйвер и ABI;
- версии Wine/Box64/графического backend;
- параметры prefix и профиля;
- результат каждого теста;
- логи, известные дефекты и обходные решения.

Цель — максимально широкий охват игр, без постоянного запрета целых семейств движков. Неизвестный движок сначала проходит общий Windows runtime path; специализированный адаптер добавляется, когда он даёт измеримое преимущество или закрывает пробел совместимости.

## Оптимизация

Оптимизация должна опираться на измерения, а не на максимальное вырезание библиотек.

Измерять отдельно:
- размер базовой установки и дополнительных компонентов;
- холодный и повторный запуск;
- пиковую и среднюю RAM;
- CPU overhead трансляции x86/x64;
- GPU/VRAM pressure и стабильность кадров;
- энергопотребление и нагрев;
- стабильность аудио/видео;
- размер игровых prefixes и кэшей.

Базовая установка должна быть компактной, а необязательные backend и зависимости — подключаемыми локальными пакетами. При этом пользователь должен иметь возможность подготовить нужные компоненты заранее; запуск игры не должен требовать скачивания их из сети.

## План разработки

### Phase 0 — техническая фиксация
- Зафиксировать версии-кандидаты и лицензии Wine, Box64, Box86, DXVK, D8VK, VKD3D и Android-зависимостей.
- Выбрать минимальный Android ARM64 test device baseline.
- Подготовить оригинальные или свободно распространяемые тестовые Windows-приложения.

### Phase 1 — минимальный Windows launch
- Собрать ARM64 userspace и Wine.
- Интегрировать Box64.
- Запустить простой x86-64 Windows GUI EXE из Android-приложения.
- Передать изображение в Android Surface.
- Обеспечить корректный exit и очистку процессов.

### Phase 2 — 32-bit compatibility
- Проверить Wine WoW64 и x86-приложения.
- Сравнить Box64 WoW64-путь с отдельным 32-bit вариантом.
- Составить честную ABI/архитектурную матрицу.

### Phase 3 — графика и ввод
- Добавить backend router.
- Проверить DXVK на совместимых Vulkan-драйверах.
- Добавить WineD3D и legacy DirectDraw/Direct3D путь.
- Подключить сенсорный ввод, мышь, клавиатуру и геймпад.

### Phase 4 — visual novel compatibility
- Проверить KiriKiri/KAG, Unity VN, старые DirectX/DirectDraw-игры и несколько неизвестных движков.
- Добавить тесты японской локали, шрифтов, аудио, видео и сохранений.
- Создать profiles для подтверждённых сочетаний игры, backend и устройства.

### Phase 5 — production hardening
- Изоляция каждой игры, безопасный импорт и управление процессами.
- Профили устройств, отчёты сбоев и диагностические логи.
- Измерение памяти, энергопотребления, температуры и времени запуска.
- Проверка лицензий, уведомлений и требований к распространению всех компонентов.

## Связь с исходным Ramen

RamenWine может повторно использовать полезные наработки исходного Ramen:
- Kotlin/Compose UI и библиотеку игр;
- ZIP и folder import;
- безопасное копирование и инвентаризацию;
- PE inspector и анализ импортируемых DLL;
- форматные парсеры GPK/GTB и VPK/VTB;
- HTML5 и System-NNN runtime как специализированные адаптеры.

Эти реализации не заменяют Windows compatibility runtime, но остаются полезными для обнаружения, диагностики и отдельных игр.

## Правило принятия решений

При выборе между новым портом движка и расширением общего runtime сначала проверять, можно ли решить задачу в общей Windows-среде. Новый нативный адаптер оправдан, когда он закрывает конкретную несовместимость, улучшает производительность/стабильность или позволяет запускать игру, которую общий runtime пока не обслуживает.

**RamenWine строится вокруг максимальной совместимости. Ни один движок не объявляется неподдерживаемым только потому, что его ещё не исследовали.**




## Current implementation status

The Android host scaffold currently includes:

- Jetpack Compose app and ARM64 NDK/JNI bridge;
- SAF folder selection, bounded EXE/PE inspection and manual EXE selection;
- streamed import into private app storage with live progress;
- local JSON game library with atomic metadata persistence;
- runtime inventory for Bionic ARM64EC/FEX, Bionic + Box64 and glibc + PRoot + Box64;
- offline runtime ZIP installation with SHA-256 payload verification, staged activation, symlink/path checks and rollback;
- a Python runtime package builder and automated Python/JVM tests;
- a PRoot/Wine command builder and process supervisor for the first glibc + PRoot + Box64 + Wine path;
- Wawona support-asset integrity verification and JVM tests;
- GitHub Actions for Android build, tests, lint and debug APK artifacts.

**Wine/Box64/FEX/PRoot binaries are not bundled, and imported games cannot be launched from the Android UI yet.** The command builder requires an existing Wayland socket, and no Wawona compositor is integrated to provide it. The process supervisor is not wired to a Play action and has not been validated against a physical device or complete Wine child-process teardown. Runtime file checks are not proof of execution. No game EXE is run during scanning or import.

See [Development Status](docs/DEVELOPMENT_STATUS.md), [Android Runtime Backends](docs/ANDROID_RUNTIME_BACKENDS.md), [Android Display Backend](docs/ANDROID_DISPLAY_BACKEND.md) and [PRoot Wine Launch](docs/PROOT_WINE_LAUNCH.md).

Wawona is the selected display/compositor research candidate. The upstream project is MIT-licensed at its root and uses a Rust Wayland core with an Android Kotlin/JNI host. The reviewed upstream snapshot is pinned in [Android Display Backend](docs/ANDROID_DISPLAY_BACKEND.md). RamenWine currently has only a Wawona asset-integrity helper; it does not bundle the upstream native libraries or start a compositor.
