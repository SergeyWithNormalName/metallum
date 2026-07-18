package com.metallum.client.lighting;

/**
 * Internal scheduling class for the local voxel-shadow path.
 *
 * <p>The class deliberately does not change {@link LightSourceKind}: upload ABI consumers still
 * distinguish only block and entity lights. It instead tells the shadow scheduler whether a
 * source can use the static cache, moves with an entity, or must be rebuilt every camera frame.</p>
 */
public enum LocalShadowSourceClass {
    STATIC_CACHE,
    ENTITY_DYNAMIC,
    CAMERA_HELD
}
