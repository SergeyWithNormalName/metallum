#include <metal_stdlib>
using namespace metal;

struct HdrVertexOut {
  float4 position [[position]];
  float2 uv;
};

struct HdrExtractUniforms {
  uint sourceEncoding;
  uint semanticAvailable;
  uint2 sourceSize;
  uint histogramEnabled;
  uint _padding0;
};

struct HdrUiBackdropUniforms {
  uint sourceEncoding;
};

struct HdrUiCompareUniforms {
  uint sourceEncoding;
  uint seededUiAvailable;
  uint scaleScene;
  uint _padding0;
};

struct HdrHistogramReduceUniforms {
  float currentHeadroom;
  float deltaTime;
  uint forceReset;
  uint _padding0;
};

struct HdrAdaptiveState {
  float breakpoint;
  float inferredPeak;
  float medianLog2;
  float p90Log2;
  float p99Log2;
  float brightCoverage;
  float currentHeadroom;
  uint valid;
};

// METALLUM scene color already contains physical scene-linear radiance. This
// state therefore controls exposure only; scenePeak is a measured percentile,
// never a reconstructed highlight target.
struct ActualHdrExposureState {
  float exposure;
  float scenePeak;
  float medianLog2;
  float p90Log2;
  float p99Log2;
  float brightCoverage;
  float currentHeadroom;
  uint valid;
};

float3 metallum_hdr_finite_or_zero(float3 value) {
  return select(float3(0.0), value, isfinite(value));
}
float3 metallum_hdr_finite_nonnegative(float3 value) {
  return max(metallum_hdr_finite_or_zero(value), 0.0);
}

vertex HdrVertexOut metallum_hdr_vs(uint vertexId [[vertex_id]]) {
  const float2 positions[3] = {
    float2(-1.0,  1.0),
    float2( 3.0,  1.0),
    float2(-1.0, -3.0)
  };
  const float2 uvs[3] = {
    float2(0.0,  1.0),
    float2(2.0,  1.0),
    float2(0.0, -1.0)
  };
  HdrVertexOut out;
  out.position = float4(positions[vertexId], 0.0, 1.0);
  out.uv = uvs[vertexId];
  return out;
}

float3 metallum_hdr_srgb_to_linear(float3 encoded, bool extendedRange) {
  encoded = metallum_hdr_finite_or_zero(encoded);
  float3 magnitude = extendedRange ? abs(encoded) : clamp(encoded, 0.0, 1.0);
  float3 low = magnitude / 12.92;
  float3 high = pow((magnitude + 0.055) / 1.055, float3(2.4));
  float3 decoded = select(high, low, magnitude <= float3(0.04045));
  return extendedRange ? copysign(decoded, encoded) : decoded;
}

float3 metallum_hdr_decode(float3 value, uint sourceEncoding) {
  value = metallum_hdr_finite_or_zero(value);
  if (sourceEncoding == 0u) {
    return metallum_hdr_srgb_to_linear(value, false);
  }
  if (sourceEncoding == 1u) {
    return max(metallum_hdr_srgb_to_linear(value, true), 0.0);
  }
  return max(value, 0.0);
}

float3 metallum_hdr_linear_to_srgb(float3 linearValue) {
  linearValue = metallum_hdr_finite_or_zero(linearValue);
  float3 bounded = clamp(linearValue, 0.0, 1.0);
  float3 low = bounded * 12.92;
  float3 high = 1.055 * pow(bounded, float3(1.0 / 2.4)) - 0.055;
  return select(high, low, bounded <= float3(0.0031308));
}

float3 metallum_hdr_sdr_encoded_appearance(float3 value, uint sourceEncoding) {
  // SRGB and extended-SRGB scene values are already display encoded. A
  // linear source needs an explicit bounded transfer into the RGBA8 UI
  // target so the seeded backdrop represents the same SDR appearance.
  return sourceEncoding == 2u
    ? metallum_hdr_linear_to_srgb(value)
    : clamp(value, 0.0, 1.0);
}

