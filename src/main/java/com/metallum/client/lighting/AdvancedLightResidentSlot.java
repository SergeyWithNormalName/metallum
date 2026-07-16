package com.metallum.client.lighting;

/** Owner token attached to the exact RenderSection that received an accepted candidate. */
public interface AdvancedLightResidentSlot {
    void metallum$bindAdvancedLightSection(Object worldIdentity, long sectionKey);

    Object metallum$getAdvancedLightWorldIdentity();

    long metallum$getAdvancedLightSectionKey();

    long metallum$getAdvancedLightOwnerToken();

    void metallum$setAdvancedLightOwnerToken(long ownerToken);
}
