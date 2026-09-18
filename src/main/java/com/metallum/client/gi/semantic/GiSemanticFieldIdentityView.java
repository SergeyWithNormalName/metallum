package com.metallum.client.gi.semantic;

/**
 * Allocation-free identity shared by the bounded G3 and G6 views of accepted G2 truth.
 * It exposes no payload copy operation and therefore cannot grant either source or transport
 * ownership to the other stage.
 */
public interface GiSemanticFieldIdentityView {
    GiSemanticWorldToken world();
    long clipmapGeneration();
    long paletteGeneration();
    long contentGeneration();
    int originComponent(int index);
}
