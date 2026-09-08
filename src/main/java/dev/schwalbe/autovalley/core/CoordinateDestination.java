package dev.schwalbe.autovalley.core;

/** An unverified destination, never an authorized work location. A null kind means player feet. */
public record CoordinateDestination(String name, Pos pos, PoiKind facilityKind, Integer classifier,
                                    boolean contentsConfirmed) { }
