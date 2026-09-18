package com.metallum.client.gi.semantic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, order-independent G2 material palette. IDs and digest are a versioned CPU ABI. */
public final class GiSemanticPalette {
    public static final int FORMAT_VERSION = 1;
    public static final int UNKNOWN_ID = 0;
    public static final int AIR_ID = 1;
    public static final int FALLBACK_ID = 2;
    public static final int DEFAULT_CAPACITY = 1 << 16;
    public static final String AIR_KEY = "metallum:reserved/air";
    public static final String FALLBACK_KEY = "metallum:reserved/conservative_fallback";
    private static final String UNKNOWN_KEY = "metallum:reserved/unknown";

    public record Entry(
            int id,
            String canonicalKey,
            GiSemanticMaterial material,
            GiSemanticMedium medium,
            short albedoRed,
            short albedoGreen,
            short albedoBlue,
            short emissionRed,
            short emissionGreen,
            short emissionBlue,
            short emissionIntensity,
            int provenance
    ) {
        public Entry {
            if (id < 0 || id >= DEFAULT_CAPACITY || canonicalKey == null || canonicalKey.isBlank()
                    || material == null || medium == null) {
                throw new IllegalArgumentException("Invalid G2 palette entry");
            }
            GiSemanticProvenance.requireMask(provenance);
        }
    }

    public record Seed(
            String canonicalKey,
            GiSemanticMaterial material,
            GiSemanticMedium medium,
            float albedoRed,
            float albedoGreen,
            float albedoBlue,
            float emissionRed,
            float emissionGreen,
            float emissionBlue,
            float emissionIntensity,
            int provenance
    ) {
        public Seed {
            if (canonicalKey == null || canonicalKey.isBlank() || material == null || medium == null) {
                throw new IllegalArgumentException("Invalid G2 palette seed");
            }
            canonicalKey = canonicalKey.trim();
            GiSemanticProvenance.requireMask(provenance);
            requireUnit(albedoRed, "albedo red");
            requireUnit(albedoGreen, "albedo green");
            requireUnit(albedoBlue, "albedo blue");
            requireUnit(emissionRed, "emission red");
            requireUnit(emissionGreen, "emission green");
            requireUnit(emissionBlue, "emission blue");
            requireUnit(emissionIntensity, "emission intensity");
        }
    }

    private final long generation;
    private final long resourceEpoch;
    private final long materialEpoch;
    private final int capacity;
    private final List<Entry> entries;
    private final Map<String, Integer> ids;
    private final int overflowedKeys;
    private final String digest;

    private GiSemanticPalette(
            final long generation,
            final long resourceEpoch,
            final long materialEpoch,
            final int capacity,
            final List<Entry> entries,
            final Map<String, Integer> ids,
            final int overflowedKeys
    ) {
        this.generation = generation;
        this.resourceEpoch = resourceEpoch;
        this.materialEpoch = materialEpoch;
        this.capacity = capacity;
        this.entries = List.copyOf(entries);
        this.ids = Map.copyOf(ids);
        this.overflowedKeys = overflowedKeys;
        this.digest = digest(generation, resourceEpoch, materialEpoch, capacity, entries, ids, overflowedKeys);
    }

    public static GiSemanticPalette build(
            final long generation,
            final long resourceEpoch,
            final long materialEpoch,
            final Collection<Seed> seeds
    ) {
        return build(generation, resourceEpoch, materialEpoch, DEFAULT_CAPACITY, seeds);
    }

    public static GiSemanticPalette build(
            final long generation,
            final long resourceEpoch,
            final long materialEpoch,
            final int capacity,
            final Collection<Seed> seeds
    ) {
        if (generation <= 0L || resourceEpoch <= 0L || materialEpoch <= 0L
                || capacity < 3 || capacity > DEFAULT_CAPACITY) {
            throw new IllegalArgumentException("Invalid G2 palette generations or capacity");
        }
        Objects.requireNonNull(seeds, "seeds");
        Map<String, Seed> unique = new HashMap<>();
        for (Seed seed : seeds) {
            Objects.requireNonNull(seed, "palette seed");
            if (UNKNOWN_KEY.equals(seed.canonicalKey()) || AIR_KEY.equals(seed.canonicalKey())
                    || FALLBACK_KEY.equals(seed.canonicalKey())) {
                throw new IllegalArgumentException("G2 palette seed uses a reserved key: " + seed.canonicalKey());
            }
            Seed prior = unique.putIfAbsent(seed.canonicalKey(), seed);
            if (prior != null && !prior.equals(seed)) {
                throw new IllegalArgumentException("Conflicting G2 palette key: " + seed.canonicalKey());
            }
        }
        List<Seed> sorted = unique.values().stream()
                .sorted(Comparator.comparing(Seed::canonicalKey))
                .toList();
        List<Entry> entries = new ArrayList<>(Math.min(capacity, sorted.size() + 3));
        Map<String, Integer> ids = new LinkedHashMap<>();
        addReserved(entries, ids, UNKNOWN_ID, UNKNOWN_KEY, GiSemanticMedium.UNKNOWN_CONSERVATIVE,
                GiSemanticValidity.UNKNOWN);
        addReserved(entries, ids, AIR_ID, AIR_KEY, GiSemanticMedium.AIR, GiSemanticValidity.KNOWN_EMPTY);
        addReserved(entries, ids, FALLBACK_ID, FALLBACK_KEY, GiSemanticMedium.UNKNOWN_CONSERVATIVE,
                GiSemanticValidity.KNOWN_FALLBACK);
        int accepted = Math.min(sorted.size(), capacity - 3);
        for (int index = 0; index < accepted; index++) {
            Seed seed = sorted.get(index);
            int id = index + 3;
            Entry entry = fromSeed(id, seed);
            entries.add(entry);
            ids.put(seed.canonicalKey(), id);
        }
        for (int index = accepted; index < sorted.size(); index++) {
            ids.put(sorted.get(index).canonicalKey(), FALLBACK_ID);
        }
        return new GiSemanticPalette(
                generation, resourceEpoch, materialEpoch, capacity, entries, ids, sorted.size() - accepted
        );
    }

