# GI: диагноз и план проверки видимого отскока для GPT-5.6 Terra

Дата: 2026-09-07. Это передача задачи, не новый канонический GI-контракт и не акт приёмки.

## Вывод

В Metallum реализована цепочка источники → поле прямого света → однократный перенос → terrain receiver. G6 действительно подключает её к рендеру. Однако утверждение «видимое непрямое освещение работает в обычном мире» имеющимися материалами не закрыто.

Найден более сильный отрицательный результат, чем субъективно слабая картинка: реальный запуск от 1 сентября сообщил READY и точное подключение G6, затем диагностический GPU readback вернул нулевые SH, confidence и coverage в ближнем каскаде C0 у белого приёмника и красного отражателя. Тот же тест получил ненулевой свет в C1/C2. Это локализует проблему до получения полезного ближнего поля, а не даёт основания начинать с усиления цвета/яркости в финальном шейдере.

Точный дефект, породивший исторические нули, ещё не установлен. Текущая незакоммиченная версия содержит существенные изменения топологии и переноса; старый провал нельзя автоматически переносить на неё. Сначала нужно исправить обнаруженные ошибки диагностического критерия и выполнить уже существующий сквозной тест на одной зафиксированной сборке.

## Что именно проверялось

- Исходный HEAD: `8406fb5cbeee`, ветка `ForksHunting`; анализировалось рабочее дерево, включая существовавшие незакоммиченные изменения GI и несвязанные изменения рендера. Чистый checkout HEAD не эквивалентен этой реализации.
- Прочитаны источники Java/Swift/Metal, текущие stage contracts, retained evidence и игровые логи. Три агента GPT-5.6 Terra независимо проверяли источники/transport, receiver и native plumbing.
- В этой задаче Minecraft, новый GPU capture и полный build/check не запускались. Изменений реализации не делалось.
- Успешно выполнены существующие `gi_g3_contract.py`, `gi_g4_contract.py`, `gi_g5_contract.py`, `gi_g6_topology_successor_contract.py`. Это проверки контрактов, не доказательство ненулевого GI в изображении.
- Файл `run/config/metallum-renderer.properties` на момент чтения содержал `improvedLighting=true`, `globalIllumination=off`. Последний `run/logs/latest.log` относился к тесту отражений от 4 сентября, с GI mixin gate `requested=false`. Это не устанавливает настройку пользовательской сборки в другом лаунчере. Место пользовательского запуска пока не подтверждено.

## Где проходит свет

| Участок | Фактическая роль | Чего его PASS не доказывает |
| --- | --- | --- |
| G2 semantic capture | Материал, отражательная способность, emission, геометрия и экспонированные грани | Что GPU G3 получил тот же payload и координаты |
| G3 direct source | Sun/sky из L4 AIR descriptor, статические/динамические источники, линейное прямое поле | Что есть ненулевой отражённый свет на другом блоке |
| G4 / live G6 transport | `rho/pi * E_direct`, один перенос по ограниченной решётке направлений, RGB L1 SH | Что данный видимый texel валиден или что его прочитал terrain vertex |
| G5 / live G6 receiver | Выбор каскадов в vertex shader, точная нормаль из Sodium carrier | Что конкретная видимая поверхность получила ненулевой varying |
| Fragment | При положительном confidence добавляет непрямой член к ambient fallback, затем общий diffuse умножается на albedo и делится на pi | Визуальную различимость и правильную пространственную форму эффекта |

Основные точки входа: `src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java`, `src/main/java/com/metallum/client/gi/live/GiLiveCoordinator.java`, `src/main/metal/MetallumGiField.metal`, `src/main/metal/MetallumGiTransport.metal`, `src/main/java/com/metallum/client/gi/live/GiLiveReceiverShaderPatcher.java`, `src/main/native/MetallumNative.swift`.

## Находки и степень уверенности

