package dev.schwalbe.autovalley.core;

/** A candidate one-block stair-up edge, not an unrestricted jump permission. */
public record LoggingJumpEdge(Pos from,Pos to) { }
