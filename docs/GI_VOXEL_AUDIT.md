# GI/voxel audit: что уже есть и что можно восстановить

Дата аудита: 22 августа 2026 года. Текущая ветка: `reflections`, HEAD
`bc04c5880b9cdc6286c69d0dc214ddfa107e6edc`.

Этот документ фиксирует фактическую базу для
[GLOBAL_ILLUMINATION_PLAN.md](GLOBAL_ILLUMINATION_PLAN.md). Он не объявляет
незакоммиченный prototype production-кодом и не заменяет Tier C/live validation.

## 1. Краткий итог

- Полноценного GI в текущей ветке нет.
- Принятые L5/L6 voxel systems уже находятся в ancestry `reflections`; ничего
  восстанавливать из старых `l5_vocxels`/`l6-shadows2` ради базовой occupancy не
  требуется.
- Найден отдельный dirty worktree `.codex-worktrees/l7-radiance-clipmap` с большой
  незакоммиченной L7 реализацией. Ветка worktree и `reflections` указывают на один
  HEAD, поэтому L7 нельзя cherry-pick: все отличия живут только в working tree.
- L7 field/mip/lifetime/tests содержат полезную инфраструктуру. Его lighting
  estimator не является GI, а все проверенные terrain reflection receivers
  остановлены по стоимости или невозможности безопасно встроить pass.
- В рамках этого аудита source-код L7 в `reflections` не переносился. Добавлены
  только два документа. Это намеренное решение: слепой перенос смешал бы
  reusable field code с несколькими уже отклонёнными shader/native paths.

## 2. Состояние git и worktree

`git worktree list --porcelain` показывает:

```text
reflections                         bc04c588...  branch refs/heads/reflections
.codex-worktrees/l7-radiance-clipmap bc04c588... branch refs/heads/experiment/l7-radiance-clipmap
```

`git rev-parse reflections experiment/l7-radiance-clipmap` возвращает один и тот
же commit. При этом L7 worktree содержит:

- 18 tracked modified files, суммарно около `+1513/-50` строк;
- untracked `src/main/java/com/metallum/client/radiance/` — 17 Java files;
- untracked `src/test/java/com/metallum/client/radiance/` — 4 test files;
- untracked `src/main/metal/MetallumRadianceClipmap.metal`;
- локальные capture images/metadata и benchmark artifacts.

Следствие: это не удалённая ветка и не отменённый commit. Это сохранённый
незакоммиченный эксперимент. До selective extraction его worktree является
единственной копией L7 source.

На момент аудита текущий `reflections` checkout содержал отдельный dirty WIP с
planar reflections и god rays. Последующий cleanup удалил весь planar runtime и
его shader/Sodium integration; god rays и прочий независимый WIP этим решением не
затрагиваются.

## 3. Что уже есть в принятом voxel stack

### 3.1. L5 topology и данные

[`VoxelClipmapLayout`](../src/main/java/com/metallum/client/voxel/VoxelClipmapLayout.java)
задаёт dense toroidal clipmap с bounded queues/rings. Balanced topology:

- 64 blocks с subdivision 4×;
- 128 blocks с subdivision 2×;
- 256 blocks с subdivision 1×.

Полезные контракты находятся в `VoxelClipmapLayout.java:7-15, 100-115,
261-295`: фиксированная topology, точный memory budget, world-snapped origins и
full reset при прыжке больше span.

Но payload — не radiance:

- packed occupancy;
- один optical byte на block;
- 4-bit chromatic transmission palette;
- logical brick tags/content stamps.

[`VoxelMaterialDescriptor`](../src/main/java/com/metallum/client/voxel/VoxelMaterialDescriptor.java)
строки 5–50 подтверждает: optical byte содержит material class и quantized
transmittance. В нём нет surface albedo, emission, direct irradiance или outgoing
radiance. `MetallumVoxelOccupancy.metal:5-6,69-156` также описывает L5 как
acceleration/visibility structure.

### 3.2. Что в L5 действительно полезно GI

- `VoxelClipmapController.java:102-140`: world reload/rotation generations.
- `VoxelClipmapController.java:189-218`: публикуются только section snapshots,
  уже принятые Sodium.
- `VoxelClipmapController.java:229-253`: block invalidation ждёт authoritative
  snapshot, а не читает mutable world как renderer truth.
- `VoxelClipmapLayout.java:299-349`: world-snapped origin, staggered scroll и
  full-reset boundary.