### 1. Старый G6 PASS не закрывает текущий видимый bounce

`benchmark/gi/g6-live-evidence-v1.json` содержит `PASS_LIVE_DYNAMIC_GI` и привязан к старым source/artifact digest. Проверялись обновления, маски, binding, latency и отсутствие грубых артефактов. Тёплое освещение рядом с факелом само по себе не отделяет прямой L3-свет от GI.

`docs/GLOBAL_ILLUMINATION_PLAN.md:7-17` прямо оставляет визуальную приёмку G5 и G7 открытой. Текущий `benchmark/gi/g6-topology-successor-contract-v1.json` имеет статус `IMPLEMENTED_PENDING_RUNTIME_EVIDENCE`; его contract tool специально отвергает преждевременное утверждение приёмки (`tools/gi_g6_topology_successor_contract.py:99-101`).

Последние найденные GI visual-probe transcripts от 3 и 7 сентября заканчиваются после preflight/восстановления настроек. Их REQUEST и preflight PASS не являются новым запуском с GPU_FIELD, четырьмя кадрами и COMPLETE.

### 2. Подтверждён исторический провал ближнего поля при READY

Источник:

`run/logs/metallum-benchmarks/20260901T045905Z-gb9f81022eec2-dirty-gi-g6-gpu-field-v13-off.minecraft.log`

SHA-256: `26bd9eebde0f24c13193c2200432f52a7237da8079fd291bbe5837ce5fd43e19`.

- Строка 187: `GI_G6_ADMISSION ... ready_mask=7 ... stale=0 rejected=0 status=PASS`.
- Строка 208: CPU_CHAIN видит выбранный факел, красный и белый `KNOWN_CONTENT`, промежуточный `KNOWN_EMPTY`.
- Строка 209: `GI_VISUAL_PROBE_READY ... measured_frame=329 ... exact_bind=true all_cascades=true status=PASS`.
- Строка 210: `EVENT=FAIL reason=GI visual probe G6 GPU field probe did not prove sampleable colored bounce`.

| GPU sample | Результат |
| --- | --- |
| C0 white `(82,76,-110)`, `(82,77,-110)`, `(82,78,-110)` | Все SH RGB = 0, confidence=0, coverage=0 |
| C0 red `(84,77,-110)` | Все SH RGB = 0, confidence=0, coverage=0 |
| C0 baffle `(81,76,-111)` | Все SH RGB = 0, confidence=0, coverage=0 |
| C1 white | L0 RGB ≈ `(0.044800, 0.011223, 0.002457)`, confidence=237, coverage=255 |
| C2 white | L0 RGB ≈ `(0.002798, 0.000401, 0.000027)`, confidence=148, coverage=255 |

Глобальная готовность означает завершённые кирпичи, а не полезный свет в каждом texel. В текущем live shader invalid semantic receiver или G3 geometry != CONTENT обнуляют все четыре выхода (`MetallumGiTransport.metal:506-516`). При допустимой поверхности конец kernel пишет coverage=1 даже при нулевой энергии (`:594-598`). Поэтому простая слабость источника не объясняет весь набор нулей.

Возможные причины: неверная G3 geometry на GPU; иной staged semantic payload G6; несовпадение координат/epoch; очищенный или не записанный участок atlas; ошибочная адресация самого probe. CPU_CHAIN не проверяет все эти переходы. По одному логу нельзя выбрать единственную причину.

Наиболее предметный кандидат найден в native staging: прежний код копировал semantic cells в единственный общий `sharedStaging.cells` только при `preparing`. После подготовки другого каскада продолжение C0 могло прочитать его semantic cells вместе с собственными C0 direct/geometry textures. Текущий dirty diff добавляет `stagedSemanticCascade`/`stagedSemanticFieldGeneration` и повторную загрузку при смене владельца буфера (`MetallumNative.swift:19372-19374,19543-19547`). Это конкретный дефект прежней логики при чередовании каскадов и правдоподобное объяснение нулевого C0; выполнение именно этого сценария в историческом dirty запуске ещё не доказано. В существовавшем `v11-staging-fix` названии запуска тоже нельзя видеть подтверждение успеха: более поздний v13 завершился FAIL.