float3 metallum_hdr_quantize_unorm8(float3 value) {
  return floor(clamp(value, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
}

float metallum_hdr_luminance(float3 color) {
  return dot(metallum_hdr_finite_nonnegative(color), float3(0.2126, 0.7152, 0.0722));
}

fragment float4 metallum_hdr_extract_fs(
  HdrVertexOut in [[stage_in]],
  texture2d<float> scene [[texture(0)]],
  texture2d<float> semantic [[texture(1)]],
  depth2d<float> sceneDepth [[texture(2)]],
  constant HdrExtractUniforms& uniforms [[buffer(0)]],
  device atomic_uint* histogram [[buffer(1)]]
) {
  uint2 origin = uint2(in.position.xy) * 4u;
  uint2 maximumCoordinate = max(uniforms.sourceSize, uint2(1u)) - 1u;
  float3 semanticBloomSum = float3(0.0);
  float averageY = 0.0;
  float semanticStrength = 0.0;

  for (uint yIndex = 0u; yIndex < 4u; ++yIndex) {
    for (uint xIndex = 0u; xIndex < 4u; ++xIndex) {
      uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
      float4 encodedSample = scene.read(coordinate);
      float3 color = metallum_hdr_decode(encodedSample.rgb, uniforms.sourceEncoding);
      float y = metallum_hdr_luminance(color);
      averageY += y;

      if (uniforms.semanticAvailable != 0u) {
        uint4 semanticBytes = uint4(round(clamp(semantic.read(coordinate), 0.0, 1.0) * 255.0));
        uint code = semanticBytes.x;
        uint strengthCode = code & 127u;
        if (strengthCode != 0u) {
          uint markerPackedDepth = semanticBytes.y
            | (semanticBytes.z << 8u)
            | (semanticBytes.w << 16u);
          uint scenePackedDepth = uint(round(
            clamp(sceneDepth.read(coordinate), 0.0, 1.0) * 16777215.0
          ));
          // Minecraft 26.2 uses reversed-Z. A semantic fragment may be
          // nearer than the stored scene depth when translucent terrain
          // was rendered through an offscreen target, but it must not be
          // clearly behind a later opaque fragment.
          if (markerPackedDepth + 2u >= scenePackedDepth) {
            float candidateStrength = float(strengthCode) / 127.0;
            float candidateExact = (code & 128u) != 0u ? 1.0 : 0.0;
            float candidateBloomGain = candidateStrength
              * mix(0.20, 0.42, candidateExact);
            semanticBloomSum += max(color, 0.0) * candidateBloomGain;
            semanticStrength = max(semanticStrength, candidateStrength);
          }
        }
      }
    }
  }
  averageY *= 1.0 / 16.0;

  // Each quarter-resolution fragment contributes exactly one sample.
  // Source-authored semantic emitters are excluded so they do not teach
  // the generic scene reconstruction to boost themselves a second time.
  if (uniforms.histogramEnabled != 0u && semanticStrength <= 0.0) {
    float logY = clamp(log2(max(averageY, exp2(-12.0))), -12.0, 4.0);
    uint bin = min(uint((logY + 12.0) * 4.0), 63u);
    atomic_fetch_add_explicit(&histogram[bin], 1u, memory_order_relaxed);
  }

  if (uniforms.semanticAvailable != 0u) {
    return float4(
      semanticBloomSum * (1.0 / 16.0),
      averageY
    );
  }
  // Generic scene reconstruction now handles non-semantic highlights.
  // Keeping the old visual fallback would apply two unrelated heuristics
  // to the same pixel and create excessive halos.
  return float4(0.0, 0.0, 0.0, averageY);
}

fragment float4 metallum_actual_hdr_extract_fs(
  HdrVertexOut in [[stage_in]],
  texture2d<float> scene [[texture(0)]],
  constant HdrExtractUniforms& uniforms [[buffer(0)]],
  device atomic_uint* histogram [[buffer(1)]]
) {
  uint2 origin = uint2(in.position.xy) * 4u;
  uint2 maximumCoordinate = max(uniforms.sourceSize, uint2(1u)) - 1u;
  float3 bloomSum = float3(0.0);
  float averageY = 0.0;

  for (uint yIndex = 0u; yIndex < 4u; ++yIndex) {
    for (uint xIndex = 0u; xIndex < 4u; ++xIndex) {
      uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
      float3 radiance = metallum_hdr_decode(
        scene.read(coordinate).rgb,
        uniforms.sourceEncoding
      );
      averageY += metallum_hdr_luminance(radiance);

      // Reference white is 1.0 in the material contract. Bloom is extracted
      // only from actual over-reference radiance, without semantic markers or
      // an inferred replacement for clipped SDR highlights.
      float3 overReference = max(radiance - 1.0, 0.0);
      float bloomGate = smoothstep(0.0, 0.25, metallum_hdr_luminance(overReference));
      bloomSum += overReference * bloomGate;
    }
  }
  averageY *= 1.0 / 16.0;

  if (uniforms.histogramEnabled != 0u) {
    float logY = clamp(log2(max(averageY, exp2(-12.0))), -12.0, 4.0);
    uint bin = min(uint((logY + 12.0) * 4.0), 63u);
    atomic_fetch_add_explicit(&histogram[bin], 1u, memory_order_relaxed);
  }

  return float4(bloomSum * (1.0 / 16.0), averageY);
}

kernel void metallum_hdr_histogram_build(
  texture2d<float, access::read> scene [[texture(0)]],
  texture2d<float, access::read> semantic [[texture(1)]],
  depth2d<float, access::read> sceneDepth [[texture(2)]],
  constant HdrExtractUniforms& uniforms [[buffer(0)]],
  device atomic_uint* histogram [[buffer(1)]],
  uint2 position [[thread_position_in_grid]]
) {
  uint2 quarterSize = (max(uniforms.sourceSize, uint2(1u)) + 3u) / 4u;
  if (any(position >= quarterSize)) {
    return;
  }
  uint2 origin = position * 4u;
  uint2 maximumCoordinate = max(uniforms.sourceSize, uint2(1u)) - 1u;
  float averageY = 0.0;
  float semanticStrength = 0.0;
  for (uint yIndex = 0u; yIndex < 4u; ++yIndex) {
    for (uint xIndex = 0u; xIndex < 4u; ++xIndex) {
      uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
      float3 color = metallum_hdr_decode(scene.read(coordinate).rgb, uniforms.sourceEncoding);
      averageY += metallum_hdr_luminance(color);
      if (uniforms.semanticAvailable != 0u) {
        uint4 semanticBytes = uint4(round(clamp(semantic.read(coordinate), 0.0, 1.0) * 255.0));
        uint code = semanticBytes.x;
        uint strengthCode = code & 127u;
        if (strengthCode != 0u) {
          uint markerPackedDepth = semanticBytes.y
            | (semanticBytes.z << 8u)
            | (semanticBytes.w << 16u);
          uint scenePackedDepth = uint(round(
            clamp(sceneDepth.read(coordinate), 0.0, 1.0) * 16777215.0
          ));
          if (markerPackedDepth + 2u >= scenePackedDepth) {
            semanticStrength = max(semanticStrength, float(strengthCode) / 127.0);
          }
        }
      }
    }
  }
  averageY *= 1.0 / 16.0;
  if (semanticStrength <= 0.0) {
    float logY = clamp(log2(max(averageY, exp2(-12.0))), -12.0, 4.0);
    uint bin = min(uint((logY + 12.0) * 4.0), 63u);
    atomic_fetch_add_explicit(&histogram[bin], 1u, memory_order_relaxed);
  }
}

float metallum_hdr_temporal_scalar(float current, float target, float deltaTime) {
  float timeConstant = target > current ? 0.75 : 0.12;
  float blend = 1.0 - exp(-max(deltaTime, 0.0) / timeConstant);
  return mix(current, target, clamp(blend, 0.0, 1.0));
}

kernel void metallum_hdr_histogram_reduce(
  device atomic_uint* histogram [[buffer(0)]],
  device HdrAdaptiveState* stateBuffer [[buffer(1)]],
  constant HdrHistogramReduceUniforms& uniforms [[buffer(2)]],
  uint index [[thread_position_in_grid]]
) {
  if (index != 0u) {
    return;
  }

  uint bins[64];
  uint total = 0u;
  uint brightCount = 0u;
  for (uint bin = 0u; bin < 64u; ++bin) {
    uint count = atomic_exchange_explicit(&histogram[bin], 0u, memory_order_relaxed);
    bins[bin] = count;
    total += count;
    // Bin 46 starts at log2(Y)=-0.5 (Y~=0.707), a stable quantized
    // threshold for SDR highlights in this 0.25-stop histogram.
    if (bin >= 46u) {
      brightCount += count;
    }
  }

  HdrAdaptiveState previous = stateBuffer[0];
  float safeHeadroom = clamp(uniforms.currentHeadroom, 1.0, 8.0);
  float maximumInferredPeak = min(safeHeadroom, 3.0);
  if (total == 0u) {
    if (previous.valid == 0u) {
      previous.breakpoint = 0.70;
      previous.inferredPeak = 1.0;
    }
    previous.breakpoint = clamp(previous.breakpoint, 0.34, 0.70);
    previous.inferredPeak = clamp(previous.inferredPeak, 1.0, maximumInferredPeak);
    previous.currentHeadroom = safeHeadroom;
    stateBuffer[0] = previous;
    return;
  }

  uint rank50 = max(uint(ceil(float(total) * 0.50)), 1u);
  uint rank90 = max(uint(ceil(float(total) * 0.90)), 1u);
  uint rank99 = max(uint(ceil(float(total) * 0.99)), 1u);
  uint cumulative = 0u;
  uint bin50 = 63u;
  uint bin90 = 63u;
  uint bin99 = 63u;
  bool found50 = false;
  bool found90 = false;
  bool found99 = false;
  for (uint bin = 0u; bin < 64u; ++bin) {
    cumulative += bins[bin];
    if (!found50 && cumulative >= rank50) {
      bin50 = bin;
      found50 = true;
    }
    if (!found90 && cumulative >= rank90) {
      bin90 = bin;
      found90 = true;
    }
    if (!found99 && cumulative >= rank99) {
      bin99 = bin;
      found99 = true;
    }
  }

  float p50Log2 = -12.0 + (float(bin50) + 0.5) * 0.25;
  float p90Log2 = -12.0 + (float(bin90) + 0.5) * 0.25;
  float p99Log2 = -12.0 + (float(bin99) + 0.5) * 0.25;
  float p90Y = exp2(p90Log2);
  float p99Y = exp2(p99Log2);
  float brightCoverage = float(brightCount) / max(float(total), 1.0);

  float isolatedPresence = brightCount == 0u
    ? 0.0
    : 1.0 - smoothstep(0.02, 0.08, brightCoverage);
  float upperHighlightSignal = max(
    smoothstep(0.55, 1.0, p99Y),
    isolatedPresence
  );
  float broadHighlightSignal = smoothstep(0.24, 0.50, p90Y);
  float breakpointSignal = max(broadHighlightSignal, 0.5 * upperHighlightSignal);
  float targetBreakpoint = clamp(0.70 - 0.36 * breakpointSignal, 0.34, 0.70);

  // Sparse highlights can approach 92% of the inferred EDR range. Broad
  // outdoor light receives a larger fraction when headroom is scarce, so
  // sky and clouds visibly enter EDR at 1.2x without becoming multi-stop
  // emitters on a high-headroom display. Dense white remains restrained.
  float sparseWeight = 1.0 - smoothstep(0.08, 0.55, brightCoverage);
  float mappingHeadroom = min(safeHeadroom, 3.0);
  float lowHeadroomWeight = exp(-1.5 * max(mappingHeadroom - 1.0, 0.0));
  float denseFraction = mix(0.16, 0.35, lowHeadroomWeight);
  float broadSparseFraction = mix(0.20, 0.92, lowHeadroomWeight);
  float sparseExpansion = upperHighlightSignal
    * mix(denseFraction, 0.92, sparseWeight);
  float broadExpansion = broadHighlightSignal
    * mix(denseFraction, broadSparseFraction, sparseWeight);
  float expansionFraction = max(sparseExpansion, broadExpansion);
  float targetPeak = 1.0
    + (maximumInferredPeak - 1.0) * clamp(expansionFraction, 0.0, 0.92);
  targetPeak = min(targetPeak, maximumInferredPeak);

  bool reset = uniforms.forceReset != 0u
    || previous.valid == 0u
    || uniforms.deltaTime > 1.0
    || abs(p50Log2 - previous.medianLog2) > 2.0;
  float breakpoint = reset
    ? targetBreakpoint
    // A lower breakpoint means more HDR expansion, so invert it while
    // applying the same slow-rise / fast-fall response as inferredPeak.
    : -metallum_hdr_temporal_scalar(
        -previous.breakpoint,
        -targetBreakpoint,
        uniforms.deltaTime
      );
  float inferredPeak = reset
    ? targetPeak
    : metallum_hdr_temporal_scalar(previous.inferredPeak, targetPeak, uniforms.deltaTime);

  HdrAdaptiveState next;
  next.breakpoint = clamp(breakpoint, 0.34, 0.70);
  // This cap is deliberately immediate, independent of temporal fall, so
  // an EDR headroom drop can never leave an over-range frame in flight.
  next.inferredPeak = clamp(inferredPeak, 1.0, maximumInferredPeak);
  next.medianLog2 = p50Log2;
  next.p90Log2 = p90Log2;
  next.p99Log2 = p99Log2;
  next.brightCoverage = clamp(brightCoverage, 0.0, 1.0);
  next.currentHeadroom = safeHeadroom;
  next.valid = 1u;
  stateBuffer[0] = next;
}

kernel void metallum_actual_hdr_exposure_reduce(
  device atomic_uint* histogram [[buffer(0)]],
  device ActualHdrExposureState* stateBuffer [[buffer(1)]],
  constant HdrHistogramReduceUniforms& uniforms [[buffer(2)]],
  uint index [[thread_position_in_grid]]
) {
  if (index != 0u) {
    return;
  }

  uint bins[64];
  uint total = 0u;
  uint brightCount = 0u;
  for (uint bin = 0u; bin < 64u; ++bin) {
    uint count = atomic_exchange_explicit(&histogram[bin], 0u, memory_order_relaxed);
    bins[bin] = count;
    total += count;
    if (bin >= 48u) { // Y >= reference white.
      brightCount += count;
    }
  }

  ActualHdrExposureState previous = stateBuffer[0];
  float safeHeadroom = clamp(uniforms.currentHeadroom, 1.0, 8.0);
  if (total == 0u) {
    previous.exposure = previous.valid == 0u ? 1.0 : clamp(previous.exposure, 0.25, 1.0);
    previous.scenePeak = previous.valid == 0u ? 1.0 : max(previous.scenePeak, 0.0);
    previous.currentHeadroom = safeHeadroom;
    stateBuffer[0] = previous;
    return;
  }

  uint rank50 = max(uint(ceil(float(total) * 0.50)), 1u);
  uint rank90 = max(uint(ceil(float(total) * 0.90)), 1u);
  uint rank99 = max(uint(ceil(float(total) * 0.99)), 1u);
  uint cumulative = 0u;
  uint bin50 = 63u;
  uint bin90 = 63u;
  uint bin99 = 63u;
  bool found50 = false;
  bool found90 = false;
  bool found99 = false;
  for (uint bin = 0u; bin < 64u; ++bin) {
    cumulative += bins[bin];
    if (!found50 && cumulative >= rank50) {
      bin50 = bin;
      found50 = true;
    }
    if (!found90 && cumulative >= rank90) {
      bin90 = bin;
      found90 = true;
    }
    if (!found99 && cumulative >= rank99) {
      bin99 = bin;
      found99 = true;
    }
  }

  float p50Log2 = -12.0 + (float(bin50) + 0.5) * 0.25;
  float p90Log2 = -12.0 + (float(bin90) + 0.5) * 0.25;
  float p99Log2 = -12.0 + (float(bin99) + 0.5) * 0.25;
  float measuredPeak = exp2(p99Log2);

  // Exposure never invents range and never boosts a dim scene. It only
  // attenuates when measured scene radiance would exceed the live EDR budget.
  float targetExposure = min(
    1.0,
    max(0.25, (safeHeadroom * 0.92) / max(measuredPeak, 1.0))
  );
  bool reset = uniforms.forceReset != 0u
    || previous.valid == 0u
    || uniforms.deltaTime > 1.0
    || abs(p50Log2 - previous.medianLog2) > 2.0;
  float exposure = targetExposure;
  if (!reset) {
    // Reduce exposure quickly, recover slowly, and cap immediately after a
    // headroom drop so no over-range frame remains in flight.
    float timeConstant = targetExposure < previous.exposure ? 0.12 : 0.75;
    float blend = 1.0 - exp(-max(uniforms.deltaTime, 0.0) / timeConstant);
    exposure = mix(previous.exposure, targetExposure, clamp(blend, 0.0, 1.0));
  }
  exposure = min(exposure, targetExposure);

  ActualHdrExposureState next;
  next.exposure = clamp(exposure, 0.25, 1.0);
  next.scenePeak = measuredPeak;
  next.medianLog2 = p50Log2;
  next.p90Log2 = p90Log2;
  next.p99Log2 = p99Log2;
  next.brightCoverage = float(brightCount) / max(float(total), 1.0);
  next.currentHeadroom = safeHeadroom;
  next.valid = 1u;
  stateBuffer[0] = next;
}

// One compute dispatch preserves the previous separable 9-tap Gaussian,
// but keeps both the source tile and horizontal FP16 intermediate in
// threadgroup memory. A four-pixel halo lets the vertical stage finish
// without a second texture or command encoder.
constant constexpr uint metallum_hdr_blur_tile_width = 16u;
constant constexpr uint metallum_hdr_blur_tile_height = 16u;
constant constexpr uint metallum_hdr_blur_radius = 4u;
constant constexpr uint metallum_hdr_blur_source_width =
  metallum_hdr_blur_tile_width + 2u * metallum_hdr_blur_radius;
constant constexpr uint metallum_hdr_blur_source_height =
  metallum_hdr_blur_tile_height + 2u * metallum_hdr_blur_radius;
constant constexpr uint metallum_hdr_blur_horizontal_rows =
  metallum_hdr_blur_tile_height + 2u * metallum_hdr_blur_radius;
constant constexpr uint metallum_hdr_blur_thread_width = 16u;
constant constexpr uint metallum_hdr_blur_thread_height = 16u;
constant constexpr float metallum_hdr_blur_weights[5] = {
  0.2270270270,
  0.1945945946,
  0.1216216216,
  0.0540540541,
  0.0162162162
};

kernel void metallum_hdr_blur(
  texture2d<float, access::read> source [[texture(0)]],
  texture2d<float, access::write> destination [[texture(1)]],
  threadgroup half4* sourceTile [[threadgroup(0)]],
  threadgroup half4* horizontalTile [[threadgroup(1)]],
  uint2 localPosition [[thread_position_in_threadgroup]],
  uint2 groupPosition [[threadgroup_position_in_grid]]
) {
  const uint lane = localPosition.y * metallum_hdr_blur_thread_width
    + localPosition.x;
  const uint laneCount = metallum_hdr_blur_thread_width
    * metallum_hdr_blur_thread_height;
  const uint sourceValueCount = metallum_hdr_blur_source_width
    * metallum_hdr_blur_source_height;
  const uint horizontalValueCount = metallum_hdr_blur_tile_width
    * metallum_hdr_blur_horizontal_rows;
  const int maximumX = int(source.get_width()) - 1;
  const int maximumY = int(source.get_height()) - 1;
  const int tileOriginX = int(groupPosition.x * metallum_hdr_blur_tile_width);
  const int tileOriginY = int(groupPosition.y * metallum_hdr_blur_tile_height);

  for (uint index = lane; index < sourceValueCount; index += laneCount) {
    const uint tileX = index % metallum_hdr_blur_source_width;
    const uint tileY = index / metallum_hdr_blur_source_width;
    const int sourceX = clamp(
      tileOriginX + int(tileX) - int(metallum_hdr_blur_radius),
      0,
      maximumX
    );
    const int sourceY = clamp(
      tileOriginY + int(tileY) - int(metallum_hdr_blur_radius),
      0,
      maximumY
    );
    sourceTile[index] = half4(source.read(uint2(sourceX, sourceY)));
  }

  threadgroup_barrier(mem_flags::mem_threadgroup);

  for (uint index = lane; index < horizontalValueCount; index += laneCount) {
    const uint localX = index % metallum_hdr_blur_tile_width;
    const uint haloY = index / metallum_hdr_blur_tile_width;
    const uint sourceCenter = haloY * metallum_hdr_blur_source_width
      + localX + metallum_hdr_blur_radius;
    float4 horizontal = float4(sourceTile[sourceCenter]) * metallum_hdr_blur_weights[0];
    for (uint offset = 1u; offset <= metallum_hdr_blur_radius; ++offset) {
      horizontal += float4(sourceTile[sourceCenter + offset])
        * metallum_hdr_blur_weights[offset];
      horizontal += float4(sourceTile[sourceCenter - offset])
        * metallum_hdr_blur_weights[offset];
    }
    // Match the old RGBA16Float intermediate instead of retaining extra
    // precision that would subtly change the established bloom image.
    horizontalTile[index] = half4(horizontal);
  }

  threadgroup_barrier(mem_flags::mem_threadgroup);

  const uint outputX = groupPosition.x * metallum_hdr_blur_tile_width
    + localPosition.x;
  const uint outputY = groupPosition.y * metallum_hdr_blur_tile_height
    + localPosition.y;
  if (outputX >= destination.get_width() || outputY >= destination.get_height()) {
    return;
  }
  const uint center = (localPosition.y + metallum_hdr_blur_radius)
    * metallum_hdr_blur_tile_width + localPosition.x;
  float4 vertical = float4(horizontalTile[center]) * metallum_hdr_blur_weights[0];
  for (uint offset = 1u; offset <= metallum_hdr_blur_radius; ++offset) {
    vertical += float4(horizontalTile[
      center + offset * metallum_hdr_blur_tile_width
    ]) * metallum_hdr_blur_weights[offset];
    vertical += float4(horizontalTile[
      center - offset * metallum_hdr_blur_tile_width
    ]) * metallum_hdr_blur_weights[offset];
  }
  destination.write(vertical, uint2(outputX, outputY));
}

fragment float4 metallum_hdr_ui_backdrop_fs(
  HdrVertexOut in [[stage_in]],
  texture2d<float> source [[texture(0)]],
  constant HdrUiBackdropUniforms& uniforms [[buffer(0)]]
) {
  uint2 sourceSize = uint2(source.get_width(), source.get_height());
  uint2 maximumCoordinate = max(sourceSize, uint2(1u)) - 1u;
  uint2 coordinate = min(uint2(in.position.xy), maximumCoordinate);
  float3 sourceValue = source.read(coordinate).rgb;
  float3 encoded = metallum_hdr_sdr_encoded_appearance(
    sourceValue,
    uniforms.sourceEncoding
  );
  encoded = metallum_hdr_quantize_unorm8(encoded);
  return float4(encoded, 0.0);
}

fragment float4 metallum_hdr_ui_compare_fs(
  HdrVertexOut in [[stage_in]],
  texture2d<float> finalFrame [[texture(0)]],
  texture2d<float> sceneFrame [[texture(1)]],
  constant HdrUiCompareUniforms& uniforms [[buffer(0)]]
) {
  constexpr sampler smp(coord::normalized, address::clamp_to_edge, filter::linear);
  uint2 sourceSize = uint2(finalFrame.get_width(), finalFrame.get_height());
  uint2 maximumCoordinate = max(sourceSize, uint2(1u)) - 1u;
  uint2 origin = uint2(in.position.xy) * 2u;
  constexpr float residualTolerance = 1.1 / 255.0;
  float hardCoverage = 0.0;
  float dimmingCoverage = 0.0;
  for (uint yIndex = 0u; yIndex < 2u; ++yIndex) {
    for (uint xIndex = 0u; xIndex < 2u; ++xIndex) {
      uint2 coordinate = min(origin + uint2(xIndex, yIndex), maximumCoordinate);
      float4 finalValue = finalFrame.read(coordinate);
      if (uniforms.seededUiAvailable != 0u) {
        float alphaCoverage = clamp(finalValue.a, 0.0, 1.0);
        hardCoverage = max(hardCoverage, alphaCoverage);

        // The seeded target stores ordinary source-over coverage in alpha.
        // Alpha-zero GUI passes need their RGB operation classified:
        // multiplicative darkening (the vanilla vignette) attenuates the
        // HDR delta continuously, while invert/additive changes mask it.
        if (alphaCoverage == 0.0) {
          float2 sceneUv = (float2(coordinate) + 0.5) / float2(sourceSize);
          float3 sceneValue = uniforms.scaleScene != 0u
            ? sceneFrame.sample(smp, sceneUv).rgb
            : sceneFrame.read(coordinate).rgb;
          float3 expectedBackdrop = metallum_hdr_quantize_unorm8(
            metallum_hdr_sdr_encoded_appearance(
              sceneValue,
              uniforms.sourceEncoding
            )
          );
          float3 finalEncoded = clamp(finalValue.rgb, 0.0, 1.0);
          float3 delta = finalEncoded - expectedBackdrop;
          float difference = max(abs(delta.r), max(abs(delta.g), abs(delta.b)));
          if (difference > residualTolerance) {
            bool darkeningOnly = all(finalEncoded <= expectedBackdrop + residualTolerance);
            if (darkeningOnly) {
              float expectedY = metallum_hdr_luminance(
                metallum_hdr_srgb_to_linear(expectedBackdrop, false)
              );
              float finalY = metallum_hdr_luminance(
                metallum_hdr_srgb_to_linear(finalEncoded, false)
              );
              float transmission = expectedY > 1e-7
                ? clamp(finalY / expectedY, 0.0, 1.0)
                : 1.0;
              dimmingCoverage = max(dimmingCoverage, 1.0 - transmission);
            } else {
              hardCoverage = 1.0;
            }
          }
        }
      } else {
        float2 sceneUv = (float2(coordinate) + 0.5) / float2(sourceSize);
        float3 sceneValue = uniforms.scaleScene != 0u
          ? sceneFrame.sample(smp, sceneUv).rgb
          : sceneFrame.read(coordinate).rgb;
        float3 delta = abs(finalValue.rgb - sceneValue);
        float difference = max(delta.r, max(delta.g, delta.b));
        hardCoverage = max(
          hardCoverage,
          smoothstep(0.25 / 255.0, 0.75 / 255.0, difference)
        );
      }
    }
  }
  return float4(hardCoverage, dimmingCoverage, 0.0, 1.0);
}

fragment float4 metallum_hdr_ui_dilate_fs(
  HdrVertexOut in [[stage_in]],
  texture2d<float> source [[texture(0)]]
) {
  uint2 sourceSize = uint2(source.get_width(), source.get_height());
  int2 maximumCoordinate = int2(max(sourceSize, uint2(1u)) - 1u);
  int2 center = int2(in.position.xy);
  float2 centerControl = source.read(uint2(clamp(center, int2(0), maximumCoordinate))).rg;
  float hardCoverage = 0.0;
  for (int yOffset = -1; yOffset <= 1; ++yOffset) {
    for (int xOffset = -1; xOffset <= 1; ++xOffset) {
      uint2 coordinate = uint2(clamp(center + int2(xOffset, yOffset), int2(0), maximumCoordinate));
      hardCoverage = max(hardCoverage, source.read(coordinate).r);
    }
  }
  return float4(hardCoverage, centerControl.g, 0.0, 1.0);
}

// ---------------------------------------------------------------------------
// GOD-RAYS-1 / 1.1 Directional World-Space Visibility Volume Shaders
// ---------------------------------------------------------------------------

struct GodRayVisibilityUniforms {
  float4x4 inverseProjectionMatrix;
  float4x4 viewToWorldMatrix;
  float4x4 worldToViewMatrix;
  float4 cameraWorldPos;
  float4 froxelGridOrigin;
  float4 froxelCellSize;
  uint4 froxelGridDimensions;
  float nearPlane;
  float farPlane;
  float maxVolumetricDistance;
  float csmMaxDistance;
  uint sampleCount;
  uint lowResWidth;
  uint lowResHeight;
  uint debugMode;   // 0 = visibility, 1 = constant, 2 = depth, 3 = shaft_mask, 4 = overlay, 5 = grayscale, 6 = stability, 7 = froxel
  uint metricMode;  // 0 = lit_fraction, 1 = mean_visibility, 2 = shaft_mask
  uint samplingStrategy; // 0 = current_normalized, 1 = world_fixed_step, 2 = world_fixed_step_refined
  float fixedStepLength;
  float intensity;
  float sigmaScattering;
  float sigmaExtinction;
  float anisotropyG;
  float _padding0;
};

struct MetallumEnvironmentShadowV1 {
  float4x4 shadowFromView0;
  float4x4 shadowFromView1;
  float4x4 shadowFromView2;
  float4 directionAndFlags;
  float4 directionalRadiance;
  float4 skyIrradiance;
  float4 ambientRadiance;
  float4 cascadeSplits;
  float4 texelAndBias;
  float4 cascadeBlend;
  uint4 contract;
  float4 worldUpAndMedium;
  float4 cascadeNormalBias;
  float4 materialWeatherAndTime;
  uint4 materialContract;
  float4 cloudOffsetAndGridSize;
  float4 cloudParams;
  float4 cloudShadowFadeAndStrength;
  uint4 cloudContract;
};

static inline float evaluateVisibilityAtPoint(
  float3 posView,
  constant GodRayVisibilityUniforms& uniforms,
  constant MetallumEnvironmentShadowV1& env,
  depth2d<float> shadowCascade0,
  sampler shadowSampler0,
  depth2d<float> shadowCascade1,
  sampler shadowSampler1,
  depth2d<float> shadowCascade2,
  sampler shadowSampler2
) {
  // If shadows are disabled in environment, return 0
  if ((env.contract.w & 1u) == 0u || env.directionAndFlags.w < 0.5) {
    return 0.0;
  }

  uint cascadeCount = clamp(env.contract.y, 1u, 3u);
  float dist = length(posView);

  // Test Cascade 0 (nearest, highest resolution)
  if (dist <= env.cascadeSplits.x) {
    float4 clip0 = env.shadowFromView0 * float4(posView, 1.0);
    if (abs(clip0.w) > 1.0e-6 && isfinite(clip0.x) && isfinite(clip0.y) && isfinite(clip0.z)) {
      float3 ndc0 = clip0.xyz / clip0.w;
      float2 uv0 = ndc0.xy * 0.5 + 0.5;
      if (uv0.x >= 0.001 && uv0.x <= 0.999 && uv0.y >= 0.001 && uv0.y <= 0.999 && ndc0.z >= 0.0 && ndc0.z <= 1.0) {
        float shadowDepth = clamp(ndc0.z + max(env.texelAndBias.y, 0.0001), 0.0, 1.0);
        return shadowCascade0.sample_compare(shadowSampler0, uv0, shadowDepth);
      }
    }
  }

  // Test Cascade 1
  if (dist <= env.cascadeSplits.y && cascadeCount > 1u) {
    float4 clip1 = env.shadowFromView1 * float4(posView, 1.0);
    if (abs(clip1.w) > 1.0e-6 && isfinite(clip1.x) && isfinite(clip1.y) && isfinite(clip1.z)) {
      float3 ndc1 = clip1.xyz / clip1.w;
      float2 uv1 = ndc1.xy * 0.5 + 0.5;
      if (uv1.x >= 0.001 && uv1.x <= 0.999 && uv1.y >= 0.001 && uv1.y <= 0.999 && ndc1.z >= 0.0 && ndc1.z <= 1.0) {
        float shadowDepth = clamp(ndc1.z + max(env.texelAndBias.y, 0.0001), 0.0, 1.0);
        return shadowCascade1.sample_compare(shadowSampler1, uv1, shadowDepth);
      }
    }
  }

  // Test Cascade 2
  if (dist <= env.cascadeSplits.z && cascadeCount > 2u) {
    float4 clip2 = env.shadowFromView2 * float4(posView, 1.0);
    if (abs(clip2.w) > 1.0e-6 && isfinite(clip2.x) && isfinite(clip2.y) && isfinite(clip2.z)) {
      float3 ndc2 = clip2.xyz / clip2.w;
      float2 uv2 = ndc2.xy * 0.5 + 0.5;
      if (uv2.x >= 0.001 && uv2.x <= 0.999 && uv2.y >= 0.001 && uv2.y <= 0.999 && ndc2.z >= 0.0 && ndc2.z <= 1.0) {
        float shadowDepth = clamp(ndc2.z + max(env.texelAndBias.y, 0.0001), 0.0, 1.0);
        return shadowCascade2.sample_compare(shadowSampler2, uv2, shadowDepth);
      }
    }
  }

  return 0.0;
}

static inline float3 godRayFalseColor(float v) {
  v = clamp(v, 0.0, 1.0);
  if (v < 0.25) {
    float t = v / 0.25;
    return mix(float3(1.0, 0.0, 0.0), float3(1.0, 1.0, 0.0), t); // Red to Yellow
  } else if (v < 0.5) {
    float t = (v - 0.25) / 0.25;
    return mix(float3(1.0, 1.0, 0.0), float3(0.0, 1.0, 0.0), t); // Yellow to Green
  } else if (v < 0.75) {
    float t = (v - 0.5) / 0.25;
    return mix(float3(0.0, 1.0, 0.0), float3(0.0, 1.0, 1.0), t); // Green to Cyan
  } else {
    float t = (v - 0.75) / 0.25;
    return mix(float3(0.0, 1.0, 1.0), float3(0.0, 0.0, 1.0), t); // Cyan to Blue
  }
}

fragment float4 metallum_god_ray_visibility_fs(
  HdrVertexOut in [[stage_in]],
  depth2d<float> sceneDepth [[texture(0)]],
  depth2d<float> shadowCascade0 [[texture(13)]],
  depth2d<float> shadowCascade1 [[texture(14)]],
  depth2d<float> shadowCascade2 [[texture(15)]],
  sampler shadowSampler0 [[sampler(13)]],
  sampler shadowSampler1 [[sampler(14)]],
  sampler shadowSampler2 [[sampler(15)]],
  constant GodRayVisibilityUniforms& uniforms [[buffer(0)]],
  constant MetallumEnvironmentShadowV1& env [[buffer(26)]]
) {
  uint2 sceneDepthSize = uint2(sceneDepth.get_width(), sceneDepth.get_height());
  float2 texUv = float2(in.uv.x, 1.0 - in.uv.y);
  uint2 depthCoord = min(uint2(texUv * float2(sceneDepthSize)), max(sceneDepthSize, uint2(1u)) - 1u);
  float rawDepth = sceneDepth.read(depthCoord);

  float ndcX = in.uv.x * 2.0 - 1.0;
  float ndcY = in.uv.y * 2.0 - 1.0;

  float4 nearClip = float4(ndcX, ndcY, 1.0, 1.0);
  float4 nearViewH = uniforms.inverseProjectionMatrix * nearClip;
  float3 nearView = nearViewH.xyz / max(nearViewH.w, 1.0e-7);
  float3 rayDir = normalize(nearView);

  float tStart = max(uniforms.nearPlane, 0.05);
  float tEnd;
  if (rawDepth > 0.0) {
    float4 sceneClip = float4(ndcX, ndcY, rawDepth, 1.0);
    float4 sceneViewH = uniforms.inverseProjectionMatrix * sceneClip;
    float3 sceneView = sceneViewH.xyz / max(sceneViewH.w, 1.0e-7);
    float tScene = length(sceneView);
    tEnd = min(tScene, uniforms.maxVolumetricDistance);
  } else {
    tEnd = min(uniforms.maxVolumetricDistance, uniforms.csmMaxDistance);
  }

  if (tEnd <= tStart) {
    return float4(0.0, 0.0, 0.0, 1.0);
  }

  // Strategy 0: CURRENT_NORMALIZED
  if (uniforms.samplingStrategy == 0u) {
    uint sampleCount = max(uniforms.sampleCount, 1u);
    float totalVis = 0.0;
    float maxVis = 0.0;
    float minVis = 1.0;
    uint litCount = 0u;
    float invN = 1.0 / float(sampleCount);

    for (uint i = 0u; i < sampleCount; ++i) {
      float u = (float(i) + 0.5) * invN;
      float t = mix(tStart, tEnd, u);
      float3 posView = rayDir * t;
      float vis = evaluateVisibilityAtPoint(posView, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);

      totalVis += vis;
      maxVis = max(maxVis, vis);
      minVis = min(minVis, vis);
      if (vis >= 0.5) {
        litCount++;
      }
    }

    float meanVis = clamp(totalVis * invN, 0.0, 1.0);
    float litFraction = clamp(float(litCount) * invN, 0.0, 1.0);
    bool hasLit = maxVis >= 0.5;
    bool hasShadow = minVis < 0.5;
    float shaftMask = (hasLit && hasShadow) ? litFraction : 0.0;

    float outputValue;
    if (uniforms.debugMode == 6u || uniforms.metricMode == 1u) {
      outputValue = meanVis;
    } else if (uniforms.metricMode == 2u || uniforms.debugMode == 3u) {
      outputValue = shaftMask;
    } else {
      outputValue = litFraction;
    }
    return float4(outputValue, 0.0, 0.0, 1.0);
  }

  // Common World-Ray setup for Strategy 1 and 2
  float4 rayDirWorld4 = uniforms.viewToWorldMatrix * float4(rayDir, 0.0);
  float3 rayDirWorld = normalize(rayDirWorld4.xyz);
  float s0 = dot(uniforms.cameraWorldPos.xyz, rayDirWorld);
  float deltaS = max(uniforms.fixedStepLength, 0.05);

  float kFirst = ceil((s0 + tStart) / deltaS);
  float tFirst = kFirst * deltaS - s0;
  if (tFirst < tStart) {
    tFirst += deltaS;
  }

  // Strategy 1: WORLD_FIXED_STEP
  if (uniforms.samplingStrategy == 1u) {
    uint maxSteps = min(uniforms.sampleCount, 128u);
    float totalVis = 0.0;
    float maxVis = 0.0;
    float minVis = 1.0;
    uint stepCount = 0u;
    uint litCount = 0u;

    for (uint i = 0u; i < maxSteps; ++i) {
      float t = tFirst + float(i) * deltaS;
      if (t > tEnd) {
        break;
      }
      float3 posView = rayDir * t;
      float vis = evaluateVisibilityAtPoint(posView, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);

      totalVis += vis;
      maxVis = max(maxVis, vis);
      minVis = min(minVis, vis);
      if (vis >= 0.5) {
        litCount++;
      }
      stepCount++;
    }

    if (stepCount == 0u) {
      return float4(0.0, 0.0, 0.0, 1.0);
    }
    float invSteps = 1.0 / float(stepCount);
    float meanVis = clamp(totalVis * invSteps, 0.0, 1.0);
    float litFraction = clamp(float(litCount) * invSteps, 0.0, 1.0);
    bool hasLit = maxVis >= 0.5;
    bool hasShadow = minVis < 0.5;
    float shaftMask = (hasLit && hasShadow) ? litFraction : 0.0;

    float outputValue;
    if (uniforms.debugMode == 6u || uniforms.metricMode == 1u) {
      outputValue = meanVis;
    } else if (uniforms.metricMode == 2u || uniforms.debugMode == 3u) {
      outputValue = shaftMask;
    } else {
      outputValue = litFraction;
    }
    return float4(outputValue, 0.0, 0.0, 1.0);
  }

  // Strategy 2: WORLD_FIXED_STEP_REFINED (Continuous Lit-Length Integration + 3-step transition search)
  uint maxSteps = min(uniforms.sampleCount, 128u);
  float totalRayLength = max(tEnd - tStart, 1.0e-5);
  float litLength = 0.0;
  float maxVis = 0.0;
  float minVis = 1.0;

  float tPrev = tStart;
  float3 posViewPrev = rayDir * tPrev;
  float visPrev = evaluateVisibilityAtPoint(posViewPrev, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);
  maxVis = max(maxVis, visPrev);
  minVis = min(minVis, visPrev);

  for (uint i = 0u; i <= maxSteps; ++i) {
    float tCurr = (i == 0u) ? tFirst : (tFirst + float(i) * deltaS);
    if (tCurr <= tPrev) {
      continue;
    }
    if (tCurr > tEnd) {
      tCurr = tEnd;
    }
    float intervalLength = tCurr - tPrev;
    if (intervalLength <= 1.0e-5) {
      if (tCurr >= tEnd) break;
      continue;
    }

    float3 posViewCurr = rayDir * tCurr;
    float visCurr = evaluateVisibilityAtPoint(posViewCurr, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);
    maxVis = max(maxVis, visCurr);
    minVis = min(minVis, visCurr);

    bool prevLit = visPrev >= 0.5;
    bool currLit = visCurr >= 0.5;

    if (prevLit && currLit) {
      litLength += intervalLength;
    } else if (!prevLit && !currLit) {
      // Both endpoints shadowed: probe midpoint to preserve narrow 0.5-block apertures
      float tMid = 0.5 * (tPrev + tCurr);
      float3 posViewMid = rayDir * tMid;
      float visMid = evaluateVisibilityAtPoint(posViewMid, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);
      maxVis = max(maxVis, visMid);
      minVis = min(minVis, visMid);
      if (visMid >= 0.5) {
        // Thin lit shaft between two shadow endpoints: 3-step refine both boundaries
        float a1 = tPrev, b1 = tMid;
        for (uint r = 0u; r < 3u; ++r) {
          float m = 0.5 * (a1 + b1);
          float vm = evaluateVisibilityAtPoint(rayDir * m, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);
          if (vm < 0.5) a1 = m; else b1 = m;
        }
        float tEnter = 0.5 * (a1 + b1);

        float a2 = tMid, b2 = tCurr;
        for (uint r = 0u; r < 3u; ++r) {
          float m = 0.5 * (a2 + b2);
          float vm = evaluateVisibilityAtPoint(rayDir * m, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);
          if (vm >= 0.5) a2 = m; else b2 = m;
        }
        float tExit = 0.5 * (a2 + b2);
        litLength += max(tExit - tEnter, 0.0);
      }
    } else {
      // Transition interval: 3-step binary search to find transition boundary
      float a = tPrev;
      float b = tCurr;
      for (uint r = 0u; r < 3u; ++r) {
        float m = 0.5 * (a + b);
        float vm = evaluateVisibilityAtPoint(rayDir * m, uniforms, env, shadowCascade0, shadowSampler0, shadowCascade1, shadowSampler1, shadowCascade2, shadowSampler2);
        if ((vm >= 0.5) == prevLit) {
          a = m;
        } else {
          b = m;
        }
      }
      float tTrans = 0.5 * (a + b);
      if (prevLit) {
        litLength += max(tTrans - tPrev, 0.0);
      } else {
        litLength += max(tCurr - tTrans, 0.0);
      }
    }

    tPrev = tCurr;
    visPrev = visCurr;
    if (tCurr >= tEnd) {
      break;
    }
  }

  float continuousVisibility = clamp(litLength / totalRayLength, 0.0, 1.0);
  bool hasLit = maxVis >= 0.5;
  bool hasShadow = minVis < 0.5;
  float shaftMask = (hasLit && hasShadow) ? continuousVisibility : 0.0;

  float outputValue;
  if (uniforms.metricMode == 2u || uniforms.debugMode == 3u) {
    outputValue = shaftMask;
  } else {
    outputValue = continuousVisibility;
  }
  return float4(outputValue, 0.0, 0.0, 1.0);
}

// Henyey-Greenstein anisotropic single-scattering phase function with isotropic base
static inline float henyeyGreensteinPhase(float cosTheta, float g) {
  float g2 = g * g;
  float denom = 1.0 + g2 - 2.0 * g * cosTheta;
  denom = max(denom, 1.0e-4);
  float hg = (1.0 / (4.0 * M_PI_F)) * ((1.0 - g2) / (denom * sqrt(denom)));
  float iso = 1.0 / (4.0 * M_PI_F);
  return mix(iso, hg, 0.7);
}

// Trilinearly samples the 64x36x32 world froxel volume stored in a 512x144 atlas
static inline float sampleFroxelAtlas(
  float3 worldPos,
  texture2d<float> froxelAtlas,
  constant GodRayVisibilityUniforms& uniforms
) {
  constexpr sampler smpLinear(coord::normalized, address::clamp_to_edge, filter::linear);

  // Continuous voxel coordinate within grid
  float3 relPos = (worldPos - uniforms.froxelGridOrigin.xyz) / max(uniforms.froxelCellSize.xyz, float3(0.01));
  float3 v = relPos - 0.5;

  float maxVx = float(uniforms.froxelGridDimensions.x) - 1.0;
  float maxVy = float(uniforms.froxelGridDimensions.y) - 1.0;
  float maxVz = float(uniforms.froxelGridDimensions.z) - 1.0;

  if (v.x < -0.5 || v.x > maxVx + 0.5
      || v.y < -0.5 || v.y > maxVy + 0.5
      || v.z < -0.5 || v.z > maxVz + 0.5) {
    return 0.0;
  }

  float vzClamped = clamp(v.z, 0.0, maxVz);
  uint k0 = uint(floor(vzClamped));
  uint k1 = min(k0 + 1u, uint(maxVz));
  float fz = vzClamped - float(k0);

  float vxClamped = clamp(v.x, 0.0, maxVx);
  float vyClamped = clamp(v.y, 0.0, maxVy);

  // Clamp sub-texel UV to tile interior [0.5, size - 0.5] to prevent bleeding into adjacent slices
  float tileU = clamp(vxClamped + 0.5, 0.5, 63.5);
  float tileV = clamp(vyClamped + 0.5, 0.5, 35.5);

  // Slice k0 UV in 512x144 atlas
  uint tileX0 = k0 % 8u;
  uint tileY0 = k0 / 8u;
  float2 uv0 = float2(
    (float(tileX0 * 64u) + tileU) / 512.0,
    (float(tileY0 * 36u) + tileV) / 144.0
  );

  // Slice k1 UV in 512x144 atlas
  uint tileX1 = k1 % 8u;
  uint tileY1 = k1 / 8u;
  float2 uv1 = float2(
    (float(tileX1 * 64u) + tileU) / 512.0,
    (float(tileY1 * 36u) + tileV) / 144.0
  );

  float s0 = froxelAtlas.sample(smpLinear, uv0).r;
  float s1 = froxelAtlas.sample(smpLinear, uv1).r;

  return mix(s0, s1, fz);
}

fragment float4 metallum_god_ray_visualize_fs(
  HdrVertexOut in [[stage_in]],
  texture2d<float> visibilityTexture [[texture(0)]],
  depth2d<float> sceneDepth [[texture(1)]],
  constant GodRayVisibilityUniforms& uniforms [[buffer(0)]],
  constant MetallumEnvironmentShadowV1& env [[buffer(26)]]
) {
  constexpr sampler smp(coord::normalized, address::clamp_to_edge, filter::linear);
  // Mode 1: CONSTANT (Full-screen Magenta to guarantee debug presentation path)
  if (uniforms.debugMode == 1u) {
    return float4(1.0, 0.0, 1.0, 1.0);
  }

  // Mode 2: DEPTH (Visualize scene depth aligned to world geometry)
  if (uniforms.debugMode == 2u) {
    uint2 depthSize = uint2(sceneDepth.get_width(), sceneDepth.get_height());
    float2 texUv = float2(in.uv.x, 1.0 - in.uv.y);
    uint2 depthCoord = min(uint2(texUv * float2(depthSize)), max(depthSize, uint2(1u)) - 1u);
    float rawDepth = sceneDepth.read(depthCoord);
    if (rawDepth <= 0.0) {
      // Clear/Sky: Dark Navy Blue
      return float4(0.05, 0.05, 0.25, 1.0);
    }
    float ndcX = in.uv.x * 2.0 - 1.0;
    float ndcY = in.uv.y * 2.0 - 1.0;
    float4 clip = float4(ndcX, ndcY, rawDepth, 1.0);
    float4 viewH = uniforms.inverseProjectionMatrix * clip;
    float3 view = viewH.xyz / max(viewH.w, 1.0e-7);
    float dist = length(view);
    float norm = clamp(dist / 48.0, 0.0, 1.0);
    float g = 1.0 - norm;
    return float4(g, g, g, 1.0);
  }

  // Mode 7: FROXEL / Mode 10: FREEZE_PHYSICAL_CAMERA (Sample 3D World-Space Froxel Volume)
  if (uniforms.debugMode == 7u || uniforms.debugMode == 10u) {
    uint2 depthCoord = min(uint2(in.position.xy), uint2(sceneDepth.get_width() - 1u, sceneDepth.get_height() - 1u));
    float rawDepth = sceneDepth.read(depthCoord);

    float viewW = max(float(sceneDepth.get_width()), 1.0);
    float viewH = max(float(sceneDepth.get_height()), 1.0);
    float ndcX = (in.position.x + 0.5) / viewW * 2.0 - 1.0;
    float ndcY = 1.0 - (in.position.y + 0.5) / viewH * 2.0;

    float4 clipNear = float4(ndcX, ndcY, 0.0, 1.0);
    float4 viewNearH = uniforms.inverseProjectionMatrix * clipNear;
    float3 viewNear = viewNearH.xyz / max(abs(viewNearH.w), 1.0e-7);

    float4 clipFar = float4(ndcX, ndcY, 1.0, 1.0);
    float4 viewFarH = uniforms.inverseProjectionMatrix * clipFar;
    float3 viewFar = viewFarH.xyz / max(abs(viewFarH.w), 1.0e-7);

    float3 rayDirView = normalize(viewFar - viewNear);
    float4 rayDirWorld4 = uniforms.viewToWorldMatrix * float4(rayDirView, 0.0);
    float3 rayDirWorld = normalize(rayDirWorld4.xyz);

    float tStart = max(uniforms.nearPlane, 0.05);
    float tEnd = min(uniforms.maxVolumetricDistance, uniforms.csmMaxDistance);
    if (rawDepth > 0.0001 && rawDepth < 0.9999) {
      float sceneNdcZ = rawDepth * 2.0 - 1.0;
      float4 sceneClip = float4(ndcX, ndcY, sceneNdcZ, 1.0);
      float4 sceneViewH = uniforms.inverseProjectionMatrix * sceneClip;
      if (abs(sceneViewH.w) > 1.0e-6) {
        float3 sceneView = sceneViewH.xyz / sceneViewH.w;
        tEnd = min(length(sceneView), uniforms.maxVolumetricDistance);
      }
    }

    float deltaS = max(uniforms.froxelCellSize.x * 0.5, 0.2);
    float s0 = dot(uniforms.cameraWorldPos.xyz, rayDirWorld);
    float kFirst = ceil((s0 + tStart) / deltaS);
    float tFirst = kFirst * deltaS - s0;
    if (tFirst < tStart) {
      tFirst += deltaS;
    }

    float accumulatedLit = 0.0;
    uint maxSteps = 96u;

    for (uint s = 0u; s < maxSteps; ++s) {
      float t = tFirst + float(s) * deltaS;
      if (t > tEnd) {
        break;
      }
      float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
      float vis = sampleFroxelAtlas(worldPos, visibilityTexture, uniforms);
      accumulatedLit += vis * deltaS;
    }

    float rayLength = max(tEnd - tStart, 0.05);
    float vNorm = clamp(accumulatedLit / rayLength, 0.0, 1.0);
    float vExp = clamp(1.0 - exp(-accumulatedLit * 0.15), 0.0, 1.0);

    float v;
    if (uniforms.metricMode == 0u || uniforms.metricMode == 1u) {
      // Normalized ray visibility: strictly invariant to eye altitude & jumping
      v = vNorm;
    } else {
      v = vExp;
    }
    return float4(v, v, v, 1.0);
  }

  // Mode 8: WORLD_GRID_DEBUG (Visualize 3D world-space froxel grid borders)
  if (uniforms.debugMode == 8u) {
    uint2 depthCoord = min(uint2(in.position.xy), uint2(sceneDepth.get_width() - 1u, sceneDepth.get_height() - 1u));
    float rawDepth = sceneDepth.read(depthCoord);

    float viewW = max(float(sceneDepth.get_width()), 1.0);
    float viewH = max(float(sceneDepth.get_height()), 1.0);
    float ndcX = (in.position.x + 0.5) / viewW * 2.0 - 1.0;
    float ndcY = 1.0 - (in.position.y + 0.5) / viewH * 2.0;

    float4 clipNear = float4(ndcX, ndcY, 0.0, 1.0);
    float4 viewNearH = uniforms.inverseProjectionMatrix * clipNear;
    float3 viewNear = viewNearH.xyz / max(abs(viewNearH.w), 1.0e-7);

    float4 clipFar = float4(ndcX, ndcY, 1.0, 1.0);
    float4 viewFarH = uniforms.inverseProjectionMatrix * clipFar;
    float3 viewFar = viewFarH.xyz / max(abs(viewFarH.w), 1.0e-7);

    float3 rayDirView = normalize(viewFar - viewNear);
    float4 rayDirWorld4 = uniforms.viewToWorldMatrix * float4(rayDirView, 0.0);
    float3 rayDirWorld = normalize(rayDirWorld4.xyz);

    float tStart = max(uniforms.nearPlane, 0.05);
    float tEnd = min(uniforms.maxVolumetricDistance, uniforms.csmMaxDistance);
    if (rawDepth > 0.0001 && rawDepth < 0.9999) {
      float sceneNdcZ = rawDepth * 2.0 - 1.0;
      float4 sceneClip = float4(ndcX, ndcY, sceneNdcZ, 1.0);
      float4 sceneViewH = uniforms.inverseProjectionMatrix * sceneClip;
      if (abs(sceneViewH.w) > 1.0e-6) {
        float3 sceneView = sceneViewH.xyz / sceneViewH.w;
        tEnd = min(length(sceneView), uniforms.maxVolumetricDistance);
      }
    }

    float deltaS = max(uniforms.froxelCellSize.x * 0.5, 0.2);
    float s0 = dot(uniforms.cameraWorldPos.xyz, rayDirWorld);
    float kFirst = ceil((s0 + tStart) / deltaS);
    float tFirst = kFirst * deltaS - s0;
    if (tFirst < tStart) {
      tFirst += deltaS;
    }

    float gridAccum = 0.0;
    uint maxSteps = 96u;

    for (uint s = 0u; s < maxSteps; ++s) {
      float t = tFirst + float(s) * deltaS;
      if (t > tEnd) break;
      float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
      float3 cellFract = abs(fract(worldPos / max(uniforms.froxelCellSize.x, 0.01)) - 0.5);
      float edgeDist = 0.5 - max(cellFract.x, max(cellFract.y, cellFract.z));
      if (edgeDist < 0.06) {
        gridAccum += 0.2;
      }
    }

    float sceneTone = (rawDepth > 0.0001 && rawDepth < 0.9999) ? 0.2 : 0.05;
    float3 col = mix(float3(sceneTone), float3(0.0, 1.0, 0.4), clamp(gridAccum, 0.0, 1.0));
    return float4(col, 1.0);
  }

  // Mode 9: CAMERA_MATRIX_DEBUG (Visualize world coordinates and ray directions)
  if (uniforms.debugMode == 9u) {
    uint2 depthCoord = min(uint2(in.position.xy), uint2(sceneDepth.get_width() - 1u, sceneDepth.get_height() - 1u));
    float rawDepth = sceneDepth.read(depthCoord);

    float viewW = max(float(sceneDepth.get_width()), 1.0);
    float viewH = max(float(sceneDepth.get_height()), 1.0);
    float ndcX = (in.position.x + 0.5) / viewW * 2.0 - 1.0;
    float ndcY = 1.0 - (in.position.y + 0.5) / viewH * 2.0;
    if (rawDepth > 0.0001 && rawDepth < 0.9999) {
      float sceneNdcZ = rawDepth * 2.0 - 1.0;
      float4 sceneClip = float4(ndcX, ndcY, sceneNdcZ, 1.0);
      float4 sceneViewH = uniforms.inverseProjectionMatrix * sceneClip;
      if (abs(sceneViewH.w) > 1.0e-6) {
        float3 sceneView = sceneViewH.xyz / sceneViewH.w;
        float4 sceneWorldRel4 = uniforms.viewToWorldMatrix * float4(sceneView, 0.0);
        float3 worldHit = uniforms.cameraWorldPos.xyz + sceneWorldRel4.xyz;
        // Fractional world coordinates mod 1.0 shows fixed 1-block lattice on surfaces
        float3 worldFract = abs(fract(worldHit) - 0.5) * 2.0;
        return float4(worldFract, 1.0);
      }
    }
    float4 clipFar = float4(ndcX, ndcY, 1.0, 1.0);
    float4 viewFarH = uniforms.inverseProjectionMatrix * clipFar;
    float3 viewFar = viewFarH.xyz / max(abs(viewFarH.w), 1.0e-7);
    float4 rayDirWorld4 = uniforms.viewToWorldMatrix * float4(normalize(viewFar), 0.0);
    float3 rayDirWorld = normalize(rayDirWorld4.xyz);
    return float4(rayDirWorld * 0.5 + 0.5, 1.0);
  }

  // Mode 12: CAMERA_DELTA_DEBUG (Visualize camera altitude, froxel grid origin snap, and world hit point)
  if (uniforms.debugMode == 12u) {
    uint2 depthCoord = min(uint2(in.position.xy), uint2(sceneDepth.get_width() - 1u, sceneDepth.get_height() - 1u));
    float rawDepth = sceneDepth.read(depthCoord);

    float viewW = max(float(sceneDepth.get_width()), 1.0);
    float viewH = max(float(sceneDepth.get_height()), 1.0);
    float ndcX = (in.position.x + 0.5) / viewW * 2.0 - 1.0;
    float ndcY = 1.0 - (in.position.y + 0.5) / viewH * 2.0;

    float rCam = abs(fract(uniforms.cameraWorldPos.y) - 0.5) * 2.0; // Red: Camera vertical position
    float gOrigin = abs(fract(uniforms.froxelGridOrigin.y) - 0.5) * 2.0; // Green: Grid origin

    float bWorld = 0.0;
    if (rawDepth > 0.0001 && rawDepth < 0.9999) {
      float sceneNdcZ = rawDepth * 2.0 - 1.0;
      float4 sceneClip = float4(ndcX, ndcY, sceneNdcZ, 1.0);
      float4 sceneViewH = uniforms.inverseProjectionMatrix * sceneClip;
      if (abs(sceneViewH.w) > 1.0e-6) {
        float3 sceneView = sceneViewH.xyz / sceneViewH.w;
        float4 sceneWorldRel4 = uniforms.viewToWorldMatrix * float4(sceneView, 0.0);
        float3 worldHit = uniforms.cameraWorldPos.xyz + sceneWorldRel4.xyz;
        bWorld = abs(fract(worldHit.y) - 0.5) * 2.0; // Blue: World hit stability
      }
    }
    return float4(rCam, gOrigin, bWorld, 1.0);
  }

  // Mode 13: HDR_PREVIEW & Modes 14..22: GOD_RAY_COMPONENT_DEBUG
  if (uniforms.debugMode == 13u || (uniforms.debugMode >= 14u && uniforms.debugMode <= 22u)) {
    uint2 depthCoord = min(uint2(in.position.xy), uint2(sceneDepth.get_width() - 1u, sceneDepth.get_height() - 1u));
    float rawDepth = sceneDepth.read(depthCoord);

    float viewW = max(float(sceneDepth.get_width()), 1.0);
    float viewH = max(float(sceneDepth.get_height()), 1.0);
    float ndcX = (in.position.x + 0.5) / viewW * 2.0 - 1.0;
    float ndcY = 1.0 - (in.position.y + 0.5) / viewH * 2.0;

    // Unproject near point (Z=0.0) and far point (Z=1.0) to get exact forward ray direction in view space
    float4 clipNear = float4(ndcX, ndcY, 0.0, 1.0);
    float4 viewNearH = uniforms.inverseProjectionMatrix * clipNear;
    float3 viewNear = viewNearH.xyz / max(abs(viewNearH.w), 1.0e-7);

    float4 clipFar = float4(ndcX, ndcY, 1.0, 1.0);
    float4 viewFarH = uniforms.inverseProjectionMatrix * clipFar;
    float3 viewFar = viewFarH.xyz / max(abs(viewFarH.w), 1.0e-7);

    float3 rayDirView = normalize(viewFar - viewNear);
    float4 rayDirWorld4 = uniforms.viewToWorldMatrix * float4(rayDirView, 0.0);
    float3 rayDirWorld = normalize(rayDirWorld4.xyz);

    float tStart = max(uniforms.nearPlane, 0.05);
    float tEnd = min(uniforms.maxVolumetricDistance, uniforms.csmMaxDistance);
    if (rawDepth > 0.0001 && rawDepth < 0.9999) {
      float sceneNdcZ = rawDepth * 2.0 - 1.0;
      float4 sceneClip = float4(ndcX, ndcY, sceneNdcZ, 1.0);
      float4 sceneViewH = uniforms.inverseProjectionMatrix * sceneClip;
      if (abs(sceneViewH.w) > 1.0e-6) {
        float3 sceneView = sceneViewH.xyz / sceneViewH.w;
        tEnd = min(length(sceneView), uniforms.maxVolumetricDistance);
      }
    }

    // Sun direction vector in world space
    float4 toLightWorld4 = uniforms.viewToWorldMatrix * float4(env.directionAndFlags.xyz, 0.0);
    float3 toLightWorld = normalize(toLightWorld4.xyz);
    float cosTheta = dot(rayDirWorld, toLightWorld);
    float phase = henyeyGreensteinPhase(cosTheta, uniforms.anisotropyG);

    // Mode 16: COMPONENT_PHASE_FUNCTION_ONLY (Visualizes angular forward scattering concentration)
    if (uniforms.debugMode == 16u) {
      float maxPhase = henyeyGreensteinPhase(1.0, uniforms.anisotropyG);
      float phaseNorm = clamp(phase / max(maxPhase, 1.0e-4), 0.0, 1.0);
      return float4(godRayFalseColor(phaseNorm), 1.0);
    }

    // Mode 17: COMPONENT_EXTINCTION_ONLY (Visualizes Beer-Lambert optical transmittance over depth)
    if (uniforms.debugMode == 17u) {
      float transmittance = exp(-uniforms.sigmaExtinction * max(tEnd, 0.0));
      return float4(transmittance, transmittance, transmittance, 1.0);
    }

    float deltaS = max(uniforms.froxelCellSize.x * 0.5, 0.25);
    float s0 = dot(uniforms.cameraWorldPos.xyz, rayDirWorld);
    float kFirst = ceil((s0 + tStart) / deltaS);
    float tFirst = kFirst * deltaS - s0;
    if (tFirst < tStart) {
      tFirst += deltaS;
    }

    // Mode 19: FROXEL_CELL_ID_DEBUG (Each 3D froxel cell has a fixed unique RGB identifier)
    if (uniforms.debugMode == 19u) {
      float3 cellIdColor = float3(0.05, 0.05, 0.05);
      for (uint s = 0u; s < 96u; ++s) {
        float t = tFirst + float(s) * deltaS;
        if (t > tEnd) break;
        float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
        float3 relPos = (worldPos - uniforms.froxelGridOrigin.xyz) / max(uniforms.froxelCellSize.xyz, float3(0.01));
        float3 v = relPos - 0.5;
        if (v.x >= 0.0 && v.x <= float(uniforms.froxelGridDimensions.x - 1u) &&
            v.y >= 0.0 && v.y <= float(uniforms.froxelGridDimensions.y - 1u) &&
            v.z >= 0.0 && v.z <= float(uniforms.froxelGridDimensions.z - 1u)) {
          uint3 cellId = uint3(floor(v + 0.5));
          cellIdColor = float3(cellId) / float3(uniforms.froxelGridDimensions.xyz - 1u);
          break;
        }
      }
      return float4(cellIdColor, 1.0);
    }

    // Mode 21: FROXEL_WORLD_POSITION_DEBUG (Fixed world coordinates lattice in 3D space)
    if (uniforms.debugMode == 21u) {
      float3 worldCoordColor = float3(0.05, 0.05, 0.05);
      for (uint s = 0u; s < 96u; ++s) {
        float t = tFirst + float(s) * deltaS;
        if (t > tEnd) break;
        float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
        worldCoordColor = abs(fract(worldPos * 0.5) - 0.5) * 2.0;
        break;
      }
      return float4(worldCoordColor, 1.0);
    }

    float accumulatedLit = 0.0;
    float scatteredRadianceSum = 0.0;
    uint maxSteps = 96u;

    for (uint s = 0u; s < maxSteps; ++s) {
      float t = tFirst + float(s) * deltaS;
      if (t > tEnd) {
        break;
      }
      float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
      float rawVis = sampleFroxelAtlas(worldPos, visibilityTexture, uniforms);

      // GOD-RAYS-3.1: Crisp shadow boundary extraction to isolate aperture shafts and prevent diffuse room fog
      float shaftVis = smoothstep(0.35, 0.75, rawVis);
      accumulatedLit += shaftVis * deltaS;

      if (shaftVis > 0.001) {
        float transmittance = exp(-uniforms.sigmaExtinction * t);
        scatteredRadianceSum += shaftVis * uniforms.sigmaScattering * phase * transmittance * deltaS;
      }
    }

    // Mode 14: COMPONENT_RAW_VISIBILITY (Fraction of ray path inside CSM illuminated shaft)
    if (uniforms.debugMode == 14u) {
      float rayLength = max(tEnd - tStart, 0.05);
      float rawVis = clamp(accumulatedLit / rayLength, 0.0, 1.0);
      return float4(godRayFalseColor(rawVis), 1.0);
    }

    // Mode 20: RAW_FROXEL_VISIBILITY_ONLY / SUN_VISIBILITY_ONLY (Pure binary ray intersection with lit 3D froxel volume)
    if (uniforms.debugMode == 20u) {
      float hitLit = 0.0;
      for (uint s = 0u; s < maxSteps; ++s) {
        float t = tFirst + float(s) * deltaS;
        if (t > tEnd) break;
        float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
        float rawVis = sampleFroxelAtlas(worldPos, visibilityTexture, uniforms);
        if (rawVis >= 0.45) {
          hitLit = 1.0;
          break;
        }
      }
      return float4(hitLit, hitLit, hitLit, 1.0);
    }

    // Mode 15: RAW_SCATTERING_DENSITY / COMPONENT_SCATTERING_ONLY (Pure medium density integral without phase function or lighting)
    if (uniforms.debugMode == 15u) {
      float densityIntegral = 0.0;
      for (uint s = 0u; s < maxSteps; ++s) {
        float t = tFirst + float(s) * deltaS;
        if (t > tEnd) break;
        float3 worldPos = uniforms.cameraWorldPos.xyz + rayDirWorld * t;
        float rawVis = sampleFroxelAtlas(worldPos, visibilityTexture, uniforms);
        if (rawVis >= 0.15) {
          densityIntegral += uniforms.sigmaScattering * rawVis * deltaS;
        }
      }
      float normDensity = clamp(densityIntegral * 10.0, 0.0, 1.0);
      return float4(godRayFalseColor(normDensity), 1.0);
    }

    float3 sunRadiance = env.directionalRadiance.rgb;
    if (dot(sunRadiance, sunRadiance) <= 0.001) {
      sunRadiance = float3(1.0, 0.92, 0.78);
    }
    float userIntensity = clamp(uniforms.intensity, 0.0, 1.0);

    // Mode 22: SUN_SHAFT_ONLY (Isolated direct sun light shafts through apertures, strictly 0.0 in shadow)
    if (uniforms.debugMode == 22u) {
      float3 shaftRadiance = scatteredRadianceSum * sunRadiance * (userIntensity * 8.0);
      return float4(shaftRadiance, 0.0);
    }

    // Mode 13: HDR_PREVIEW & Mode 18: COMPONENT_FINAL_RADIANCE
    float3 finalRadiance = scatteredRadianceSum * sunRadiance * (userIntensity * 8.0);
    return float4(finalRadiance, 0.0);
  }

  // Mode 6: STABILITY / Mode 5: GRAYSCALE (Continuous neutral grayscale transfer function)
  if (uniforms.debugMode == 6u || uniforms.debugMode == 5u) {
    float2 visUv = float2(in.uv.x, 1.0 - in.uv.y);
    float v = clamp(visibilityTexture.sample(smp, visUv).r, 0.0, 1.0);
    return float4(v, v, v, 1.0);
  }

  // Mode 4: OVERLAY (World geometry depth shading + Golden Sun Shaft)
  if (uniforms.debugMode == 4u) {
    uint2 depthSize = uint2(sceneDepth.get_width(), sceneDepth.get_height());
    float2 texUv = float2(in.uv.x, 1.0 - in.uv.y);
    uint2 depthCoord = min(uint2(texUv * float2(depthSize)), max(depthSize, uint2(1u)) - 1u);
    float rawDepth = sceneDepth.read(depthCoord);
    float g = 0.0;
    if (rawDepth > 0.0) {
      float ndcX = in.uv.x * 2.0 - 1.0;
      float ndcY = in.uv.y * 2.0 - 1.0;
      float4 clip = float4(ndcX, ndcY, rawDepth, 1.0);
      float4 viewH = uniforms.inverseProjectionMatrix * clip;
      float3 view = viewH.xyz / max(viewH.w, 1.0e-7);
      float dist = length(view);
      g = clamp(1.0 - dist / 32.0, 0.05, 0.6);
    }
    float2 visUv = float2(in.uv.x, 1.0 - in.uv.y);
    float v = clamp(visibilityTexture.sample(smp, visUv).r, 0.0, 1.0);
    float3 shaftColor = float3(1.0, 0.85, 0.4) * v; // Golden sunbeam
    float3 sceneBase = float3(g * 0.4, g * 0.4, g * 0.45);
    return float4(sceneBase + shaftColor, 1.0);
  }

  // Mode 0 & 3: VISIBILITY / SHAFT_MASK (Unmistakable False-Color spectrum)
  float2 visUv = float2(in.uv.x, 1.0 - in.uv.y);
  float v = clamp(visibilityTexture.sample(smp, visUv).r, 0.0, 1.0);
  float3 color = godRayFalseColor(v);
  return float4(color, 1.0);
}

// ---------------------------------------------------------------------------
// GOD-RAYS-2.0 World-Space Froxel Visibility Volume Building Shader
// ---------------------------------------------------------------------------

fragment float4 metallum_god_ray_froxel_build_fs(
  HdrVertexOut in [[stage_in]],
  depth2d<float> shadowCascade0 [[texture(13)]],
  depth2d<float> shadowCascade1 [[texture(14)]],
  depth2d<float> shadowCascade2 [[texture(15)]],
  sampler shadowSampler0 [[sampler(13)]],
  sampler shadowSampler1 [[sampler(14)]],
  sampler shadowSampler2 [[sampler(15)]],
  constant GodRayVisibilityUniforms& uniforms [[buffer(0)]],
  constant MetallumEnvironmentShadowV1& env [[buffer(26)]]
) {
  // Atlas size: 512 x 144 (8x4 grid of 64x36 slices)
  uint px = min(uint(in.position.x), 511u);
  uint py = min(uint(in.position.y), 143u);

  uint tileX = px / 64u;
  uint tileY = py / 36u;
  uint k = tileX + tileY * 8u; // Slice index in [0, 31]
  uint i = px % 64u;          // Voxel X in [0, 63]
  uint j = py % 36u;          // Voxel Y in [0, 35]

  if (k >= uniforms.froxelGridDimensions.z
      || i >= uniforms.froxelGridDimensions.x
      || j >= uniforms.froxelGridDimensions.y) {
    return float4(0.0, 0.0, 0.0, 1.0);
  }

  // World-space center of voxel (i, j, k)
  float3 voxelIndex = float3(float(i) + 0.5, float(j) + 0.5, float(k) + 0.5);
  float3 worldPos = uniforms.froxelGridOrigin.xyz + voxelIndex * uniforms.froxelCellSize.xyz;

  // Transform worldPos to Camera-Relative View space
  float3 camRelPos = worldPos - uniforms.cameraWorldPos.xyz;
  float3 posView = (uniforms.worldToViewMatrix * float4(camRelPos, 0.0)).xyz;

  // Evaluate CSM shadow visibility
  float vis = evaluateVisibilityAtPoint(
    posView, uniforms, env,
    shadowCascade0, shadowSampler0,
    shadowCascade1, shadowSampler1,
    shadowCascade2, shadowSampler2
  );

  return float4(vis, 0.0, 0.0, 1.0);
}
