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