Текущий source-chain тест проверяет наличие restaging в тексте, но не исполняет C0 → C1 → C2 → непервый batch C0 с различными semantic payload на GPU. Нужен такой регрессионный тест с проверкой actual C0 output. При обзоре Java/Swift/Metal не найдено отдельного подтверждённого несовпадения размеров G6 header/cell/stats ABI.

### 3. Топология C0 менялась; исправление в коде ещё не равно исправлению в игре

В текущем дереве `GiFieldLayout.java:14-20` задаёт физические ячейки `[1,4,8]` блоков и snap origins `[2,4,8]`. Старый вариант использовал двухблочный C0. Его бинарная геометрия способна объединить пол и воздух над ним в одну занятую ячейку и перекрыть короткий bounce. C1/C2 по-прежнему грубые.

В dirty diff есть согласующие изменения semantic capture, CPU packets, native params, G3 injection и receiver. В частности, `MetallumGiField.metal:378-392` теперь использует C0=1; прежний текст использовал C0=2. Неполная миграция этой цепочки даёт неверные расстояния, source cell и адреса светового поля.

Не утверждать, что конкретный запуск 1 сентября исполнял G3 с C0=2: он был dirty. Его mapper уже выдавал `82 - origin68 = local14`, то есть C0=1. `git show b9f8102` не восстанавливает dirty shader того запуска. Согласованность текущего emitted/bundled Metal с Java и native нужно доказать заново.

Отдельно в dirty diff уже исправляется попадание округлённого DDA в ячейку самого источника до последнего шага (`MetallumGiField.metal:271-284`). Это реальный класс потерь прямой энергии от диагонально расположенного факела, но нельзя повторно реализовывать готовую правку или объявлять её причиной всех нулей coverage.

### 4. В новом G3 GPU probe есть конкретные ошибки проверки

Эти ошибки относятся к диагностике; сами по себе они не выключают GI при обычной игре.

`MetalFxBenchmarkController.java:1800-1823` в `visualProbeGpuDirectProbePasses` требует `empty.geometryState()==0` и лишь `!=0` у стен. GPU enum в `MetallumGiField.metal:176-179`: UNKNOWN=0, EMPTY=1, CONTENT=2, FALLBACK=3. Native readback возвращает исходный байт без перекодировки (`MetallumNative.swift:17427-17429`). Корректная известная пустая ячейка поэтому не проходит новый критерий, а UNKNOWN принимается за воздух.

Ещё два пробела:

- Маркер сообщает `white=OCCLUDED`, но predicate не проверяет нулевое прямое освещение белого приёмника. Также он не требует точный CONTENT для стен и не проверяет конечность всех компонентов/alpha.
- `visualProbeGpuDirectProbeMatchesCurrentReceipt` (`:1831-1849`) проверяет положительность captured IDs и отдельно актуальность G6 snapshot, но не равенство captured G3 tuple/origin тому полю, которое потребляет G6. Нативная собственная проверка свежести G3 не заменяет межстадийное совпадение.

До запуска нового probe исправить эти условия и добавить тесты на реальные enum/несовпадающие поколения. Не ослаблять PASS ради существующей картинки.

### 5. Receiver подключён, но его счётчики не измеряют полезное покрытие

В текущем коде нет найденного общего отключения GI в fragment при корректном входе: `GiLiveReceiverShaderPatcher.java:95-121` добавляет ненулевой физический член. При confidence=0 остаётся прежний ambient. Текстуры/params привязываются к vertex stage native encoder.