    public long generation() {
        return this.generation;
    }

    public long resourceEpoch() {
        return this.resourceEpoch;
    }

    public long materialEpoch() {
        return this.materialEpoch;
    }

    public int capacity() {
        return this.capacity;
    }

    public int size() {
        return this.entries.size();
    }

    public int overflowedKeys() {
        return this.overflowedKeys;
    }

    public String digest() {
        return this.digest;
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public int idFor(final String canonicalKey) {
        if (canonicalKey == null) {
            return FALLBACK_ID;
        }
        return this.ids.getOrDefault(canonicalKey, FALLBACK_ID);
    }

    public boolean isPaletteFallback(final String canonicalKey) {
        return canonicalKey == null || this.ids.getOrDefault(canonicalKey, FALLBACK_ID) == FALLBACK_ID;
    }

    public Entry entry(final int id) {
        if (id < 0 || id >= this.entries.size()) {
            return this.entries.get(FALLBACK_ID);
        }
        return this.entries.get(id);
    }

    private static Entry fromSeed(final int id, final Seed seed) {
        return new Entry(
                id, seed.canonicalKey(), seed.material(), seed.medium(),
                GiSemanticPacking.unorm16(seed.albedoRed()),
                GiSemanticPacking.unorm16(seed.albedoGreen()),
                GiSemanticPacking.unorm16(seed.albedoBlue()),
                GiSemanticPacking.unorm16(seed.emissionRed()),
                GiSemanticPacking.unorm16(seed.emissionGreen()),
                GiSemanticPacking.unorm16(seed.emissionBlue()),
                GiSemanticPacking.unorm16(seed.emissionIntensity()),
                seed.provenance()
        );
    }

    private static void addReserved(
            final List<Entry> entries,
            final Map<String, Integer> ids,
            final int id,
            final String key,
            final GiSemanticMedium medium,
            final GiSemanticValidity validity
    ) {
        int provenance = switch (validity) {
            case UNKNOWN -> 0;
            case KNOWN_EMPTY -> GiSemanticProvenance.AUTHORITATIVE_EMPTY;
            case KNOWN_FALLBACK -> GiSemanticProvenance.PALETTE_FALLBACK;
            case KNOWN_CONTENT -> throw new IllegalArgumentException("Reserved content entry is unsupported");
        };
        entries.add(new Entry(
                id, key, GiSemanticMaterial.DIELECTRIC, medium,
                (short) 0, (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0, (short) 0, provenance
        ));
        ids.put(key, id);
    }

    private static String digest(
            final long generation,
            final long resourceEpoch,
            final long materialEpoch,
            final int capacity,
            final List<Entry> entries,
            final Map<String, Integer> ids,
            final int overflowed
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer header = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(FORMAT_VERSION).putInt(capacity).putLong(generation)
                    .putLong(resourceEpoch).putLong(materialEpoch).putInt(entries.size()).putInt(overflowed);
            digest.update(header.array());
            for (Entry entry : entries) {
                byte[] key = entry.canonicalKey().getBytes(StandardCharsets.UTF_8);
                ByteBuffer packet = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                packet.putInt(entry.id()).putInt(key.length)
                        .putInt(entry.material().abiId()).putInt(entry.medium().mask())
                        .putShort(entry.albedoRed()).putShort(entry.albedoGreen()).putShort(entry.albedoBlue())
                        .putShort(entry.emissionRed()).putShort(entry.emissionGreen()).putShort(entry.emissionBlue())
                        .putShort(entry.emissionIntensity()).put((byte) entry.provenance()).put((byte) 0);
                digest.update(packet.array());
                digest.update(key);
            }
            ids.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(mapping -> {
                byte[] key = mapping.getKey().getBytes(StandardCharsets.UTF_8);
                ByteBuffer packet = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                packet.putInt(mapping.getValue()).putInt(key.length);
                digest.update(packet.array());
                digest.update(key);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void requireUnit(final float value, final String label) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException("G2 " + label + " must be finite and in [0,1]");
        }
    }
}