- `VoxelOccupancyGpuResources.java:351-445`: validated bounded upload/application.
- `MetallumVoxelOccupancy.metal:69-157`: GPU patch kernel с logical/content tag
  checks.
- `VoxelShadowCacheMirror.java:43-124`: accepted-native publication model и
  controlled fallback gap.

Эти механизмы следует повторно использовать как lifecycle и geometry truth.
Расширять существующий optical ABI новым смыслом нельзя.

### 3.3. L3/L4/L6 границы

- L3 static registry camera-independent, имеет stable source IDs и bounded
  snapshot. GI нужен registry-level static source set, а не view-compacted direct
  frame list. Текущий dynamic collector использует camera/frustum admission;
  поэтому dynamic GI требует отдельного bounded world-space collector и не может
  автоматически переиспользовать current frame snapshot.
- L4 уже предоставляет sun direction/radiance, sky/ambient и environment reset
  reasons.
- L6 хранит visibility/transmittance относительно конкретных lights. Он не
  хранит incoming/outgoing world radiance и не может быть переименован в GI.
- Production compiler намеренно не оставляет raw L5/DDA slots в обычном L6
  fragment path (`MetalCrossShaderCompiler.java:671-681`). Это полезная защита,
  которую GI не должен отменять.

## 4. Найденный L7 worktree

### 4.1. Реальная topology

`RadianceClipmapLayout.java:5-36` в L7 worktree задаёт:

- три каскада `64^3`;
- 1/2/4 blocks на cell;
- охваты 64/128/256 blocks;
- полную mip chain `64^3 ... 1^3`.

Native prototype выделяет radiance `RGBA16Float` и coverage textures, строит
coverage-aware mips и содержит frozen directional-probe compute. Тесты проверяют
фактическое Metal allocation, mips, one-shot debug readback и steady zero-work
counters.

### 4.2. Почему это не GI

`RadianceAppearanceModel.java:181-196` вычисляет:

```text
diffuseIrradiance = skyLight / 15 + 0.85 * blockLight / 15
radiance = albedo * diffuseIrradiance + emission
```

`SodiumRadianceSectionExtractor.java:120-184` берёт vanilla SKY/BLOCK brightness
у voxel и соседей, затем записывает этот pre-lit цвет в поле. Здесь нет
transport между поверхностями, visibility-integrated bounce или сохранённой
directional incoming radiance.

Это полезный diagnostic appearance field и источник тестов, но не «честный GI».
Перенос estimator без изменения семантики создал бы видимость GI, повторяя уже
вычисленную Minecraft lightmap.

### 4.3. Что доказали capture artifacts

Локальная metadata
`ЗАХВАТ 3/radiance_live_v3_20260821_142648_metadata.json` сообщает:

- `fromAcceptedSodiumLevelSlice=true`;
- world generation 2, Overworld;
- 64/128/256 coverage;
- ненулевые emissive/radiance cells и provenance counters;
- unavailable cells отдельно от known-empty.

Это `SUPPORTED` доказательство, что prototype умеет заполнять coarse world field
из accepted Sodium data. Оно не доказывает GI, отражения, художественное качество
или production performance.

## 5. Результаты старых reflection receivers

Источники: L7 worktree `OptimizationHistory.md:2112-2364` и соответствующие
`run/logs/metallum-benchmarks/*l7*` artifacts. Все числа ниже — Tier B/dry
screening. Они `SUPPORTED` как причина остановить prototype, но не являются Tier
C production FPS claim.

| Кандидат | Фактический path | Результат M1 Pro | Решение |
| --- | --- | --- | --- |
| Six-step radiance receiver | 6 steps, 12–24 texture reads в L8-gated fragment | whole GPU p95 `+2.163 ms` / `+4.0716%`; `WORLD_OPAQUE +2.108 ms` | `REJECTED`, hard stop |
| Metal visible function | Cone body вынесен из caller в function table | whole p95 `+7.579 ms` / `+13.4194%`; `WORLD_OPAQUE +7.606 ms` | `REJECTED`, terminal |
| Frozen directional probe | Один radiance + один moment 3D sample | whole p95 `+3.024 ms` / `+5.3687%`; `WORLD_OPAQUE +2.819 ms` | `REJECTED_DRY_PERFORMANCE_GATE` |
| Dedicated sparse reflective pass | Отдельный draw только reflective surfaces | Valid bounded candidate PSO/draw не построен | `SPARSE_PASS_UNSUPPORTED` |

Важные детали измерений:

- Для первых трёх A/B использовались одинаковые frozen fields и compile-time OFF
  плечи; selected windows имели Advanced admission, ноль pipeline failures,
  timestamp drops, steady allocations и GPU-to-CPU copies.
- Six-step source field занимал фактически 10.078 MiB GPU; полный tracked contract
  prototype — 20.828 MiB.
- Directional probe добавлял 0.688 MiB native Metal allocation; полный tracked
  contract — 21.516 MiB, ниже экспериментального 24 MiB cap.
- Малый размер field не спас receiver: bottleneck оказался в common terrain
  shader path.

### 5.1. Почему sparse pass не готов

Sodium 0.9.1 сохраняет `SOLID`, `CUTOUT`, `TRANSLUCENT`, но не отдельный water
submesh после build/sort. Без второго mesh нельзя безопасно replay только water.
Второй mesh потребовал бы новый builder/output/upload/storage path и отдельный
fixed-cap allocator.

Аудит L7 оценил только vertex data для минимального water field 256×256 blocks в
5,242,880 bytes. Это уже больше оставшегося 2,605,008-byte headroom старого 24
MiB prototype до CPU buffer, indices, metadata и arena padding. Поэтому
дублирование всей translucent/terrain geometry не является допустимым обходом.

## 6. Что полезно восстановить

В отдельной чистой ветке Stage G1 стоит выборочно извлечь:

1. `RadianceClipmapLayout` — после пересмотра edge/spacing под diffuse probes.
2. Generation/reset и bounded candidate accounting из `RadianceRuntimeStorage`.
3. `RadianceMemoryAuditor` и тест независимости steady memory от числа
   публикаций.
4. Java–Swift resource ownership из `RadianceGpuResources`/native context.
5. Coverage-aware 3D mip kernel и его numerical tests.
6. One-shot capture/provenance debug plumbing.
7. Tests на actual Metal allocations, empty/unknown coverage и zero steady work.

Это не готовый patch set. Каждый элемент нужно отделить от остальных 18 tracked
правок и нового native ABI, затем проверить заново на текущей dirty
`reflections` архитектуре.

## 7. Что не восстанавливать

- `RadianceAppearanceModel` и pre-lit extractor как shipping GI truth.
- Six-step fragment cone trace, любой другой per-fragment L5/L7 DDA или попытку
  «чуть уменьшить steps» без новой архитектуры.
- Visible-function receiver/table.
- Двухтекстурный directional-probe receiver в common terrain fragment.
- Terrain-side texture slots/bindings 23–28, которые prototype уже удалил.
- Unbounded duplicated water/translucent geometry.
- CPU replay/readback поля в frame loop.

Отдельная историческая причина не возвращать fragment DDA находится в текущем
`OptimizationHistory.md:395-407`: reachable 96-step traversal снижал короткий
прогон до 53.42 FPS даже с двумя DDA lights. Это warning, а не Tier C GI baseline.

## 8. Текущее состояние отражений в `reflections`

### 8.1. Принятые L8 analytic terms

Commits `c918ee8` и `5b23655` достижимы из текущего HEAD. Они дают water sky/sun
environment terms, но не отражают локальную/off-screen геометрию. Это полезный
стабильный fallback, не voxel reflection.

### 8.2. Planar reflection cleanup

После этого аудита весь незакоммиченный planar runtime был удалён из основного
checkout: offscreen target, reflected Sodium lists/batch cache, mixins, shader ABI,
water sampling, настройки и тест. Это не затрагивает принятый L8 analytic fallback
выше и не является источником GI или voxel reflections.

## 9. Решение по переносу в текущем аудите

Source recovery не выполнен по четырём причинам:

1. L7 — dirty WIP, а не атомарный commit; автоматического безопасного cherry-pick
   нет.
2. Reusable infrastructure смешана с тремя rejected receivers и большой
   Java–FFM–Swift ABI правкой.
3. Источник radiance использует vanilla lightmap и должен быть семантически
   разделён до появления в GI.
4. Текущий `reflections` checkout уже содержит unrelated planar/god-rays WIP;
   большой перенос нарушил бы ownership и усложнил проверку.

Практический следующий шаг — G1 из основного плана: создать отдельную чистую
ветку/worktree, перенести только field/lifecycle/mip/tests, доказать нулевое
влияние на production shader и сделать focused commit. Исходный L7 worktree до
этого нельзя удалять или prune: он содержит единственную незакоммиченную копию
эксперимента и benchmark receipts.