Однако конкретная вершина получает GI только при безопасном carrier, axis face code, допустимой области и полном sampleable footprint (`GiLiveReceiverShaderPatcher.java:175-196,247-305`), ненулевом coverage и валидных SH (`:312-339`). `field_bindings>0` не доказывает ненулевой GI на выбранной белой грани. Если C0 уже исправлен, следующий шаг — проверить её реальные vertices/varying, затем blending с C1/C2, и лишь потом финальные RGB.

### 6. Sun и локальные источники требуют разных контрольных сцен

Sun/sky поступают через `GiEnvironmentSource.fromDescriptor` из L4 AIR, без camera ambient (`GiEnvironmentSource.java:109-123`). G3 добавляет солнце при открытом пути к границе поля, sky — отдельным лучом +Y (`MetallumGiField.metal:367-375`). UNKNOWN/FALLBACK и занятые клетки блокируют видимость; обход ограничен 32 шагами (`:244-258`). Таким образом отсутствие солнечного bounce может начинаться в conservative geometry/visibility ещё до transport. Отдельного нового солнечного GPU/visual доказательства в этой задаче не получено.

Transport ищет источники по 26 фиксированным направлениям и расстояниям 1..8 ячеек (`MetallumGiTransport.metal:525-567`), с conservative path visibility. Это ограниченный один bounce, не произвольная многократная трассировка мира. При C0=1 осевой радиус — 8 блоков; диагональный достигает `8*sqrt(3)`, но выборка только на дискретной решётке. Непопавший на неё небольшой отражатель может дать нулевой перенос; крупные поверхности и C1/C2 иногда скрывают этот недостаток. Это установленное свойство алгоритма, а его вклад в пользовательскую сцену ещё нужно измерить.

Transport проверяет промежуточные CONTENT-клетки как полные препятствия (`MetallumGiTransport.metal:131-175`). Лучи между центрами ячеек и conservative supercover могут блокировать путь, который между реальными открытыми гранями проходит по воздуху, особенно в углах и грубых каскадах. Проверять это на физически взаимно видимых непараллельных гранях: между строго копланарными идеальными diffuse поверхностями отсутствие переноса само по себе корректно, и его нельзя «исправлять» световой утечкой.

Размещаемые источники не исключены целиком: упавшая сцена уже нашла свой факел среди двух источников. Но G3 ограничивает список 16 источниками на кирпич; `AdvancedLightRegistry.copyStaticSourcesForGi` выбирает их по приоритету (`AdvancedLightRegistry.java:624-670`). В плотной torch/lava сцене проверять drops и ID нужного источника отдельно. Этот лимит не объясняет контролируемый провал с двумя источниками.

## План выполнения для следующего агента Terra

Работай по этапам ниже. Пользователь просит настоящий видимый отскок, особенно от размещаемых источников. Не заканчивай на зелёных unit tests, READY или просто более яркой сцене.

### A. Зафиксировать воспроизводимую основу

1. Прочитать AGENTS.md, benchmarking skill, `docs/BENCHMARKING.md`, relevant `OptimizationHistory.md`; снять git status и source digest. Не терять существующий dirty/untracked GI WIP и несвязанные изменения отражений/UI/MetalFX.
2. Изолированный checkout должен содержать выбранный полный текущий GI candidate, включая untracked dependencies; один HEAD их не содержит. Записать точный patch/manifest или focused commit набора перед A/B. Нельзя переносить всю dirty ветку вслепую.
3. Установить, откуда запускается пользовательский Minecraft и какой именно JAR/native artifact он загрузил; сверить hashes, конфиг и startup gate. `run/config` не считать конфигом всех лаунчеров. Зафиксировать `dynamic`, Advanced и полный restart для ON; OFF отдельным запуском. Старые benchmark файлы не менять.

Выход A: однозначные source/artifact/settings/fixture identity для новой проверки.

### B. Починить измерение до изменения визуального результата

