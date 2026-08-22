# FRAME INTERPOLATION 22 AUG

Дата проверки: 22 августа 2026 года
Стенд: MacBook Pro 14-inch (MacBookPro18,3), Apple M1 Pro, 16 GPU cores, 16 GB unified memory, встроенный дисплей ProMotion 120 Hz, macOS 27.0 (26A5416b)
Проверенный код: `6e69586afa85d17173aa7dba8b82f747f6b8488b`, ветка `reflections`

## Краткий вердикт

1. **Стоимость около 20–22 мс для MetalFX FI с native HDR-выходом 3024×1964 реальна.** Изолированный `MTLFXFrameInterpolator` на `RGBA16Float` занял 22,17 мс в среднем и 22,69 мс p95. Историческая встроенная телеметрия Metallum на игровом пути показывала практически то же: 20,06–20,08 мс.
2. **Наблюдаемые примерно 20 сгенерированных кадров в секунду — не чистый аппаратный предел самого интерполятора.** Один FI-вызов в native теоретически даёт около 45 результатов/с в изоляции. До примерно 20/с весь путь падает из-за сочетания FI, рендера, backpressure, двух HDR-композитов, drawable availability и нестабильного presentation pacing.
3. **30 real + 30 generated = 60 в native вычислительно возможно, но текущая реализация не обеспечивает стабильную выдачу на экран.** Лучшие старые прогоны создавали 297/300 и даже 300/300 generated frames без interpolation failures и без out-of-order, однако проваливали cadence: 115–123 промаха на 593–600 интервалах. Новый чистый прогон 22 августа был справедливо отключён health gate после 31 generated presentation с причиной `ON_GLASS_CADENCE`.
4. **60 real + 60 generated = 120 при native 3024×1964 HDR на M1 Pro невозможно.** Связка Temporal+FI одна, ещё до рендера мира и финального композита, занимает 20,61 мс при 40% temporal input и 21,87 мс при 50%. Бюджет одного source frame при 60 FPS — 16,67 мс.
5. **Temporal 50% сам по себе не решает проблему, пока FI выдаёт native 3024×1964.** Стоимость определяется в основном разрешением FI output, а не только depth/motion input.
6. **Буквальная схема “FI в 50%, затем Temporal upscale generated frame” тоже не даёт 60+60 без потери качества.** Два temporal resolve и низкоразрешённый FI заняли 16,30 мс в среднем / 17,10 мс p95 ещё до игрового рендера. Кроме того, FI не создаёт midpoint depth/motion для корректной temporal history.
7. Для 120 FPS нужно снижать **именно разрешение, в котором FI формирует output**, а не только внутреннее разрешение мира. 2560×1600 всё ещё почти полностью съедает бюджет; точка около 2048×1330 выглядит как кандидат для будущего live-прототипа, но 120 FPS на ней пока не доказаны.

Итог в одной фразе: **FI у нас одновременно дорогая по-честному и проблемная по части presentation/pacing. Исправления способны сделать 30→60 стабильнее без снижения качества, но не способны превратить native HDR 60→120 в рабочий режим на M1 Pro.**

## Что именно проверялось

### Границы работы

- Реализация FI не менялась.
- Основные FI-файлы в проверенном clean worktree совпадали с `HEAD`.
- Измерительный Swift/MetalFX probe находился в `/private/tmp`, в продукт не добавлялся.
- Главный worktree содержит пользовательский WIP, поэтому живые тесты запускались из отдельного clean worktree на том же commit. Это также обошло не относящуюся к FI ошибку текущего dirty `build.gradle` при передаче `METALLUM_*` environment overrides.
- Результаты являются Tier B: аппаратные GPU timestamps, строгая on-glass телеметрия и воспроизводимый benchmark route. Визуальное качество low-resolution альтернатив не объявляется принятым без отдельного Tier C gameplay review.

### Проверка архитектуры по коду

Текущая production-схема в важных местах соответствует публичной рекомендации Apple:

- `MTLFXFrameInterpolatorDescriptor.inputWidth/inputHeight` получают render extent, а `outputWidth/outputHeight` — display extent (`MetallumFrameInterpolationCoordinator.swift:1287-1297`).
- При Fixed Temporal в descriptor передаётся существующий `MTLFXTemporalScaler` (`:1303-1315`). Это штатный combined Temporal+FI путь, а не два независимых эффекта.
- Depth и motion выделяются в render extent, но real/generated HDR color — в display extent (`:1330-1380`).
- FI кодируется на той же command queue сразу после producer/Temporal (`:2948-2967`).
- В FI передаются предыдущий и текущий display-sized post-world color, input-sized depth/motion и display-sized output (`:2968-2997`).
- UI не интерполируется: он накладывается отдельным full-resolution composite после FI (`:3374-3415`). Это правильнее для текста и HUD.

