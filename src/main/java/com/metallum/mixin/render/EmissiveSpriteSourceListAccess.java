package com.metallum.mixin.render;

/** Internal state added to SpriteSourceList for the one blocks-atlas emissive injection point. */
interface EmissiveSpriteSourceListAccess {
    void metallum$markBlockAtlas();

    boolean metallum$isBlockAtlas();
}