1. Использовать уже написанный G3 direct debug probe, а не создавать второй readback механизм. Исправить enum assertions на точные EMPTY=1 и CONTENT=2; UNKNOWN/FALLBACK должны отвергаться.
2. Проверять конечные значения, direct alpha/support, ненулевой red direct и отсутствие direct на white в физически перекрытой сцене. Численные допуски обосновать FP16/энергией, а не подобрать для PASS.
3. Связать G2 payload, G3 readback и потреблённый G6 source одной идентичностью world/resource/palette/content/static/dynamic/environment/origin, насколько эти поля представлены ABI. Не требовать совпадения несопоставимых счётчиков; явно записывать producer stamp, которым питается consumer.
4. Добавить негативные тесты enum, stale tuple, wrong origin, прямого света на white, нулевого red и NaN/Inf. Дополнить runner-проверку маркером GPU_DIRECT: сейчас shell явно требует GPU_FIELD, а гарантия предыдущего этапа в основном внутри Java controller.
5. Readback оставить benchmark-only, асинхронным и с заранее выделенным ограниченным буфером. Не вводить чтение GPU в production frame loop.

Документировать существующий G3 prerequisite в `docs/GI_VISUAL_PROBE.md`: текущий текст описывает в основном G6 readback и не объясняет новый этап GPU_DIRECT.

Выход B: правильный контрольный payload проходит; UNKNOWN, stale и прямое освещение приёмника не могут выдавать PASS.

### C. Найти первый повреждённый переход и исправить только его

Запустить существующую пару в disposable fixture:

```bash
scripts/run_metal_benchmark.sh --gi-visual-probe off --label gi-bounce-audit-off
scripts/run_metal_benchmark.sh --gi-visual-probe on --label gi-bounce-audit-on
```

Это реальные запуски, не `--preflight-only` и не shell contract test. Использовать текущий rig `red-reflector-occluded-v1` и одинаковую геометрию обеих ветвей.

Проверить семь предусмотренных точек, затем при необходимости минимально расширить диагностику:

| Наблюдение | Следующая работа |
| --- | --- |
| CPU semantic неверен | Capture/resampling/material/face weights, coverage и независимость 1-block C0 от 2-block snap |
| CPU верен, G3 GPU geometry неверна | Java packet/byte offsets, brickId и cascade stride, per-cascade dispatch offsets, staging slot lifetime, geometry publication |
| Geometry верна, red direct=0 | Выбор статического источника, cellSize/source-relative origin, endpoint DDA, blockers, attenuation; sun отдельно |
| G3 geometry/direct верны, G6 coverage=0 | G6 staged transport cells, validity/face weights, atlas clear/remap/build mask, source stamp, command completion; проверить адрес probe |
| G6 coverage>0, SH=0 | Достижимость reflector по transport решётке, visibility, source face support, outgoing energy, quadrature |
| G6 SH правильны, на поверхности эффекта нет | Exact draw/vertex carrier, normal, 8-texel footprint, cascade weights, varying и fragment RGB |

При неоднозначности снять GPU trace диагностического запуска и проверить нужные dispatch/ресурсы через навыки using-gpucapture/using-gpudebug. Не начинать с смены архитектуры, снятия fail-closed защит, увеличения gain, ambient, bloom или HDR exposure.

Для найденной native staging гипотезы до приёмки исправления обязателен исполняемый GPU regression: разные легко различимые semantic поля C0/C1/C2, подготовка C0, подготовка других каскадов, продолжение C0 без флага prepare, затем сравнение его SH/coverage с эталоном. Проверить lifetime общего staging до command completion. Одной проверки строки `stageSemanticCells` недостаточно.

Выход C: на одном актуальном наборе данных известен первый неверный переход, исправлен узкий дефект, повторная пара проходит GPU_DIRECT/GPU_FIELD и завершается COMPLETE с matching capture frames.

### D. Доказать именно отскок от размещаемого света