Apple в [WWDC25: Go further with Metal 4 games](https://developer.apple.com/videos/play/wwdc2025/211/) рекомендует интерполяцию после tone mapping, повторное использование motion/depth и передачу temporal scaler в FI descriptor. Официальное описание размеров находится в [`MTLFXFrameInterpolatorDescriptor`](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatordescriptor). Поэтому сами размеры input depth/motion и связка scaler в Metallum не выглядят ошибкой.

### GPU microbenchmark

Probe создавал реальные `MTLFXTemporalScaler` и `MTLFXFrameInterpolator`, private `RGBA16Float`, `Depth32Float` и `RG16Float` textures, заполнял их ненулевым детерминированным содержимым, выполнял 20 warm-up и 120 measured iterations. В таблице для chained случаев указано время критического GPU-пути от начала первого MetalFX command buffer до конца последнего; отдельные command-buffer durations не складывались, потому что внутренние MetalFX работы могут перекрываться.

| Схема | Input | FI/output | Mean, мс | p95, мс | Что это означает |
|---|---:|---:|---:|---:|---|
| Standalone FI | 3024×1964 | 3024×1964 | 22,17 | 22,69 | Честная native FI; потолок около 45 generated/с до игры |
| Standalone FI | 1512×982 | 1512×982 | 5,80 | 6,16 | FI действительно хорошо масштабируется при снижении её output |
| Standalone FI | 1210×786 | 1210×786 | 3,86 | 4,33 | 40% output ещё дешевле |
| Linked Temporal+FI | 1512×982 (50%) | 3024×1964 | 21,87 | 22,40 | Native output; 60+60 невозможно |
| Linked Temporal+FI | 1210×786 (40%) | 3024×1964 | 20,61 | 20,99 | Снижение input с 50% до 40% экономит лишь около 1,26 мс |
| Linked Temporal+FI | 1280×800 (50%) | 2560×1600 | 15,45 | 16,33 | Почти весь 16,67-мс бюджет уже занят MetalFX |
| Linked Temporal+FI | 1024×665 (50%) | 2048×1330 | 10,67 | 11,54 | Первый разумный кандидат, но остаётся лишь около 5,1 мс p95 на всю игру и presentation |
| Temporal(real)+low-res FI+Temporal(generated) | 1512×982 | 3024×1964 | 16,30 | 17,10 | Уже превышает p95 budget до рендера мира |
| Temporal(real)+low-res FI+Temporal(generated) | 1210×786 | 3024×1964 | 11,91 | 12,19 | Дешевле, но temporal-history для midpoint всё ещё некорректна и бюджет игры крайне мал |

Важная ловушка измерения: linked FI без предварительного encode связанного temporal scaler показывала около 1,28 мс. Это не рабочая цепочка — после реального Temporal encode стоимость FI возвращалась к 18,7–19,4 мс, а полный критический путь к 20,6–21,9 мс. Поэтому значение 1,28 мс не использовалось в выводах.

## Живые проверки в Minecraft

### Чистые прогоны 22 августа

Оба запуска использовали строгий профиль FI Auto: HDR, Fixed Temporal Ultra Performance (40%), VSync, source cap 30, 300 warm-up + 300 measurement, frozen route `hdrtest-static-v1`.

| Display/output | Temporal input | Результат |
|---:|---:|---|
| 3024×1964@120 | 1210×786 | FI допущена, затем quarantine после 31 generated presentation: `ON_GLASS_CADENCE`; измерение прошло в safe real-only fallback и корректно завершилось FAIL |
| 2560×1600@120 | 1024×640 | Тот же результат: quarantine после 31 generated presentation: `ON_GLASS_CADENCE` |

Логи:

- `run/logs/metallum-benchmarks/20260822T010341Z-g6e69586afa85-clean-fi-22aug-native-ultra-screening-clean-temporal_ultra_performance.console.log`
- `run/logs/metallum-benchmarks/20260822T011433Z-g6e69586afa85-clean-fi-22aug-2560x1600-ultra-screening-clean-temporal_ultra_performance.console.log`

Одинаковое отключение после одного 60-интервального evaluation block и при native, и при 2560×1600 показывает: текущий отказ 30→60 нельзя объяснить только native pixel cost. Health gate допускает максимум 2 target misses и 1 severe interval на 60 сравнимых интервалах (`MetallumFrameInterpolationCoordinator.swift:855-927`). Ослабление gate не является исправлением: оно лишь скрыло бы видимую неровность.

### Исторические диагностические прогоны

Эти логи относятся к более старому dirty commit `466c945caeab`, поэтому они не являются сравнительным acceptance benchmark текущего `HEAD`. Они полезны как локализация причины, поскольку содержат более подробную FI-телеметрию.

#### 60 real → 120 target, Temporal Performance 50%, native output

- Без FI тот же route держал 59,6 real FPS; presenting command buffer: 12,092 мс mean, 13,332 мс p95, 13,576 мс p99.
- С FI source cadence падала до 25,04–28,06 мс на real frame, то есть примерно до 36–40 real FPS.
- В одном прогоне было 245 generated/300 real; 256 admission waits со средним 22,30 мс. Только 26 интервалов целились в 120 Hz, 366 — в 80 Hz, 63 — в 60 Hz.
- В другом было 284 generated/300 real; admission waits в среднем 16,23 мс. Target 120 не использовался вовсе: 32 интервала на 80 Hz и 510 на 60 Hz.

Это прямое доказательство, что текущая FI/presentation pipeline отнимает у producer его исходные 60 FPS. Но даже идеальное удаление CPU/drawable stalls не спасёт native 120: MetalFX critical path сам длиннее 16,67 мс.

#### 30 real → 60 target, native output

- Temporal Ultra: 297 generated / 300 real, 0 backpressure, 0 interpolation failures, 0 out-of-order.
- Несмотря на почти идеальную генерацию, было 123 target misses на 593 интервалах и 33 severe late.
- Эксперимент с заранее приобретённой парой drawables получил 300/300 generated, но всё равно 115 misses на 600 интервалах. Фазы были асимметричны: generated→real 15,389 мс, real→generated 18,083 мс вместо ровных 16,667/16,667 мс. Среднее ожидание real drawable достигло 13,294 мс, максимум — 31,709 мс.

Следовательно, счётчик generated frames не равен плавности. Интерполятор способен посчитать 30 generated/с, а текущий presentation path не способен равномерно показать их.

## Какие проблемы в FI можно исправить без потери качества

### 1. Presentation phase и drawable ownership

В текущем пути generated и real кадры отдельно получают drawable, отдельно выполняют full-resolution HDR/UI composite и коммитятся через presentation queue (`MetallumFrameInterpolationCoordinator.swift:3347-3497`). Историческая асимметрия 15,4/18,1 мс и большие real drawable waits показывают, что именно эта граница вносит заметную часть неровности.

Это не требует снижать качество картинки. Следующий инженерный шаг — привести ownership/pacing к completion-driven PresentThread discipline из примера Apple: один строго ограниченный pair in flight, FI completion как фазовый якорь, generated present немедленно после готовности, real present через измеренную половину source interval, без раннего удержания второго drawable.

### 2. Admission feedback тормозит source producer

Production ring разрешает до двух live tickets, после чего render thread ждёт освобождения slot вплоть до 100 мс (`:1998-2027`). В 60-source экспериментах ожидание возникало почти на каждом кадре и занимало 16–22 мс в среднем. Это объясняет переход исходной частоты с 60 к 36–40 FPS.

Для 30→60 это можно улучшать без потери качества: отделить сохранение history от drawable lifetime и не держать admission до завершения лишней presentation-работы. Для native 60→120 такая оптимизация полезна по стабильности, но недостаточна по GPU budget.

### 3. Недостаёт production-разбивки GPU cost до срабатывания quarantine

Текущий строгий benchmark начинает measurement после route warm-up, а health gate успевает отключить FI на 31-й generated frame. Поэтому measurement section видит только safe fallback. Перед изменением scheduler нужно добавить диагностические, выключенные по умолчанию timestamps для:

- producer world + Temporal;
- FI encode;
- generated HDR/UI composite;
- real HDR/UI composite;
- drawable waits и фактические `presentedTime` до quarantine.

Это инструментирование, а не изменение качества или FI-алгоритма. Оно позволит проверить конкретную гипотезу, а не двигать таймеры вслепую.

### Что не надо считать исправлением

- Увеличить допустимое число misses в health gate.
- Считать `generated_count` доказательством плавности.
- Отключить HDR или перейти на более дешёвый pixel format без отдельного запроса на компромисс качества.
- Подменить temporal upscale generated frame обычным spatial upscale и объявить качество сохранённым.
- Пытаться получить 120 Hz только уменьшением render input при сохранении native FI output.

## Ответ на эксперимент Temporal 50% + FI

### Штатный linked-путь

Он уже реализован: FI получает 1512×982 depth/motion и ссылку на тот же temporal scaler, а color/output остаются 3024×1964. Это именно рекомендуемая Apple combined-схема. Результат — 21,87 мс mean / 22,40 мс p95 только для Temporal+FI. **60+60 не получается.**

### Буквальный low-resolution FI до Temporal

Чтобы оба показанных кадра были native, temporal scaler надо применить и к real, и к generated low-resolution color. Измеренный critical path при 50% — 16,30 мс mean / 17,10 мс p95. Рендеру Minecraft, HDR composite и drawable presentation времени уже не остаётся.

Есть и качественный блокер: FI выдаёт interpolated color, но не midpoint depth, motion, reactive mask и jitter history. Передача real-frame auxiliary textures во второй temporal resolve нарушает temporal semantics и с высокой вероятностью даст ghosting/disocclusion artifacts. Корректного quality-preserving протокола для такого порядка текущий MetalFX API не предоставляет.

Поэтому ответ: **нет, Temporal 50% + low-resolution FI не превращает текущий M1 Pro профиль в 60+60=120.**

## Какое снижение разрешения имеет смысл

Снижать надо output FI:

- **3024×1964:** окончательно reject для 60→120 на M1 Pro.
- **2560×1600:** Temporal+FI p95 16,33 мс; reject для 60→120, потому что игре остаётся около 0,34 мс p95. Живой 30→60 прогон также не вылечил cadence.
- **примерно 2048×1330:** Temporal+FI p95 11,54 мс; это первая точка, где остаётся около 5,13 мс p95. Считать её рабочей пока нельзя: нужен отдельный прототип, полный renderer GPU budget и on-glass 120-Hz proof.

Практический дизайн для будущего 120-Hz режима, если он вообще нужен на M1 Pro: отдельный ограниченный профиль с примерно 2K FI output и последующим единичным простым display resolve. Это уже явный компромисс разрешения generated frames; его качество нужно сравнить в движении, на HUD edges, particles, foliage, water, cave disocclusions и резких поворотах камеры.

## Рекомендуемый следующий этап

### Цель A: довести native HDR 30→60 без потери качества

1. Добавить только диагностические timestamps до quarantine.
2. Испытать одну гипотезу: completion-driven pair presentation без раннего удержания real drawable.
3. Не менять MetalFX descriptor, HDR format, temporal scale или health thresholds.
4. Принимать изменение только если одновременно выполнены:
   - не менее 297 generated на 300 real;
   - 0 interpolation failures, 0 out-of-order, 0 backpressure drops;
   - не более 2 target misses и не более 1 severe interval в каждом 60-интервальном block;
   - повторный полный strict run остаётся `ACTIVE`;
   - отдельный live visual review не находит новых FI artifacts.

### Цель B: не тратить время на native HDR 60→120

Эту конфигурацию следует зафиксировать как `DO NOT RETRY` для M1 Pro: hardware budget уже опровергает её до Minecraft workload.

### Цель C: отдельный 120-Hz experiment только с пониженным FI output

Если 120 Hz остаётся приоритетом, следующий измеримый кандидат — около 2048×1330 FI output, не native output с 50% auxiliary inputs. Stop-gate до игрового внедрения: MetalFX critical-path p95 не выше 11,5 мс и оценочный полный GPU p95 не выше 15,5 мс. После этого нужен live 60-source run и настоящий on-glass 120-Hz acceptance; screening FPS или generated counter недостаточны.

## Финальный ответ на поставленные вопросы

- **Есть ли честная стоимость FI?** Да: около 20–22 мс для native HDR output на M1 Pro подтверждено двумя независимыми способами.
- **Есть ли проблемы в нашей FI?** Да: presentation cadence, drawable waits и admission feedback. Они мешают даже вычислительно допустимому 30→60 и могут быть исправлены без снижения качества.
- **Можно ли после исправления получить native HDR 60+60?** Нет. GPU budget это исключает.
- **Поможет ли Temporal Upscale 50% при native FI output?** Нет; linked-схема уже так работает на auxiliary input, но FI остаётся native-cost.
- **Поможет ли FI в тех же 50%, что и вход Temporal?** Не для quality-preserving 120 FPS: два temporal resolve плюс FI уже не помещаются в бюджет, а корректных midpoint auxiliary данных нет.
- **Нужно ли снижать разрешение?** Для 30→60 — необязательно, сначала следует исправить pacing. Для 60→120 — обязательно снижать FI output; 2560×1600 недостаточно, около 2048×1330 является лишь кандидатом на следующий эксперимент.
