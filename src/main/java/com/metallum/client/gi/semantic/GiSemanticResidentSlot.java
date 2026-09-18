package com.metallum.client.gi.semantic;

public interface GiSemanticResidentSlot {
    void metallum$bindGiSemanticSection(Object worldIdentity, long sectionKey);

    Object metallum$getGiSemanticWorldIdentity();

    long metallum$getGiSemanticSectionKey();

    long metallum$getGiSemanticOwnerToken();

    void metallum$setGiSemanticOwnerToken(long ownerToken);
}
