Как встроить Spatial
1. Разделить два размера
Сейчас Minecraft обычно считает, что размер рендера равен размеру окна.
Нужно ввести:
displayWidth / displayHeight
renderWidth / renderHeight
Например:
display: 3024 × 1964
render:  2268 × 1473
Внутренний размер лучше округлять до кратного 8 или 16.
2. Мир рендерить в уменьшенную FP16-цель
sceneColorLow: RGBA16Float
sceneDepthLow: Depth32Float
Не следует сразу уменьшать вообще весь интерфейс Minecraft. Иначе:
текст станет нечётким;
хотбар будет размыт;
курсор и прицел будут масштабироваться;
GUI scale начнёт вести себя странно.
Идеальная схема:
мир — в render resolution
GUI — в display resolution
Полноценный HDR уже дал тебе FP16-сцену, финальный HDR-композитор и управление CAMetalLayer, поэтому большая часть выходного тракта существует. recomendationsForHDR
3. Создать Spatial scaler один раз
Объект MTLFXSpatialScaler не нужно пересоздавать каждый кадр.
Пересоздание требуется при:
изменении render resolution;
изменении display resolution;
смене формата;
смене Metal device;
включении или выключении режима.
Общая идея Swift-кода:
let descriptor = MTLFXSpatialScalerDescriptor()

descriptor.inputWidth = renderWidth
descriptor.inputHeight = renderHeight
descriptor.outputWidth = displayWidth
descriptor.outputHeight = displayHeight

descriptor.colorTextureFormat = .rgba16Float
descriptor.outputTextureFormat = .rgba16Float

let scaler = descriptor.makeSpatialScaler(device: device)
Это псевдокод: названия и availability следует сверять с текущим SDK.
4. Выход делать в промежуточную FP16-текстуру
Не рекомендую писать MetalFX сразу в drawable:
нежелательно:
MetalFX → drawable
Лучше:
MetalFX
→ upscaledScene RGBA16Float
→ HDR/display mapping
→ GUI
→ drawable
Так сохраняются:
HDR-обработка;
bloom;
отдельный GUI;
будущая генерация кадров;
возможность сделать снимок;
возможность добавить sharpening.
5. Кодировать в том же command buffer
После завершения рендера сцены:
endRenderPass
→ encode MetalFX
→ HDR compositor
→ present
Без CPU-ожидания между этапами.