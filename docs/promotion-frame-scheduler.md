# Extended ProMotion Frame Scheduler

Статус на 2026-07-24: нативный scheduler интегрирован в обычный present и в
production MetalFX Frame Interpolation. Автоматические policy/native/parity
проверки пройдены; live Metal HUD, input-latency и длительный игровой прогон
остаются отдельным acceptance gate.

## Цель и границы

Scheduler управляет только временем и порядком presentation. Он не меняет:

- render/display extent, Spatial/Temporal preset или DRS policy;
- цветовые форматы, HDR/EDR tone mapping, bloom и material generation;
- содержимое world/UI textures или качество MetalFX;
- частоту симуляции и не вводит скрытый FPS cap.

Поэтому при выключенном Frame Interpolation изображение остаётся тем же real
frame, а при включённом FI сохраняется существующий world-only MetalFX + общий
SDR UI composite. Меняется только способ выдачи готовых drawable дисплею.

## Что подтверждает документация Apple

Использованы следующие публичные контракты Apple:

- [`NSScreen`](https://developer.apple.com/documentation/appkit/nsscreen)
  сообщает `maximumFramesPerSecond`, `minimumRefreshInterval`,
  `maximumRefreshInterval`, `displayUpdateGranularity` и
  `lastDisplayUpdateTimestamp`. Это runtime contract дисплея; 120 Гц нельзя
  задавать константой.
- [WWDC21: Optimize for variable refresh rate displays](https://developer.apple.com/videos/play/wwdc2021/10147/)
  описывает Adaptive-Sync как полноэкранный режим, рекомендует выбирать
  устойчивую частоту по времени работы GPU и использовать
  `present(afterMinimumDuration:)` для engine-owned render loop.
- [`MTLDrawable`](https://developer.apple.com/documentation/metal/mtldrawable)
  предоставляет `present(afterMinimumDuration:)`, `addPresentedHandler` и
  `presentedTime`. Minimum-duration presentation сохраняет ровную последовательность
  относительно фактического предыдущего present, а handler даёт on-glass
  timestamp вместо времени завершения command buffer.
- [CAMetalDisplayLink](https://developer.apple.com/documentation/quartzcore/cametaldisplaylink)
  выдаёт заранее полученный drawable и target timestamps. Его
  `preferredFrameRateRange` и `preferredFrameLatency` полезны для приложения,
  где display link является владельцем render loop.
- [Apple sample: Achieving smooth frame rates with a Metal display link](https://developer.apple.com/documentation/metal/achieving-smooth-frame-rates-with-a-metal-display-link)
  показывает display-link architecture и необходимость реагировать на
  меняющуюся частоту дисплея.
- [WWDC25: Go further with Metal 4 games](https://developer.apple.com/videos/play/wwdc2025/211/)
  задаёт ключевые FI-инварианты: generated перед соответствующим real,
  стабильные интервалы, вход не ниже примерно 30 real FPS и не более двух
  ожидаемых interval buckets.

### Почему production path не использует CAMetalDisplayLink

`CAMetalDisplayLink` не является независимым таймером: callback уже приносит
принадлежащий display-link drawable. Текущий Minecraft/GLFW renderer сам
вызывает `CAMetalLayer.nextDrawable()` после завершения world/UI render graph.
Если одновременно запустить display link и продолжить старый acquire path,
два владельца будут конкурировать за drawable pool. Кроме того, Apple запрещает
смешивать display-link drawable с альтернативным timed-present API.

Поэтому безопасный текущий путь использует рекомендованный Apple вариант для
engine-owned loop: runtime timing из `NSScreen` +
`present(afterMinimumDuration:)` + фактический `presentedTime`. Полный переход
на `CAMetalDisplayLink` возможен только как отдельный рефакторинг, где его
drawable заменит, а не дополнит текущий `nextDrawable()` contract.

## Аудит прежней реализации

До этой работы в репозитории уже были:

- native AppKit fullscreen/window coordination;
- обычный single-real present через `commandBuffer.present(drawable)`;
- production MetalFX coordinator с preallocated three-slot ring;
- корректная последовательность generated → real, отдельный SDR UI и
  fail-open real presentation;
- отбрасывание late generated frame и bounded backpressure;
- static refresh admission по `maximumFramesPerSecond` и синтетический Stage-7
  pacing harness.

Не хватало общего владельца cadence для FI Off/On, runtime VRR диапазона,
точного production deadline, системных display transitions и фактических
on-glass timestamps. Production FI использовал общий GCD delay и учитывал
завершение GPU command buffer как presentation, что не доказывает момент вывода
на экран.

## Реализованная архитектура

### Display contract и lifecycle

`MetallumExtendedProMotionSchedulerRegistry` хранит один scheduler на
`CAMetalLayer`. AppKit-состояние снимается на main thread и обновляется при:

- переходе окна между экранами и изменении screen profile;
- изменении backing properties или глобальной конфигурации дисплеев;
- входе/выходе из native fullscreen;
- изменении EDR/display state.

Snapshot включает весь runtime диапазон refresh и fullscreen flag. Изменение
контракта увеличивает `displayGeneration`, сбрасывает cadence estimators и
делает уже подготовленный FI plan устаревшим. Generated member устаревшей пары
отбрасывается; mandatory real идёт через актуальный real-only fallback.

### Real-only, Frame Interpolation Off

`MetallumExtendedProMotionScheduler.realOnlyPlan` объединяет устойчивый render
delta и завершённое GPU duration, затем выбирает не более быструю частоту с
небольшим jitter reserve. Переход к более медленной частоте происходит сразу;
возврат к более быстрой требует нескольких подтверждений, чтобы частота не
дребезжала около границы.

- Fullscreen + реальный variable-refresh range: adaptive timed presentation.
- Windowed или fixed-refresh display: прежний display-synced plain present;
  диагностическая цель квантуется к кратной физической частоте.
- VSync/display sync Off: unmanaged plain present без навязанной pacing
  задержки.
- `METALLUM_PROMOTION_SCHEDULER=0`: полный fail-open к прежнему present.

### Frame Interpolation On

Для 2x synthesis admission использует cadence настоящих кадров и runtime
display contract:

- минимум шесть валидных real samples перед включением пары;
- real input не ниже 30 FPS;
- 2x stream не может превышать максимум панели — real frames не отбрасываются
  ради generated;
- fullscreen Adaptive-Sync допускает, например, 60→120, 40→80 и 30→60;
- fixed/windowed режим допускает только честную кратную cadence, например
  30→60; несовместимый 40→80 на фиксированных 120 Гц идёт real-only;
- скрытого ограничения real FPS до половины refresh нет.

Production worker имеет `userInteractive` QoS. Он отправляет optional generated
member первым, ждёт абсолютный midpoint через один заранее созданный
`kqueue`/`kevent64` Mach timer (`NOTE_MACHTIME | NOTE_ABSOLUTE`), затем отправляет
mandatory real. `Thread.sleep`, CPU readback, synchronous GPU wait и per-frame
texture/buffer allocation не используются. Если generated опоздал, drawable
недоступен, display generation изменилась или Metal encode завершился ошибкой,
generated отбрасывается, а real всё равно предпринимается.

### Фактическая обратная связь

Каждый drawable регистрирует `addPresentedHandler`. Scheduler считает кадр
показанным только по валидному `presentedTime`, а не по completion command
buffer. Это позволяет отличать GPU completion от фактического вывода и строить
интервалы real/generated на одной шкале времени.

## Telemetry

В metadata каждого JSONL GPU timing report добавлен объект
`extended_promotion_scheduler`:

- `mode`: `unmanaged`, `fixedRefresh` или `adaptiveRefresh`;
- runtime display ID, maximum FPS, minimum/maximum interval, granularity,
  fullscreen/VRR flags и display generation;
- `target_presented_fps`;
- timed/plain present request counts;
- фактически показанные `real_only`, `generated` и `interpolated_real`;
- missed target, skipped/stale presentation callback и rate-transition counts;
- средний фактический интервал и число histogram buckets.

Существующий `frame_interpolation_pacing` теперь увеличивает generated/real
presentation counters из `presentedTime` handler. Синтетический Stage-7 harness
сохраняет свои отдельные policy counters.

## Проверки

`metallum_extended_promotion_scheduler_stress_v1` проверяет:

- adaptive real-only 60 FPS pacing;
- FI 60→120 и 40→80;
- отказ от oversubscribed 90→180;
- 30→60 на fixed 60 Гц;
- запрет некратного 40→80 в windowed/fixed 120 Гц;
- VSync-off bypass и invalidation старого display plan;
- работу абсолютного Mach deadline.

Проверка подключена к `frameInterpolationValidation`, которая также запускает
Stages 4–10, Metal API Validation, 12 SDR/EDR/HDR present-parity сценариев и
allocation/performance regression.

## Ожидаемый эффект и оставшийся live gate

При устойчивой нагрузке scheduler должен убрать случайные burst/неравномерные
интервалы, позволить ProMotion выбрать устойчивую частоту настоящих кадров и
равномерно разместить generated/real pair до 120 Гц. Он не может создать 120
уникальных real кадров, ускорить тяжёлый render graph или гарантировать частоту,
которую macOS снизила из-за power/thermal policy.

До продуктового утверждения нужны на встроенном дисплее MacBook Pro:

- одинаковый fullscreen route с FI Off/On и VSync policy, записанной явно;
- Metal HUD frame-interval graph и JSONL telemetry;
- real/generated/total FPS, p95/p99, 1%/0.1% lows и input-to-real latency;
- window/fullscreen/F11/green-button/display/headroom transitions;
- UI, camera motion, teleport/resource reload и 30–60 минут gameplay.