1. Белый приёмник находится за непрозрачной перегородкой от факела; отражатель получает прямой свет, путь отражатель → приёмник открыт.
2. Сравнить OFF/ON при неизменном факеле, затем black/red/green reflector при неизменной геометрии и экспозиции. Цвет приёмника должен следовать цвету отражателя, а замена на чёрный должна уменьшать непрямой член.
3. Torch absent/placed/removed: лог источника и GPU-поле реагируют, contribution появляется/исчезает за SLA; прямой свет на приёмнике остаётся заблокированным. Проверить напольный и настенный факел, соседний блоковый emitter и lava отдельно, включая source IDs/дедупликацию.
4. Повторить с переносом rig на один блок и иной ориентацией, чтобы не принять единственную удачную lattice-aligned сцену за общую работоспособность. Проверить 6 ориентаций грани, floor/wall/corner и расстояния в/вне области C0.
5. Зафиксировать raw linear HDR GI на приёмнике и одинаковые screenshot ROI. PNG/глобальная яркость не заменяют сырые данные. Четыре статичных кадра не доказывают непрерывную стабильность: отдельно live orbit, шаги, поворот, пересечение cascade boundary.

Выход D: причинно отделённый colored bounce и подтверждение пользователем, что он виден и стабилен в мире.

### E. Отдельно проверить солнечный отскок

Создать освещаемый солнцем цветной reflector и затенённый от прямого солнца белый receiver. Проверить environment direction/RGB, открытый путь на reflector, закрытый direct path на receiver, затем ненулевой G3 → G6 → vertex signal. Сравнить GI OFF/ON и цвет/чёрный материал отражателя. Проверить полдень, низкое солнце, помещение с проёмом, закрытую пещеру и смену погоды. Если виноват 32-step/binary visibility или транспортная решётка, сначала сделать минимальный воспроизводимый тест и оценить отдельный алгоритмический вариант; не увеличивать солнечную энергию вместо исправления видимости.

### F. Проверка и отчёт без повторной ложной приёмки

По фактически изменённому участку запустить CPU/ABI/source-chain и source/bundled GPU validation; существующие имена задач находятся в build.gradle:

```bash
./gradlew giSemanticCpuUnitTest giSemanticGpuEncoderUnitTest \
  giDirectSourceCpuUnitTest giDirectSourceSourceChainUnitTest \
  giDirectSourceGpuValidationSource giDirectSourceGpuValidationBundled \
  giTransportCpuUnitTest giTransportGpuValidationSource giTransportGpuValidationBundled \
  giLiveCpuUnitTest giLiveAbiUnitTest giLiveReceiverSourceChainUnitTest \
  giReceiverCpuUnitTest giReceiverCarrierCoexistenceUnitTest \
  giG6TopologySuccessorContractTest giVisualProbeRunnerContractTest \
  --no-daemon --console=plain
./gradlew check --no-daemon --console=plain
```

После физического/визуального успеха выполнить свежие `--gi-live` torch-toggle и `hdrtest-gi-g6-matrix-v1` на текущем artifact. Следовать documented persistent setting/restart contract. Сохранить новые receipts отдельно от historical G6. Проверить memory cap, SLA и zero stale/incompatible publication.

Если меняется production workload/алгоритм, дать Tier B attribution и затем требуемую Tier C baseline/candidate матрицу с неизменными native HDR, разрешением и quality. Не выдавать diagnostic FPS, preflight или один COMPLETE за release performance. G7 human visual/motion acceptance отмечать отдельно.

Коммитить только проверенные относящиеся к исправлению пути. Финальный отчёт Terra должен содержать: найденный первый неверный переход, точные правки, hashes и команды, GPU-данные до/после, ON/OFF/материальные контроли, пользовательскую визуальную границу, результаты performance и оставшиеся ограничения. Если physics/visual gate не пройден — написать это прямо, не повышать статус G6/G7 по unit tests.
