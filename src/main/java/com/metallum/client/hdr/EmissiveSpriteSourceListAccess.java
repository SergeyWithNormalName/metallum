package com.metallum.client.hdr;

/** Internal state added to SpriteSourceList for the one blocks-atlas emissive injection point. */
public interface EmissiveSpriteSourceListAccess {
    void metallum$markBlockAtlas();

    boolean metallum$isBlockAtlas();
}
